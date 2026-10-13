package com.naury.framekit.picker

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.ui.config.ThemeMode
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.design.ProvideEditorLocale
import com.naury.framekit.ui.picker.MediaPickerContract
import com.naury.framekit.ui.picker.MediaPickerRequest
import com.naury.framekit.ui.picker.PickedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 앱 안 갤러리. [MediaPickerContract]가 열며 exported되지 않는다.
 *
 * 호스트가 사진 읽기 권한을 선언했으면 권한을 확인·요청한 뒤 갤러리를 보여 준다. 선언하지 않았거나 거부되면 화면을
 * 그리지 않고 시스템 사진 선택기를 열어, 고른 결과에 같은 제한을 적용해 돌려준다.
 */
internal class MediaPickerActivity : ComponentActivity() {

    private lateinit var request: MediaPickerRequest
    private var mode = Mode.DECIDING
    private var delivered = false

    private val viewModel: PickerViewModel by viewModels {
        viewModelFactory {
            initializer {
                PickerViewModel(request, createSavedStateHandle(), MediaStoreLibrary(applicationContext.contentResolver), Dispatchers.IO)
            }
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val access = MediaAccess.current(this, request.kind)
        when {
            mode == Mode.GALLERY -> viewModel.onAccess(access)
            access == Access.NONE -> openSystemPicker()
            else -> showGallery(access)
        }
    }

    private val systemPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) return@registerForActivityResult cancel()
        val uris = MediaPickerContract.systemUris(data)
        lifecycleScope.launch {
            val described = withContext(Dispatchers.IO) { uris.map { describe(this@MediaPickerActivity, it) } }
            // 시스템 선택기는 제한을 모르므로 고른 뒤 걸러 낸다.
            val accepted = described.filter { request.accepts(it.mimeType) && request.blockOf(it.sizeBytes, it.durationMs, it.isVideo) == null }
                .take(request.maxItems)
            if (accepted.isEmpty()) cancel() else deliver(accepted, skipped = described.size - accepted.size)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = IntentCompat.getParcelableExtra(intent, MediaPickerContract.EXTRA_REQUEST, MediaPickerRequest::class.java)
        if (parsed == null || parsed.validate() is ValidationResult.Invalid) return cancel()
        request = parsed
        // 갤러리가 상태 표시줄 아래까지 그려지도록 창을 만들 때 바로 적용한다.
        val darkBars = parsed.ui.themeMode != ThemeMode.LIGHT
        enableEdgeToEdge(
            statusBarStyle = if (darkBars) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (darkBars) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        mode = savedInstanceState?.getString(KEY_MODE)?.let(Mode::valueOf) ?: Mode.DECIDING
        when (mode) {
            // 다시 만들어졌으면 갤러리를 다시 그린다. 시스템 선택기·권한 창은 결과가 그대로 돌아온다.
            Mode.GALLERY -> showGallery(MediaAccess.current(this, request.kind))
            Mode.DECIDING -> decide()
            Mode.SYSTEM, Mode.REQUESTING -> Unit
        }
    }

    override fun onResume() {
        super.onResume()
        // 설정 화면에서 권한을 바꾸고 돌아왔을 수 있다.
        if (mode == Mode.GALLERY) {
            val access = MediaAccess.current(this, request.kind)
            if (access != viewModel.state.value.access) viewModel.onAccess(access)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_MODE, mode.name)
    }

    private fun decide() {
        val access = MediaAccess.current(this, request.kind)
        when {
            access != Access.NONE -> showGallery(access)
            MediaAccess.declared(this, request.kind) -> {
                mode = Mode.REQUESTING
                permissions.launch(MediaAccess.requestable(this, request.kind))
            }
            else -> openSystemPicker()
        }
    }

    private fun showGallery(access: Access) {
        mode = Mode.GALLERY
        val ui = request.ui
        if (viewModel.state.value.access != access || viewModel.state.value.items.isEmpty()) viewModel.onAccess(access)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { deliver(viewModel.result.filterNotNull().first(), skipped = 0) }
        }
        val loader = ThumbnailLoader(applicationContext.contentResolver)
        setContent {
            ProvideEditorLocale(ui.localeTag) {
                FrameKitTheme(ui) {
                    PickerScreen(
                        viewModel = viewModel,
                        loader = loader,
                        onClose = ::cancel,
                        onManageAccess = { permissions.launch(MediaAccess.requestable(this, request.kind)) },
                        onSystemPicker = ::openSystemPicker,
                    )
                }
            }
        }
    }

    private fun openSystemPicker() {
        mode = Mode.SYSTEM
        try {
            systemPicker.launch(MediaPickerContract.systemIntent(this, request))
        } catch (_: android.content.ActivityNotFoundException) {
            cancel()
        }
    }

    private fun deliver(items: List<PickedMedia>, skipped: Int) {
        if (delivered) return
        delivered = true
        val data = Intent()
            .putParcelableArrayListExtra(MediaPickerContract.EXTRA_RESULT, ArrayList(items))
            .putExtra(MediaPickerContract.EXTRA_SKIPPED, skipped)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // 받는 쪽이 다른 프로세스여도 읽을 수 있게 Uri를 ClipData에도 담아 읽기 권한을 넘긴다.
        items.firstOrNull()?.let { first ->
            val clip = ClipData.newRawUri(null, first.uri)
            items.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
            data.clipData = clip
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun cancel() {
        if (delivered) return
        delivered = true
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private enum class Mode { DECIDING, REQUESTING, GALLERY, SYSTEM }

    private companion object {
        const val KEY_MODE = "framekit_picker_mode"

        // 시스템 선택기가 준 Uri의 형식·크기·길이·크기(px)를 읽는다. 알 수 없는 값은 비운다.
        fun describe(context: Context, uri: Uri): PickedMedia {
            val resolver = context.contentResolver
            val mime = runCatching { resolver.getType(uri) }.getOrNull()
            var size: Long? = null
            var duration: Long? = null
            var width: Int? = null
            var height: Int? = null
            runCatching {
                resolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        fun long(name: String) = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
                        size = long(OpenableColumns.SIZE)
                        duration = long("duration_millis") ?: long("duration")
                        width = long(MediaStore.MediaColumns.WIDTH)?.toInt()?.takeIf { it > 0 }
                        height = long(MediaStore.MediaColumns.HEIGHT)?.toInt()?.takeIf { it > 0 }
                    }
                }
            }
            return PickedMedia(uri, mime, size?.takeIf { it > 0 }, width, height, duration?.takeIf { it > 0 && mime?.startsWith("video/") == true })
        }
    }
}
