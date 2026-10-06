package com.naury.framekit.android.session

import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Values that identify the exact image a session was editing.
 *
 * After process death the source is opened again and compared with this fingerprint. A different
 * image behind the same reference is never treated as the edited one.
 *
 * @property encodedWidth stored pixel width before orientation.
 * @property encodedHeight stored pixel height before orientation.
 * @property orientation EXIF orientation tag value.
 */
@Serializable
public data class SourceFingerprint(
    val mimeType: String,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val orientation: Int,
)

/** Reference that can reopen a source after recreation. Only strings are stored. */
@Serializable
public sealed interface SourceReference {
    @Serializable
    @SerialName("content")
    public data class Content(val uri: String) : SourceReference

    @Serializable
    @SerialName("file")
    public data class LocalFile(val absolutePath: String) : SourceReference
}

/**
 * Committed image edits as stored on disk. Drafts of an open tool are never written.
 *
 * The source key is not stored because a [SourceId] is only valid for one registry; the restored
 * project gets the key of the newly registered source.
 */
@Serializable
public data class ImageProjectSnapshot(
    val projectId: String,
    val revision: Long,
    val quarterTurns: Int,
    val straightenDegrees: Double,
    val flipX: Boolean,
    val flipY: Boolean,
    val cropLeft: Double,
    val cropTop: Double,
    val cropRight: Double,
    val cropBottom: Double,
) {
    public fun toProject(source: SourceId): ImageProject = ImageProject(
        id = ProjectId(projectId),
        source = source,
        geometry = GeometryEdit(
            quarterTurns = quarterTurns,
            straightenDegrees = straightenDegrees,
            flipX = flipX,
            flipY = flipY,
            crop = RectN(cropLeft, cropTop, cropRight, cropBottom),
        ),
        revision = revision,
    )

    public companion object {
        public fun of(project: ImageProject): ImageProjectSnapshot = with(project.geometry) {
            ImageProjectSnapshot(
                projectId = project.id.value,
                revision = project.revision,
                quarterTurns = quarterTurns,
                straightenDegrees = straightenDegrees,
                flipX = flipX,
                flipY = flipY,
                cropLeft = crop.left,
                cropTop = crop.top,
                cropRight = crop.right,
                cropBottom = crop.bottom,
            )
        }
    }
}

@Serializable
internal data class SessionDescriptor(
    val schemaVersion: Int,
    val sessionId: String,
    val source: SourceReference,
    val fingerprint: SourceFingerprint,
    val persistedGrant: Boolean,
    val createdAt: Long,
)

@Serializable
internal data class SessionSnapshotFile(
    val schemaVersion: Int,
    val updatedAt: Long,
    val exportInProgress: Boolean,
    val image: ImageProjectSnapshot?,
)

/**
 * Everything restored for a session.
 *
 * @property snapshot last committed edits, or `null` when nothing was committed yet.
 * @property exportWasInterrupted `true` when the process died while an export was running. The
 *   export did not complete and must be started again.
 */
public data class SessionRecord(
    val sessionId: String,
    val source: SourceReference,
    val fingerprint: SourceFingerprint,
    val snapshot: ImageProjectSnapshot?,
    val exportWasInterrupted: Boolean,
)
