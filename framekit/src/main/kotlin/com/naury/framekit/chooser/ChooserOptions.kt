package com.naury.framekit.chooser

import com.naury.framekit.EditorAction

/** 넘겨받은 원본 하나의 종류. */
internal enum class SourceKind { IMAGE, ANIMATED, VIDEO, AUDIO }

/**
 * 원본 종류에 맞는 선택지. [mixed]면 사진과 영상이 섞여 함께 편집할 수 없다는 안내를 보여 준다.
 */
internal data class ChooserOptions(val actions: List<EditorAction>, val mixed: Boolean, val singleAnimated: Boolean)

internal object ChooserRules {

    /** [kinds]에 맞는 동작 중 [allowed]에 있는 것을 보여 줄 순서대로 고른다. */
    fun options(kinds: List<SourceKind>, allowed: Set<EditorAction>): ChooserOptions {
        val hasVideo = SourceKind.VIDEO in kinds
        val hasAudio = SourceKind.AUDIO in kinds
        val hasImage = kinds.any { it == SourceKind.IMAGE || it == SourceKind.ANIMATED }
        val singleAnimated = kinds.size == 1 && kinds.single() == SourceKind.ANIMATED
        val mixed = listOf(hasVideo, hasAudio, hasImage).count { it } > 1
        val candidates = when {
            mixed -> listOf(EditorAction.SEND_ORIGINAL)
            hasAudio -> listOf(EditorAction.EDIT_AUDIO, EditorAction.SEND_ORIGINAL)
            hasVideo -> listOf(EditorAction.EDIT_VIDEO, EditorAction.SEND_ORIGINAL)
            singleAnimated -> listOf(EditorAction.MOTION, EditorAction.EDIT_PHOTO, EditorAction.SEND_ORIGINAL)
            else -> listOf(EditorAction.EDIT_PHOTO, EditorAction.MAKE_PDF, EditorAction.MOTION, EditorAction.SEND_ORIGINAL)
        }
        return ChooserOptions(candidates.filter { it in allowed }, mixed = mixed, singleAnimated = singleAnimated)
    }
}
