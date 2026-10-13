package com.naury.framekit.scanner

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.naury.framekit.android.capture.CaptureFiles
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.android.result.EditorError
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.result.FrameKitResultCodec
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.ui.component.EditorLoadingView
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.design.ProvideEditorLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * 카메라 문서 스캐너. [DocumentScanContract]로 열며 exported되지 않는다.
 *
 * 호스트가 `CAMERA` 권한을 선언했으면 권한을 받아 앱 안 카메라로 찍는다. 선언하지 않았거나 거부되면 기기 카메라 앱으로 한 장을
 * 찍어 같은 방식으로 편 뒤 바로 돌려준다.
 */
internal class DocumentScannerActivity : ComponentActivity() {

    private lateinit var request: DocumentScanRequest
    private var delivered = false
    private var systemPhoto: File? = null

    private val viewModel: ScannerViewModel by viewModels {
        viewModelFactory {
            initializer {
                val maker = PageMaker(applicationContext)
                ScannerViewModel(request, { photo, quad -> maker.make(photo, quad, request.scanMode) }, maker::discard, Dispatchers.IO)
            }
        }
    }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showCamera() else openSystemCamera()
    }

    private val systemCamera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = systemPhoto ?: savedPhotoPath?.let(::File)
        systemPhoto = null
        if (ok && file != null && CaptureFiles.isFilled(file)) {
            // 한 장을 펴는 동안 진행 화면을 보이고, 끝나면 바로 돌려준다.
            setContent { Themed { Box(Modifier.fillMaxSize().background(FrameKitTheme.colors.background)) { EditorLoadingView() } } }
            viewModel.onPhoto(file)
            lifecycleScope.launch {
                val state = viewModel.state.first { !it.busy }
                if (state.pages.isEmpty()) deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.DECODE_FAILED))) else viewModel.finish()
            }
        } else {
            file?.delete()
            deliver(FrameKitResult.Cancelled)
        }
    }

    private var savedPhotoPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = IntentCompat.getParcelableExtra(intent, DocumentScanContract.EXTRA_REQUEST, DocumentScanRequest::class.java)
        if (parsed == null || parsed.validate() is ValidationResult.Invalid) {
            deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.INVALID_CONFIGURATION)))
            return
        }
        request = parsed
        savedPhotoPath = savedInstanceState?.getString(KEY_SYSTEM_PHOTO)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { deliver(viewModel.result.filterNotNull().first()) }
        }
        if (savedInstanceState != null && savedPhotoPath != null) return
        when {
            hasCamera(this) -> showCamera()
            declaresCamera(this) -> permission.launch(Manifest.permission.CAMERA)
            else -> openSystemCamera()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        (systemPhoto?.absolutePath ?: savedPhotoPath)?.let { outState.putString(KEY_SYSTEM_PHOTO, it) }
    }

    private fun showCamera() {
        setContent {
            Themed {
                BackHandler { viewModel.cancel() }
                ScannerScreen(viewModel, request.maxPages, onClose = viewModel::cancel)
            }
        }
    }

    private fun openSystemCamera() {
        val (file, uri) = try {
            CaptureFiles.create(this, MediaKind.IMAGE)
        } catch (_: Exception) {
            return deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.OUTPUT_WRITE_FAILED)))
        }
        systemPhoto = file
        savedPhotoPath = file.absolutePath
        try {
            systemCamera.launch(uri)
        } catch (_: ActivityNotFoundException) {
            file.delete()
            deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.CAMERA_UNAVAILABLE)))
        }
    }

    @androidx.compose.runtime.Composable
    private fun Themed(content: @androidx.compose.runtime.Composable () -> Unit) {
        ProvideEditorLocale(request.ui.localeTag) { FrameKitTheme(request.ui, content) }
    }

    private fun deliver(result: FrameKitResult) {
        if (delivered) return
        delivered = true
        val (code, data) = FrameKitResultCodec.encode(result)
        setResult(code, data)
        finish()
    }

    private companion object {
        const val KEY_SYSTEM_PHOTO = "framekit_scan_system_photo"

        fun hasCamera(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        // 호스트 manifest에 CAMERA가 있으면 요청할 수 있다. SDK는 이 권한을 선언하지 않는다.
        fun declaresCamera(context: Context): Boolean = runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
            info.requestedPermissions?.contains(Manifest.permission.CAMERA) == true
        }.getOrDefault(false)
    }
}
