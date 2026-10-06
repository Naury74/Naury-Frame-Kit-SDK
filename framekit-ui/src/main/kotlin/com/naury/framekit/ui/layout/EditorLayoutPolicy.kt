package com.naury.framekit.ui.layout

/** Posture of a foldable device, with the hinge position in window pixels. */
public sealed interface FoldPosture {
    /** Not a foldable, fully open or fully closed. */
    public data object Flat : FoldPosture

    /** Half-opened with a horizontal hinge, like a laptop: content above, controls below. */
    public data class Tabletop(val hingeTopPx: Int, val hingeBottomPx: Int) : FoldPosture

    /** Half-opened with a vertical hinge, like a book: content left, controls right. */
    public data class Book(val hingeLeftPx: Int, val hingeRightPx: Int) : FoldPosture
}

/** Arrangement of the canvas and the tool controls. */
public sealed interface EditorLayout {
    /** Canvas on top, controls below. [maxControlsWidthDp] limits control width on medium screens. */
    public data class Stacked(val maxControlsWidthDp: Int?) : EditorLayout

    /** Canvas on the left, controls in a side panel of [panelWidthDp]. */
    public data class SidePanel(val panelWidthDp: Int) : EditorLayout

    /** Canvas above the horizontal hinge, controls below it. */
    public data class SplitAtHorizontalHinge(val hingeTopPx: Int, val hingeBottomPx: Int) : EditorLayout

    /** Canvas left of the vertical hinge, controls right of it. */
    public data class SplitAtVerticalHinge(val hingeLeftPx: Int, val hingeRightPx: Int) : EditorLayout
}

/**
 * Chooses the editor layout from the window size and fold posture.
 *
 * The project and its normalized coordinates never depend on the layout, so switching layouts while
 * folding, unfolding or resizing keeps every edit in place.
 */
public object EditorLayoutPolicy {
    public const val EXPANDED_WIDTH_DP: Int = 840
    public const val MEDIUM_WIDTH_DP: Int = 600
    public const val SIDE_PANEL_WIDTH_DP: Int = 360
    public const val MAX_CONTROLS_WIDTH_DP: Int = 640

    public fun decide(widthDp: Float, heightDp: Float, posture: FoldPosture): EditorLayout = when {
        posture is FoldPosture.Tabletop -> EditorLayout.SplitAtHorizontalHinge(posture.hingeTopPx, posture.hingeBottomPx)
        posture is FoldPosture.Book -> EditorLayout.SplitAtVerticalHinge(posture.hingeLeftPx, posture.hingeRightPx)
        widthDp >= EXPANDED_WIDTH_DP -> EditorLayout.SidePanel(SIDE_PANEL_WIDTH_DP)
        // 가로로 눕힌 폰은 높이가 낮아 아래 패널이 캔버스를 너무 가린다.
        widthDp > heightDp && widthDp >= MEDIUM_WIDTH_DP -> EditorLayout.SidePanel(SIDE_PANEL_WIDTH_DP)
        widthDp >= MEDIUM_WIDTH_DP -> EditorLayout.Stacked(MAX_CONTROLS_WIDTH_DP)
        else -> EditorLayout.Stacked(maxControlsWidthDp = null)
    }
}
