package com.naury.framekit.picker

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.ui.picker.MediaPickerRequest
import com.naury.framekit.ui.picker.PickedMedia
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 사용자에게 잠깐 보여 줄 안내. */
internal sealed interface PickerMessage {
    data class LimitReached(val max: Int) : PickerMessage
    data class MinItems(val min: Int) : PickerMessage
    data class Blocked(val block: Block) : PickerMessage
}

/**
 * 갤러리 화면 상태.
 *
 * @property loading 목록을 읽는 중이면 `true`. 첫 묶음이 오면 [items]가 채워진 채로 계속 `true`일 수 있다.
 * @property items 요청에 맞는 전체 항목(최근 것부터).
 * @property selection 고른 항목 id. 고른 순서다.
 * @property preview 미리보기로 연 항목의 [albumItems] 위치. 닫혀 있으면 `null`.
 */
internal data class PickerUiState(
    val access: Access = Access.NONE,
    val loading: Boolean = true,
    val items: List<MediaItem> = emptyList(),
    val albums: List<Album> = emptyList(),
    val albumId: String = Album.ALL_ID,
    val showAlbums: Boolean = false,
    val selection: List<Long> = emptyList(),
    val preview: Int? = null,
    val message: PickerMessage? = null,
) {
    val album: Album? get() = albums.firstOrNull { it.id == albumId }

    /** 지금 앨범의 항목. */
    val albumItems: List<MediaItem> by lazy { album?.let { a -> items.filter(a::contains) } ?: items }

    /** [id]가 몇 번째로 골랐는지(1부터). 고르지 않았으면 0. */
    fun orderOf(id: Long): Int = selection.indexOf(id) + 1
}

/** 앱 안 갤러리의 상태와 동작. 고른 순서는 화면을 다시 만들어도 남는다. */
internal class PickerViewModel(
    val request: MediaPickerRequest,
    private val savedState: SavedStateHandle,
    private val library: MediaLibrary,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(
        PickerUiState(
            selection = savedState.get<LongArray>(KEY_SELECTION)?.toList().orEmpty(),
            albumId = savedState.get<String>(KEY_ALBUM) ?: Album.ALL_ID,
        ),
    )
    val state: StateFlow<PickerUiState> = _state.asStateFlow()

    private val _result = MutableStateFlow<List<PickedMedia>?>(null)

    /** 고르기를 마치면 고른 순서대로 담긴다. */
    val result: StateFlow<List<PickedMedia>?> = _result.asStateFlow()

    private var loadJob: Job? = null

    /** 권한 상태가 정해졌거나 바뀌었을 때 부른다. 읽을 수 있으면 목록을 (다시) 읽는다. */
    fun onAccess(access: Access) {
        _state.update { it.copy(access = access) }
        if (access != Access.NONE) reload()
    }

    /** 목록을 처음부터 다시 읽는다. 지금 고른 것 중 더는 없는 항목은 뺀다. */
    fun reload() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            var first = true
            val collected = ArrayList<MediaItem>()
            withContext(ioDispatcher) {
                library.load(request.kind) { chunk ->
                    collected += chunk.filter(request::shows)
                    // 첫 묶음은 바로 보여 주고, 나머지는 모두 읽은 뒤 한 번에 바꿔 목록이 자꾸 흔들리지 않게 한다.
                    if (first) {
                        first = false
                        val snapshot = collected.toList()
                        _state.update { it.copy(items = snapshot, albums = Albums.of(snapshot, request.kind == MediaKind.ANY)) }
                    }
                }
            }
            val items = collected.toList()
            val ids = items.mapTo(HashSet()) { it.id }
            val albums = Albums.of(items, request.kind == MediaKind.ANY)
            _state.update { current ->
                current.copy(
                    loading = false,
                    items = items,
                    albums = albums,
                    albumId = current.albumId.takeIf { id -> albums.any { it.id == id } } ?: Album.ALL_ID,
                    selection = current.selection.filter { it in ids },
                )
            }
            persist()
        }
    }

    fun toggleAlbums(show: Boolean) = _state.update { it.copy(showAlbums = show) }

    fun selectAlbum(id: String) {
        _state.update { current -> if (current.albums.none { it.id == id }) current else current.copy(albumId = id, showAlbums = false, preview = null) }
        persist()
    }

    /** 칸을 눌렀다. 하나만 고르는 요청이면 고르는 즉시 끝낸다. */
    fun toggle(item: MediaItem) {
        when (val outcome = request.toggle(_state.value.selection, item)) {
            is Toggle.Changed -> {
                _state.update { it.copy(selection = outcome.selection) }
                persist()
                if (request.maxItems == 1 && outcome.selection.isNotEmpty() && _state.value.preview == null) confirm()
            }
            Toggle.LimitReached -> _state.update { it.copy(message = PickerMessage.LimitReached(request.maxItems)) }
            is Toggle.Blocked -> _state.update { it.copy(message = PickerMessage.Blocked(outcome.block)) }
        }
    }

    fun openPreview(index: Int) = _state.update { if (index in it.albumItems.indices) it.copy(preview = index) else it }

    /** [item]을 크게 본다. 지금 앨범에 없으면 전체 앨범으로 옮겨서 연다. */
    fun openPreviewOf(item: MediaItem) = _state.update { current ->
        val index = current.albumItems.indexOfFirst { it.id == item.id && it.isVideo == item.isVideo }
        if (index >= 0) return@update current.copy(preview = index, showAlbums = false)
        val all = current.copy(albumId = Album.ALL_ID, showAlbums = false)
        val allIndex = all.albumItems.indexOfFirst { it.id == item.id && it.isVideo == item.isVideo }
        if (allIndex >= 0) all.copy(preview = allIndex) else current
    }

    fun closePreview() = _state.update { it.copy(preview = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** 고른 것을 돌려준다. 최소 개수보다 적으면 안내만 띄운다. */
    fun confirm() {
        val current = _state.value
        if (current.selection.size < request.minItems) {
            _state.update { it.copy(message = PickerMessage.MinItems(request.minItems)) }
            return
        }
        val byId = current.items.associateBy { it.id }
        val picked = current.selection.mapNotNull { byId[it]?.toPicked() }
        if (picked.isNotEmpty()) _result.value = picked
    }

    private fun persist() {
        val current = _state.value
        savedState[KEY_SELECTION] = current.selection.toLongArray()
        savedState[KEY_ALBUM] = current.albumId
    }

    private companion object {
        const val KEY_SELECTION = "framekit_picker_selection"
        const val KEY_ALBUM = "framekit_picker_album"
    }
}
