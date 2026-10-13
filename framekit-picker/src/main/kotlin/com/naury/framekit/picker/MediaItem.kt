package com.naury.framekit.picker

import android.net.Uri
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.ui.picker.MediaPickerRequest
import com.naury.framekit.ui.picker.PickedMedia

/**
 * 갤러리에 보이는 사진·영상 하나. MediaStore 한 줄을 옮겨 담는다.
 *
 * @property sizeBytes 파일 크기(byte). 모르면 0.
 * @property width 저장된 가로(px). 모르면 0. 회전 정보는 [orientation].
 * @property durationMs 영상 길이(ms). 사진이거나 모르면 0.
 * @property bucketId 들어 있는 폴더(앨범) id. 모르면 `null`.
 * @property orientation 사진 회전(도, 0·90·180·270).
 */
internal data class MediaItem(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val mimeType: String?,
    val sizeBytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val bucketId: String? = null,
    val bucketName: String? = null,
    val dateAdded: Long = 0,
    val orientation: Int = 0,
) {
    /** 돌려줄 결과. 크기는 회전을 반영한 똑바로 선 크기다. */
    fun toPicked(): PickedMedia {
        val turned = orientation % 180 != 0
        val w = (if (turned) height else width).takeIf { it > 0 }
        val h = (if (turned) width else height).takeIf { it > 0 }
        return PickedMedia(uri, mimeType, sizeBytes.takeIf { it > 0 }, w, h, durationMs.takeIf { isVideo && it > 0 })
    }
}

/** 고를 수 없는 이유. 갤러리에는 흐리게 보이고, 누르면 이유를 알려 준다. */
internal enum class Block { TOO_LARGE, TOO_LONG }

/** 요청한 종류·형식이면 `true`. 맞지 않는 것은 갤러리에 아예 보이지 않는다. */
internal fun MediaPickerRequest.shows(item: MediaItem): Boolean {
    val kindMatches = when (kind) {
        MediaKind.IMAGE -> !item.isVideo
        MediaKind.VIDEO -> item.isVideo
        MediaKind.ANY, MediaKind.AUDIO -> true
    }
    return kindMatches && accepts(item.mimeType)
}

/** 크기·길이 제한에 걸리면 그 이유. 크기·길이를 모르면 막지 않는다. */
internal fun MediaPickerRequest.blockOf(sizeBytes: Long?, durationMs: Long?, isVideo: Boolean): Block? {
    val maxSize = maxFileSizeBytes
    val maxDuration = maxVideoDurationMs
    return when {
        maxSize != null && sizeBytes != null && sizeBytes > maxSize -> Block.TOO_LARGE
        isVideo && maxDuration != null && durationMs != null && durationMs > maxDuration -> Block.TOO_LONG
        else -> null
    }
}

internal fun MediaPickerRequest.blockOf(item: MediaItem): Block? =
    blockOf(item.sizeBytes.takeIf { it > 0 }, item.durationMs.takeIf { it > 0 }, item.isVideo)

/** 선택을 바꾼 결과. */
internal sealed interface Toggle {
    data class Changed(val selection: List<Long>) : Toggle
    data object LimitReached : Toggle
    data class Blocked(val block: Block) : Toggle
}

/**
 * [item]을 고르거나 뺀다. 이미 골랐으면 빼고 뒤의 순서를 당긴다. 최대 개수에 닿았으면 [Toggle.LimitReached]를,
 * 제한에 걸리면 [Toggle.Blocked]를 돌려준다. 하나만 고르는 요청이면 고른 것을 바꾼다.
 */
internal fun MediaPickerRequest.toggle(selection: List<Long>, item: MediaItem): Toggle {
    if (item.id in selection) return Toggle.Changed(selection - item.id)
    blockOf(item)?.let { return Toggle.Blocked(it) }
    if (maxItems == 1) return Toggle.Changed(listOf(item.id))
    if (selection.size >= maxItems) return Toggle.LimitReached
    return Toggle.Changed(selection + item.id)
}
