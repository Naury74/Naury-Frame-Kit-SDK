package com.naury.framekit.scanner

import android.content.Context
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.core.document.DocumentQuad
import com.naury.framekit.core.document.ScanMode
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.image.decode.BitmapDecoder
import com.naury.framekit.image.decode.ImageMetadataReader
import com.naury.framekit.image.decode.SampleSize
import com.naury.framekit.image.document.DocumentRectifier
import java.io.File

/**
 * 찍은 사진 한 장을 반듯한 문서 쪽으로 만든다.
 *
 * 찍은 사진에서 문서를 다시 찾고(카메라 미리보기보다 해상도가 높아 더 정확하다), 찾지 못하면 미리보기에서 찾은 [liveQuad]를,
 * 그것도 없으면 사진 전체를 쓴다. 휘거나 접힌 종이는 테두리 곡선을 따라 편다. 결과는 앱 내부 저장소에 JPEG로 쓴다.
 */
internal class PageMaker(context: Context) {

    private val appContext = context.applicationContext
    private val registry = SessionSourceRegistry(appContext)
    private val store = AppFileOutputStore(appContext)
    private val memoryBudget = Runtime.getRuntime().maxMemory() / 3

    /**
     * @throws com.naury.framekit.android.result.FrameKitException 읽거나 쓰지 못했을 때.
     */
    fun make(photo: File, liveQuad: DocumentQuad?, mode: ScanMode): EditedMedia {
        val sourceId = registry.register(EditorInput.FileSource(photo.absolutePath))
        val info = ImageMetadataReader(registry).read(sourceId)
        val preview = BitmapDecoder(registry, appContext.contentResolver).decode(info, SampleSize.forMinimumLongEdge(info.encodedSize, DETECT_LONG_EDGE))
        val detected = try {
            DocumentRectifier.analyze(preview.bitmap, insideOnly = true)
        } finally {
            preview.recycle()
        }
        val quad = detected.quad ?: liveQuad?.takeIf { it.isUsable } ?: DocumentQuad.inset(0.0)
        val partial = store.createPartial(EXTENSION)
        try {
            // 종이가 휘거나 접혔으면 찾은 테두리 곡선을 따라 편다.
            val size = DocumentRectifier(registry, appContext.contentResolver, memoryBudget).rectify(info, quad, partial, mode, outline = detected.outline)
            val published = store.publish(partial, EXTENSION)
            return EditedMedia(
                uri = store.uriFor(published),
                mediaType = MediaType.IMAGE,
                width = size.width,
                height = size.height,
                durationMs = null,
                mimeType = MIME_TYPE,
                fileSize = published.length(),
            )
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    /** 다시 찍기 등으로 버린 쪽을 지운다. */
    fun discard(page: EditedMedia) {
        store.delete(page.uri)
    }

    private companion object {
        // 찍은 사진에서 문서를 찾을 때 읽는 긴 변(px). 감지는 256px로 줄여 하므로 이 정도면 충분하다.
        const val DETECT_LONG_EDGE = 1024
        const val EXTENSION = "jpg"
        const val MIME_TYPE = "image/jpeg"
    }
}
