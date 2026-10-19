package com.naury.framekit.showcase

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.core.model.MediaType
import android.net.Uri
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import com.naury.kinetica.ui.KImageViewer
import com.naury.kinetica.ui.KImageViewerState
import com.naury.kinetica.ui.imageViewerSource
import com.naury.kinetica.ui.kClickable
import com.naury.kinetica.ui.rememberKImageViewerState
import com.naury.kinetica.ui.skeleton
import com.naury.kinetica.ui.staggerIn
import com.naury.kinetica.ui.KAlertDialog
import com.naury.kinetica.ui.KButton
import com.naury.kinetica.ui.KDialogAction
import com.naury.kinetica.ui.KDialogActionStyle
import com.naury.kinetica.ui.KTone
import com.naury.kinetica.ui.KVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** 편집 결과 목록. 사진·영상·PDF를 미리 보고 메타데이터를 확인하며 공유하거나 모두 지운다. */
@Composable
fun ResultScreen(outputs: List<EditedMedia>, elapsedMs: Long, onBack: () -> Unit, onDeleteAll: () -> Unit) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // 미리보기는 카드와 전체 화면 뷰어가 함께 쓰므로 화면에서 한 번만 읽는다.
    val previews = remember(outputs) { mutableStateMapOf<Uri, ImageBitmap>() }
    outputs.forEach { media ->
        if (!media.hasStillPreview()) return@forEach
        key(media.uri) {
            LaunchedEffect(media.uri) {
                withContext(Dispatchers.IO) { runCatching { loadPreview(context, media) }.getOrNull() }?.let { previews[media.uri] = it.asImageBitmap() }
            }
        }
    }
    // 뷰어는 정지 미리보기가 있는 결과만 넘겨 본다.
    val viewable = outputs.filter { it.hasStillPreview() }
    val viewer = rememberKImageViewerState()
    // 목록이 상단 바 아래로 지나가도록 바 높이와 내비게이션 바는 목록 여백으로 받는다.
    val navigationBar = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    BlurBarScaffold(stringResource(R.string.result_count, outputs.size, elapsedMs), listState, onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 4.dp, bottom = navigationBar + 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(outputs, key = { _, media -> media.uri.toString() }) { index, media ->
                val page = viewable.indexOf(media)
                ResultCard(media, preview = previews[media.uri], viewer = viewer, viewerPage = page, modifier = Modifier.staggerIn(index))
            }
            item {
                KButton(
                    stringResource(R.string.result_delete),
                    onClick = { confirmDelete = true },
                    modifier = Modifier.widthIn(max = 720.dp),
                    variant = KVariant.Outlined,
                    tone = KTone.Destructive,
                    fullWidth = true,
                )
            }
        }
    }
    // 핀치·두 번 탭으로 확대해 편집 결과를 자세히 본다.
    KImageViewer(
        viewer,
        pageCount = viewable.size,
        aspectRatio = { page -> previews[viewable[page].uri]?.let { it.width.toFloat() / it.height } },
    ) { page ->
        previews[viewable[page].uri]?.let { Image(it, contentDescription = stringResource(viewable[page].previewLabel()), modifier = Modifier.fillMaxSize()) }
    }
    // 지운 파일은 되돌릴 수 없으므로 한 번 더 묻는다. 버튼을 누르면 대화상자는 스스로 닫힌다.
    KAlertDialog(
        visible = confirmDelete,
        onDismissRequest = { confirmDelete = false },
        title = stringResource(R.string.result_delete_confirm),
        message = stringResource(R.string.result_delete_confirm_message),
        actions = listOf(
            KDialogAction(stringResource(R.string.result_delete_cancel), KDialogActionStyle.Cancel),
            KDialogAction(stringResource(R.string.result_delete), KDialogActionStyle.Destructive, onClick = onDeleteAll),
        ),
    )
}

