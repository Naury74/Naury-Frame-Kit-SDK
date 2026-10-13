package com.naury.framekit.picker

/** 앨범 종류. [ALL]·[VIDEOS]는 이름을 화면 문구로 보여 준다. */
internal enum class AlbumKind { ALL, VIDEOS, FOLDER }

/**
 * 갤러리 앨범 하나.
 *
 * @property id [ALL_ID], [VIDEOS_ID] 또는 MediaStore 폴더 id.
 * @property name 폴더 이름. [AlbumKind.FOLDER]가 아니면 `null`.
 * @property cover 가장 최근 항목. 비어 있으면 `null`.
 */
internal data class Album(val id: String, val kind: AlbumKind, val name: String?, val count: Int, val cover: MediaItem?) {
    fun contains(item: MediaItem): Boolean = when (kind) {
        AlbumKind.ALL -> true
        AlbumKind.VIDEOS -> item.isVideo
        AlbumKind.FOLDER -> item.bucketId == id
    }

    companion object {
        const val ALL_ID = "framekit:all"
        const val VIDEOS_ID = "framekit:videos"
    }
}

internal object Albums {

    /**
     * 최근 항목(전체), 동영상(사진과 영상을 함께 보여 줄 때만, 영상이 있을 때), 그리고 폴더별 앨범. 폴더는 항목이 많은
     * 순서이며, 같으면 이름 순서다. [items]는 최근 것이 앞에 오도록 정렬돼 있어야 한다.
     */
    fun of(items: List<MediaItem>, includeVideos: Boolean): List<Album> {
        val albums = ArrayList<Album>()
        albums += Album(Album.ALL_ID, AlbumKind.ALL, null, items.size, items.firstOrNull())
        if (includeVideos) {
            val videos = items.filter { it.isVideo }
            if (videos.isNotEmpty() && videos.size < items.size) albums += Album(Album.VIDEOS_ID, AlbumKind.VIDEOS, null, videos.size, videos.first())
        }
        items.filter { it.bucketId != null }
            .groupBy { it.bucketId!! }
            .map { (id, group) -> Album(id, AlbumKind.FOLDER, group.first().bucketName ?: id, group.size, group.first()) }
            .sortedWith(compareByDescending<Album> { it.count }.thenBy { it.name?.lowercase() })
            .let(albums::addAll)
        return albums
    }
}
