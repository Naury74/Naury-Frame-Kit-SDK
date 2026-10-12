package com.naury.framekit.ui.motion.editor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.naury.framekit.android.input.EditorInput
import java.io.File

/** 소리 파일의 이름과 길이를 읽는다. 디스크를 읽으므로 메인 스레드에서 부르지 않는다. */
internal object MotionAudioSources {

    /** 읽을 수 없거나 소리가 아니면 `null`. */
    fun describe(context: Context, input: EditorInput): MotionAudioSource? {
        val uri = when (input) {
            is EditorInput.UriSource -> input.uri
            is EditorInput.FileSource -> Uri.fromFile(File(input.absolutePath))
            else -> return null
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes") return null
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            MotionAudioSource(nameOf(context, input), duration)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    // 녹음 파일은 이름을 보여 주지 않고 기본 이름("소리")을 쓴다.
    private fun nameOf(context: Context, input: EditorInput): String? = when (input) {
        is EditorInput.UriSource -> runCatching {
            context.contentResolver.query(input.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.substringBeforeLast('.') else null
            }
        }.getOrNull()
        else -> null
    }
}
