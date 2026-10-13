package com.naury.framekit.picker

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.ui.picker.MediaPickerRequest
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PickerViewModelTest {

    private fun item(id: Long, video: Boolean = false, bucket: String = "camera", size: Long = 1_000, duration: Long = 0, mime: String? = null) = MediaItem(
        id = id,
        uri = Uri.parse("content://media/external/${if (video) "video" else "images"}/media/$id"),
        isVideo = video,
        mimeType = mime ?: if (video) "video/mp4" else "image/jpeg",
        sizeBytes = size,
        width = 4000,
        height = 3000,
        durationMs = duration,
        bucketId = bucket,
        bucketName = bucket.replaceFirstChar { it.uppercase() },
        orientation = if (video) 0 else 90,
    )

    private val library = listOf(
        item(1), item(2, video = true, duration = 5_000), item(3, bucket = "screenshots"), item(4), item(5, size = 50_000_000),
        item(6, video = true, bucket = "movies", duration = 120_000), item(7, mime = "image/gif"),
    )

    private fun viewModel(request: MediaPickerRequest, savedState: SavedStateHandle = SavedStateHandle()) =
        PickerViewModel(request, savedState, { kind, onChunk -> onChunk(library.filter { kind != MediaKind.IMAGE || !it.isVideo }) }, Dispatchers.Unconfined)
            .also { it.onAccess(Access.FULL) }

    @Test
    fun `selection keeps the order and renumbers when one is removed`() {
        val vm = viewModel(MediaPickerRequest(MediaKind.ANY, maxItems = 3))
        val items = vm.state.value.items.associateBy { it.id }
        vm.toggle(items.getValue(4))
        vm.toggle(items.getValue(1))
        vm.toggle(items.getValue(2))
        assertThat(vm.state.value.selection).containsExactly(4L, 1L, 2L).inOrder()
        // 최대 개수에 닿으면 더 고르지 않고 알린다.
        vm.toggle(items.getValue(3))
        assertThat(vm.state.value.message).isEqualTo(PickerMessage.LimitReached(3))
        assertThat(vm.state.value.selection).hasSize(3)
        // 가운데를 빼면 뒤의 번호가 당겨진다.
        vm.toggle(items.getValue(1))
        assertThat(vm.state.value.orderOf(2)).isEqualTo(2)

        vm.confirm()
        val result = vm.result.value!!
        assertThat(result.map { it.uri.lastPathSegment }).containsExactly("4", "2").inOrder()
        // 90도 돌아간 사진은 똑바로 선 크기로 돌려준다.
        assertThat(result.first().width).isEqualTo(3000)
        assertThat(result.last().durationMs).isEqualTo(5_000)
    }

    @Test
    fun `limits block large files long videos and hide other formats`() {
        val vm = viewModel(MediaPickerRequest(MediaKind.ANY, maxItems = 5, maxFileSizeBytes = 10_000_000, maxVideoDurationMs = 60_000, mimeTypes = setOf("image/jpeg", "video/*")))
        val ids = vm.state.value.items.map { it.id }
        // GIF는 형식이 맞지 않아 보이지 않는다.
        assertThat(ids).doesNotContain(7L)
        val items = vm.state.value.items.associateBy { it.id }
        vm.toggle(items.getValue(5))
        assertThat(vm.state.value.message).isEqualTo(PickerMessage.Blocked(Block.TOO_LARGE))
        vm.toggle(items.getValue(6))
        assertThat(vm.state.value.message).isEqualTo(PickerMessage.Blocked(Block.TOO_LONG))
        assertThat(vm.state.value.selection).isEmpty()
    }

    @Test
    fun `single choice finishes on tap and minimum count is enforced`() {
        val single = viewModel(MediaPickerRequest(MediaKind.IMAGE, maxItems = 1))
        assertThat(single.state.value.items.none { it.isVideo }).isTrue()
        single.toggle(single.state.value.items.first())
        assertThat(single.result.value).hasSize(1)

        val atLeastTwo = viewModel(MediaPickerRequest(MediaKind.ANY, maxItems = 4, minItems = 2))
        atLeastTwo.toggle(atLeastTwo.state.value.items.first())
        atLeastTwo.confirm()
        assertThat(atLeastTwo.result.value).isNull()
        assertThat(atLeastTwo.state.value.message).isEqualTo(PickerMessage.MinItems(2))
    }

    @Test
    fun `albums group folders and keep the selection across albums and recreation`() {
        val savedState = SavedStateHandle()
        val vm = viewModel(MediaPickerRequest(MediaKind.ANY, maxItems = 10), savedState)
        val albums = vm.state.value.albums
        assertThat(albums.first().kind).isEqualTo(AlbumKind.ALL)
        assertThat(albums.map { it.id }).contains(Album.VIDEOS_ID)
        // 폴더는 항목이 많은 순서다.
        assertThat(albums.filter { it.kind == AlbumKind.FOLDER }.map { it.name }).containsExactly("Camera", "Movies", "Screenshots").inOrder()

        vm.toggle(vm.state.value.items.first { it.id == 1L })
        vm.selectAlbum(Album.VIDEOS_ID)
        assertThat(vm.state.value.albumItems.all { it.isVideo }).isTrue()
        vm.toggle(vm.state.value.albumItems.first())

        val restored = viewModel(MediaPickerRequest(MediaKind.ANY, maxItems = 10), savedState)
        assertThat(restored.state.value.selection).containsExactly(1L, 2L).inOrder()
        assertThat(restored.state.value.albumId).isEqualTo(Album.VIDEOS_ID)
    }

    @Test
    fun `request validation and mime matching`() {
        assertThat(MediaPickerRequest(MediaKind.AUDIO).validate().isValid).isFalse()
        assertThat(MediaPickerRequest(maxItems = 0).validate().isValid).isFalse()
        assertThat(MediaPickerRequest(maxItems = 3, minItems = 4).validate().isValid).isFalse()
        assertThat(MediaPickerRequest(mimeTypes = setOf("jpeg")).validate().isValid).isFalse()
        val request = MediaPickerRequest(mimeTypes = setOf("image/*", "video/mp4"))
        assertThat(request.accepts("image/heic")).isTrue()
        assertThat(request.accepts("video/mp4")).isTrue()
        assertThat(request.accepts("video/webm")).isFalse()
        assertThat(formatDuration(65_000)).isEqualTo("1:05")
        assertThat(formatDuration(3_725_000)).isEqualTo("1:02:05")
    }
}
