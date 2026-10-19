package com.naury.framekit.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import com.naury.kinetica.ui.KProgressRing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.framekit.android.capture.CaptureFiles
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.core.document.DocumentQuad
import com.naury.framekit.image.document.DocumentRectifier
import com.naury.framekit.ui.component.FrameKitNavigationBar
import com.naury.framekit.ui.component.FrameKitSnackbarHost
import com.naury.framekit.ui.component.NavigationIcon
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.design.frameKitGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * 카메라 문서 스캔 화면. 찾은 문서 테두리를 미리보기에 겹쳐 보이고, 찍은 쪽·셔터·자동 촬영·플래시를 다룬다.
 */
@Composable
internal fun ScannerScreen(viewModel: ScannerViewModel, maxPages: Int, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = FrameKitTheme.colors
    val snackbar = remember { SnackbarHostState() }
    val messageText = state.message?.let { message ->
        when (message) {
            ScannerMessage.FAILED -> stringResource(R.string.framekit_scan_failed)
            ScannerMessage.LIMIT -> pluralStringResource(R.plurals.framekit_scan_limit, maxPages, maxPages)
            ScannerMessage.CAMERA_UNAVAILABLE -> stringResource(R.string.framekit_scan_camera_error)
        }
    }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.dismissMessage()
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            FrameKitNavigationBar(
                title = stringResource(R.string.framekit_scan_title),
                navigationIcon = NavigationIcon.CLOSE,
                onNavigate = onClose,
                actionLabel = if (state.pages.isNotEmpty()) stringResource(R.string.framekit_scan_done_count, state.pages.size) else null,
                actionEnabled = !state.busy,
                onAction = viewModel::finish,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CameraLayer(
                    torch = state.torch,
                    captureRequests = viewModel.captureRequests,
                    onFrame = viewModel::onFrame,
                    onPhoto = viewModel::onPhoto,
                    onPhotoFailed = viewModel::onPhotoFailed,
                    onUnavailable = viewModel::onCameraUnavailable,
                    modifier = Modifier.fillMaxSize(),
                )
                QuadOverlay(state.quad, state.frameAspect, state.progress, Modifier.fillMaxSize())
                Hint(state, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                ShutterFlash(state.shots)
            }
            Controls(state, viewModel)
        }
        FrameKitSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 140.dp))
        if (state.busy) {
            Box(Modifier.align(Alignment.Center).frameKitGlass(RoundedCornerShape(18.dp)).padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KProgressRing(progress = null, modifier = Modifier.size(18.dp), thickness = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.framekit_scan_working), color = colors.foreground, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun Hint(state: ScannerUiState, modifier: Modifier) {
    val text = when {
        state.quad == null -> stringResource(R.string.framekit_scan_hint_find)
        state.autoCapture -> stringResource(R.string.framekit_scan_hint_hold)
        else -> stringResource(R.string.framekit_scan_hint_manual)
    }
    Text(
        text,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 미리보기와 같은 영역(가운데 맞춤)에 문서 테두리를 그린다. [progress]는 자동 촬영까지의 진행이다. */
@Composable
private fun QuadOverlay(quad: DocumentQuad?, aspect: Float, progress: Float, modifier: Modifier) {
    val accent = FrameKitTheme.colors.accent
    Canvas(modifier) {
        val q = quad ?: return@Canvas
        val content = fitRect(size.width, size.height, aspect)
        fun p(x: Double, y: Double) = Offset(content.left + (x * content.width).toFloat(), content.top + (y * content.height).toFloat())
        val corners = q.corners.map { p(it.x, it.y) }
        val path = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            corners.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path, accent.copy(alpha = 0.16f + 0.24f * progress))
        drawPath(path, accent, style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
        corners.forEach { drawCircle(Color.White, 6.dp.toPx(), it); drawCircle(accent, 4.dp.toPx(), it) }
    }
}

// 가로/세로 비가 [aspect]인 내용을 (w, h) 안에 가운데 맞춤으로 넣었을 때의 영역.
private fun fitRect(w: Float, h: Float, aspect: Float): Rect = if (w / h > aspect) {
    val cw = h * aspect
    Rect((w - cw) / 2f, 0f, (w + cw) / 2f, h)
} else {
    val ch = w / aspect
    Rect(0f, (h - ch) / 2f, w, (h + ch) / 2f)
}

@Composable
private fun ShutterFlash(shots: Int) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(shots) {
        if (shots == 0) return@LaunchedEffect
        alpha.snapTo(0.7f)
        alpha.animateTo(0f, tween(260))
    }
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }.background(Color.White))
}

@Composable
private fun Controls(state: ScannerUiState, viewModel: ScannerViewModel) {
    val colors = FrameKitTheme.colors
    Box(Modifier.fillMaxWidth().height(CONTROLS_HEIGHT).background(Color.Black).padding(horizontal = 24.dp)) {
        PagesStack(state.pages, onUndo = viewModel::removeLast, modifier = Modifier.align(Alignment.CenterStart))
        Shutter(state.progress, enabled = !state.busy, onClick = viewModel::shutter, modifier = Modifier.align(Alignment.Center))
        Column(Modifier.align(Alignment.CenterEnd), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ToggleChip(stringResource(R.string.framekit_scan_auto), state.autoCapture, colors.accent, viewModel::toggleAuto)
            ToggleChip(stringResource(R.string.framekit_scan_torch), state.torch, Color(0xFFFFC94D), viewModel::toggleTorch)
        }
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, tint: Color, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) Color.Black else Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape)
            .background(if (on) tint else Color.White.copy(alpha = 0.14f))
            .clickable(role = Role.Switch, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** 셔터 버튼. [progress]는 자동 촬영까지의 진행이다. */
@Composable
private fun Shutter(progress: Float, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val accent = FrameKitTheme.colors.accent
    val label = stringResource(R.string.framekit_scan_shutter)
    Box(
        modifier.size(78.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.35f), size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
            if (progress > 0f) drawArc(accent, -90f, 360f * progress, false, style = Stroke(stroke, cap = StrokeCap.Round), topLeft = Offset(stroke / 2, stroke / 2), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke))
        }
        Box(Modifier.size(62.dp).clip(CircleShape).background(if (enabled) Color.White else Color.White.copy(alpha = 0.4f)))
    }
}

