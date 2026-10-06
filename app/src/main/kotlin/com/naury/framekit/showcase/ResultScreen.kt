package com.naury.framekit.showcase

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.core.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** 편집 결과 목록. 사진·영상·PDF를 미리 보고 메타데이터를 확인하며 공유하거나 모두 지운다. */
@Composable
fun ResultScreen(outputs: List<EditedMedia>, elapsedMs: Long, onBack: () -> Unit, onDeleteAll: () -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.result_back)) }
                Text(
                    stringResource(R.string.result_count, outputs.size, elapsedMs),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        items(outputs, key = { it.uri.toString() }) { media -> ResultCard(media) }
        item {
            OutlinedButton(onClick = onDeleteAll, modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                Text(stringResource(R.string.result_delete))
            }
        }
    }
}

@Composable
private fun ResultCard(media: EditedMedia) {
    val context = LocalContext.current
    val preview by produceState<Bitmap?>(null, media.uri) {
        value = withContext(Dispatchers.IO) { runCatching { loadPreview(context, media) }.getOrNull() }
    }
    Column(
        Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
            preview?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = stringResource(
                        when (media.mediaType) {
                            MediaType.VIDEO -> R.string.result_video
                            MediaType.DOCUMENT -> R.string.result_document
                            else -> R.string.result_image
                        },
                    ),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                )
            }
        }
        val sizeUnit = if (media.mediaType == MediaType.DOCUMENT) "pt" else "px"
        MetadataRow(stringResource(R.string.result_size), "${media.width} × ${media.height} $sizeUnit")
        media.pageCount?.let { MetadataRow(stringResource(R.string.result_pages), "$it") }
        media.durationMs?.let { MetadataRow(stringResource(R.string.result_duration), String.format(Locale.ROOT, "%.2f s", it / 1000.0)) }
        MetadataRow(stringResource(R.string.result_mime), media.mimeType)
        MetadataRow(stringResource(R.string.result_file_size), Formatter.formatShortFileSize(context, media.fileSize))
        MetadataRow(stringResource(R.string.result_warnings), media.warnings.joinToString().ifEmpty { stringResource(R.string.result_none) })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { share(context, media) }) { Text(stringResource(R.string.result_share)) }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurface)
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
