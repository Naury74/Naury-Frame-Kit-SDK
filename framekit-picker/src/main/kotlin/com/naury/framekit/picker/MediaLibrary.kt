package com.naury.framekit.picker

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.provider.MediaStore
import com.naury.framekit.android.input.MediaKind

/** 기기의 사진·영상 목록을 읽는다. 테스트에서는 가짜로 바꾼다. */
internal fun interface MediaLibrary {

    /**
     * [kind]에 맞는 항목을 최근 것부터 읽어 [chunk]마다 [onChunk]로 넘긴다. 첫 묶음은 작게 넘겨 화면이 빨리 채워지게 한다.
     * 읽을 수 없으면 아무것도 넘기지 않는다.
     */
    fun load(kind: MediaKind, onChunk: (List<MediaItem>) -> Unit)
}

/** MediaStore에서 읽는 [MediaLibrary]. 한 번의 쿼리로 필요한 열만 읽는다. */
internal class MediaStoreLibrary(private val resolver: ContentResolver) : MediaLibrary {

    override fun load(kind: MediaKind, onChunk: (List<MediaItem>) -> Unit) {
        val types = when (kind) {
            MediaKind.IMAGE -> "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}"
            MediaKind.VIDEO -> "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
            MediaKind.ANY, MediaKind.AUDIO -> "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
        }
        val cursor = try {
            resolver.query(
                MediaStore.Files.getContentUri("external"),
                PROJECTION,
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN ($types) AND ${MediaStore.MediaColumns.SIZE} > 0",
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC",
            )
        } catch (_: SecurityException) {
            null
        } ?: return
        cursor.use { read(it, onChunk) }
    }

    private fun read(cursor: Cursor, onChunk: (List<MediaItem>) -> Unit) {
        val id = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
        val type = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val mime = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
        val size = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
        val width = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
        val height = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
        val duration = cursor.getColumnIndex(COLUMN_DURATION)
        val bucket = cursor.getColumnIndex(COLUMN_BUCKET_ID)
        val bucketName = cursor.getColumnIndex(COLUMN_BUCKET_NAME)
        val added = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
        val orientation = cursor.getColumnIndex(COLUMN_ORIENTATION)
        fun Cursor.long(index: Int) = if (index >= 0 && !isNull(index)) getLong(index) else 0L
        fun Cursor.int(index: Int) = if (index >= 0 && !isNull(index)) getInt(index) else 0
        fun Cursor.string(index: Int) = if (index >= 0 && !isNull(index)) getString(index) else null
        var batch = ArrayList<MediaItem>(FIRST_CHUNK)
        var limit = FIRST_CHUNK
        while (cursor.moveToNext()) {
            val itemId = cursor.long(id)
            val video = cursor.int(type) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            batch += MediaItem(
                id = itemId,
                uri = ContentUris.withAppendedId(base, itemId),
                isVideo = video,
                mimeType = cursor.string(mime),
                sizeBytes = cursor.long(size),
                width = cursor.int(width),
                height = cursor.int(height),
                durationMs = if (video) cursor.long(duration) else 0L,
                bucketId = cursor.string(bucket),
                bucketName = cursor.string(bucketName),
                dateAdded = cursor.long(added),
                orientation = if (video) 0 else cursor.int(orientation),
            )
            if (batch.size >= limit) {
                onChunk(batch)
                batch = ArrayList(CHUNK)
                limit = CHUNK
            }
        }
        if (batch.isNotEmpty()) onChunk(batch)
    }

    private companion object {
        // Files 표에서 영상·사진 열을 함께 읽는다. 일부 열은 상수가 API 29 이상에만 있어 이름으로 쓴다.
        const val COLUMN_DURATION = "duration"
        const val COLUMN_BUCKET_ID = "bucket_id"
        const val COLUMN_BUCKET_NAME = "bucket_display_name"
        const val COLUMN_ORIENTATION = "orientation"
        val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            COLUMN_DURATION,
            COLUMN_BUCKET_ID,
            COLUMN_BUCKET_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            COLUMN_ORIENTATION,
        )
        const val FIRST_CHUNK = 120
        const val CHUNK = 1000
    }
}
