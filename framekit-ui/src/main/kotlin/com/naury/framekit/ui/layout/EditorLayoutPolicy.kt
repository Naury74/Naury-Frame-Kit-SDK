package com.naury.framekit.ui.layout

/** 폴더블 기기의 자세와 창 픽셀 단위 힌지 위치. */
public sealed interface FoldPosture {
    /** 폴더블이 아니거나, 완전히 펼쳤거나 완전히 접은 상태. */
    public data object Flat : FoldPosture

    /** 노트북처럼 가로 힌지로 반쯤 펼친 상태. 콘텐츠는 위, 컨트롤은 아래. */
    public data class Tabletop(val hingeTopPx: Int, val hingeBottomPx: Int) : FoldPosture

    /** 책처럼 세로 힌지로 반쯤 펼친 상태. 콘텐츠는 왼쪽, 컨트롤은 오른쪽. */
    public data class Book(val hingeLeftPx: Int, val hingeRightPx: Int) : FoldPosture
}

/** 캔버스와 도구 컨트롤의 배치. */
public sealed interface EditorLayout {
    /** 캔버스는 위, 컨트롤은 아래. 중간 크기 화면에서는 [maxControlsWidthDp]로 컨트롤 폭을 제한한다. */
    public data class Stacked(val maxControlsWidthDp: Int?) : EditorLayout

    /** 캔버스는 왼쪽, 컨트롤은 폭 [panelWidthDp]의 사이드 패널. */
    public data class SidePanel(val panelWidthDp: Int) : EditorLayout

    /** 캔버스는 가로 힌지 위, 컨트롤은 그 아래. */
    public data class SplitAtHorizontalHinge(val hingeTopPx: Int, val hingeBottomPx: Int) : EditorLayout

    /** 캔버스는 세로 힌지 왼쪽, 컨트롤은 그 오른쪽. */
    public data class SplitAtVerticalHinge(val hingeLeftPx: Int, val hingeRightPx: Int) : EditorLayout
}

/**
 * 창 크기와 폴드 자세로 에디터 레이아웃을 고른다.
 *
 * 프로젝트와 정규화 좌표는 레이아웃에 의존하지 않으므로, 접기·펼치기·크기 변경으로 레이아웃이
 * 바뀌어도 모든 편집이 제자리에 유지된다.
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
