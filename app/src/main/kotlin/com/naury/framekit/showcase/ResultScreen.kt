package com.naury.framekit.showcase

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.naury.framekit.android.result.EditedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ResultScreen(media: EditedMedia, elapsedMs: Long, onBack: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val preview by produceState<Bitmap?>(null, media.uri) {
        value = withContext(Dispatchers.IO) { runCatching { loadPreview(context, media) }.getOrNull() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        ResultContent(media, elapsedMs, preview, onBack, onDelete)
    }
}

@Composable
private fun ResultContent(
    media: EditedMedia,
    elapsedMs: Long,
    preview: Bitmap?,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.result_back)) }
        Box(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            preview?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = stringResource(R.string.result_image),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        MetadataRow(stringResource(R.string.result_size), "${media.width} × ${media.height}")
        MetadataRow(stringResource(R.string.result_mime), media.mimeType)
        MetadataRow(stringResource(R.string.result_file_size), Formatter.formatShortFileSize(context, media.fileSize))
        MetadataRow(stringResource(R.string.result_elapsed), "$elapsedMs ms")
        MetadataRow(
            stringResource(R.string.result_warnings),
            media.warnings.joinToString().ifEmpty { stringResource(R.string.result_none) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { share(context, media) }) { Text(stringResource(R.string.result_share)) }
            OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.result_delete)) }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onBackground)
    }
}

private fun loadPreview(context: Context, media: EditedMedia): Bitmap =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, media.uri)) { decoder, info, _ ->
            val longEdge = maxOf(info.size.width, info.size.height)
            if (longEdge > PREVIEW_LONG_EDGE) decoder.setTargetSampleSize(longEdge / PREVIEW_LONG_EDGE)
        }
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, media.uri)
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