/** 찍은 쪽 수와 마지막 쪽. 누르면 마지막 쪽을 지우고 다시 찍는다. */
@Composable
private fun PagesStack(pages: List<EditedMedia>, onUndo: () -> Unit, modifier: Modifier) {
    val last = pages.lastOrNull() ?: return Spacer(modifier.size(56.dp))
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(null, last.uri) { value = withContext(Dispatchers.IO) { loadThumbnail(context, last) } }
    val label = pluralStringResource(R.plurals.framekit_scan_pages_cd, pages.size, pages.size)
    Box(modifier.size(60.dp).semantics { contentDescription = label }) {
        Box(
            Modifier.size(52.dp).align(Alignment.BottomStart).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.14f))
                .border(1.5.dp, Color.White, RoundedCornerShape(10.dp)).clickable(onClick = onUndo),
        ) {
            thumbnail?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 2.dp).size(22.dp).clip(CircleShape).background(FrameKitTheme.colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("${pages.size}", color = FrameKitTheme.colors.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun loadThumbnail(context: Context, page: EditedMedia): Bitmap? = runCatching {
    context.contentResolver.openInputStream(page.uri)?.use { stream ->
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = max(1, max(page.width, page.height) / THUMBNAIL_PX) }
        android.graphics.BitmapFactory.decodeStream(stream, null, options)
    }
}.getOrNull()

/**
 * CameraX 미리보기·분석·촬영을 묶는다. 분석 프레임은 줄여서 문서를 찾고([onFrame]), [captureRequests]가 오면 고해상도 사진을
 * 찍어 [onPhoto]로 넘긴다. 미리보기·분석·촬영 모두 4:3으로 맞춰, 분석 좌표를 미리보기 위에 그대로 그릴 수 있게 한다.
 */
@Composable
private fun CameraLayer(
    torch: Boolean,
    captureRequests: Flow<Unit>,
    onFrame: (DocumentQuad?, Float, Long) -> Unit,
    onPhoto: (File) -> Unit,
    onPhotoFailed: () -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val frame by rememberUpdatedState(onFrame)
    val photo by rememberUpdatedState(onPhoto)
    val failed by rememberUpdatedState(onPhotoFailed)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val selector = remember { ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build() }
    val imageCapture = remember {
        ImageCapture.Builder().setResolutionSelector(selector).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setJpegQuality(JPEG_QUALITY).build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var lastAnalyzed = 0L
        future.addListener({
            try {
                val cameraProvider = future.get().also { provider = it }
                val preview = Preview.Builder().setResolutionSelector(selector).build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysisSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    val now = System.currentTimeMillis()
                    // 문서 감지는 초당 몇 번이면 충분하다. 배터리와 발열을 줄이려 간격을 둔다.
                    if (now - lastAnalyzed >= ANALYSIS_INTERVAL_MS) {
                        lastAnalyzed = now
                        val (quad, aspect) = analyze(proxy)
                        frame(quad, aspect, now)
                    }
                    proxy.close()
                }
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, imageCapture)
            } catch (_: Exception) {
                onUnavailable()
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }
    LaunchedEffect(captureRequests) {
        captureRequests.collect {
            val file = try {
                CaptureFiles.create(context, MediaKind.IMAGE).first
            } catch (_: Exception) {
                failed()
                return@collect
            }
            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) = photo(file)
                    override fun onError(exception: ImageCaptureException) {
                        file.delete()
                        failed()
                    }
                },
            )
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

// 분석 프레임을 똑바로 세워 작게 줄인 뒤 문서를 찾는다. 찾은 모서리와 똑바로 선 프레임의 가로/세로 비를 돌려준다.
private fun analyze(proxy: ImageProxy): Pair<DocumentQuad?, Float> = try {
    val source = proxy.toBitmap()
    val rotation = proxy.imageInfo.rotationDegrees
    val scale = DETECT_LONG_EDGE.toFloat() / max(source.width, source.height)
    val matrix = Matrix().apply {
        postScale(scale, scale)
        postRotate(rotation.toFloat())
    }
    val upright = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (upright !== source) source.recycle()
    try {
        // 문서 전체가 화면 안에 들어왔을 때만 테두리를 보이고 찍는다.
        DocumentRectifier.detect(upright, insideOnly = true) to upright.width.toFloat() / upright.height
    } finally {
        upright.recycle()
    }
} catch (_: Exception) {
    null to 3f / 4f
}

private val CONTROLS_HEIGHT = 132.dp
private const val THUMBNAIL_PX = 160
private const val JPEG_QUALITY = 95
private const val ANALYSIS_WIDTH = 640
private const val ANALYSIS_HEIGHT = 480
private const val ANALYSIS_INTERVAL_MS = 120L
private const val DETECT_LONG_EDGE = 256