@Composable
private fun ResultCard(media: EditedMedia, preview: ImageBitmap?, viewer: KImageViewerState, viewerPage: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
            // GIF·움직이는 WebP는 움직이는 채로 보여 준다(Android 9 이상).
            if (media.mediaType == MediaType.AUDIO) {
                AudioPreview(media)
            } else if (media.mediaType == MediaType.ANIMATED_IMAGE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                AnimatedPreview(media)
            } else if (preview == null) {
                Box(Modifier.fillMaxWidth().height(220.dp).skeleton(visible = true))
            } else {
                Image(
                    preview,
                    contentDescription = stringResource(media.previewLabel()),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .then(if (viewerPage >= 0) Modifier.imageViewerSource(viewer, viewerPage).kClickable { viewer.open(viewerPage) } else Modifier),
                )
            }
        }
        val sizeUnit = if (media.mediaType == MediaType.DOCUMENT) "pt" else "px"
        MetadataRow(stringResource(R.string.result_size), "${media.width} × ${media.height} $sizeUnit")
        media.pageCount?.let { MetadataRow(stringResource(R.string.result_pages), "$it") }
        media.durationMs?.let { MetadataRow(stringResource(R.string.result_duration), String.format(Locale.ROOT, "%.2f s", it / 1000.0)) }
        MetadataRow(stringResource(R.string.result_mime), media.mimeType)
        if (media.isOriginal) MetadataRow(stringResource(R.string.result_original), stringResource(R.string.result_original_yes))
        MetadataRow(stringResource(R.string.result_file_size), Formatter.formatShortFileSize(context, media.fileSize))
        MetadataRow(stringResource(R.string.result_warnings), media.warnings.joinToString("\n").ifEmpty { stringResource(R.string.result_none) })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KButton(stringResource(R.string.result_share), onClick = { share(context, media) }, variant = KVariant.Tonal)
        }
    }
}

/** 움직이는 이미지는 Android 9 이상에서 움직이는 채로 보여 주므로 정지 미리보기를 만들지 않는다. */
private fun EditedMedia.hasStillPreview(): Boolean =
    mediaType != MediaType.AUDIO && !(mediaType == MediaType.ANIMATED_IMAGE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)

private fun EditedMedia.previewLabel(): Int = when (mediaType) {
    MediaType.VIDEO -> R.string.result_video
    MediaType.DOCUMENT -> R.string.result_document
    MediaType.ANIMATED_IMAGE -> R.string.result_animated
    MediaType.AUDIO -> R.string.result_audio
    else -> R.string.result_image
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
@Composable
private fun AnimatedPreview(media: EditedMedia) {
    val context = LocalContext.current
    val drawable by produceState<android.graphics.drawable.Drawable?>(null, media.uri) {
        value = withContext(Dispatchers.IO) { runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, media.uri)) }.getOrNull() }
    }
    val current = drawable ?: return
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { android.widget.ImageView(it).apply { adjustViewBounds = true } },
        update = { view ->
            view.setImageDrawable(current)
            (current as? android.graphics.drawable.AnimatedImageDrawable)?.start()
        },
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
    )
}

/** 소리 결과를 바로 들어 볼 수 있는 재생 버튼. */
@Composable
private fun AudioPreview(media: EditedMedia) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    val player = remember(media.uri) { android.media.MediaPlayer() }
    androidx.compose.runtime.DisposableEffect(player) {
        runCatching {
            player.setDataSource(context, media.uri)
            player.setOnCompletionListener { playing = false }
            player.prepare()
        }
        onDispose { player.release() }
    }
    KButton(stringResource(if (playing) R.string.result_audio_pause else R.string.result_audio_play), onClick = {
        if (playing) player.pause() else player.start()
        playing = !playing
    })
}

@Composable
private fun MetadataRow(label: String, value: String) {
    // 값이 길면(경고 여러 개) 라벨을 밀어내지 않고 오른쪽 칸 안에서 줄을 바꾼다.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

private fun loadPreview(context: Context, media: EditedMedia): Bitmap? = when (media.mediaType) {
    // 영상은 첫 프레임, PDF는 첫 쪽을 미리보기로 쓴다.
    MediaType.VIDEO -> MediaMetadataRetriever().run {
        try {
            setDataSource(context, media.uri)
            getFrameAtTime(0)
        } finally {
            release()
        }
    }
    MediaType.DOCUMENT -> context.contentResolver.openFileDescriptor(media.uri, "r")?.use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(0).use { page ->
                val scale = PREVIEW_LONG_EDGE.toFloat() / maxOf(page.width, page.height)
                createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1)).also { bitmap ->
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }
    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, media.uri)) { decoder, info, _ ->
            val longEdge = maxOf(info.size.width, info.size.height)
            if (longEdge > PREVIEW_LONG_EDGE) decoder.setTargetSampleSize(longEdge / PREVIEW_LONG_EDGE)
        }
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, media.uri)
    }
}

// 다른 앱이 결과를 읽을 수 있도록 Intent와 ClipData 모두에 읽기 권한을 붙인다.
private fun share(context: Context, media: EditedMedia) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = media.mimeType
        putExtra(Intent.EXTRA_STREAM, media.uri)
        clipData = ClipData.newRawUri(null, media.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}

private const val PREVIEW_LONG_EDGE = 1600
