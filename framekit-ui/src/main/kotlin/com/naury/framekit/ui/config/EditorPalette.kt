package com.naury.framekit.ui.config

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 편집기 기본 색을 바꾸는 값(ARGB). `null`인 항목은 고른 테마의 기본값을 쓴다.
 *
 * 색을 바꿀 때는 [contrastWarnings]로 글자가 잘 보이는지 확인하세요. 편집기는 경고가 있어도 그대로 쓴다.
 *
 * @property backgroundArgb 화면 배경.
 * @property surfaceArgb 패널·칩 배경.
 * @property raisedArgb 선택·강조된 패널 배경.
 * @property foregroundArgb 기본 글자·아이콘.
 * @property foregroundMutedArgb 보조 글자·비활성 아이콘.
 * @property canvasArgb 사진·영상 뒤 여백.
 * @property onPrimaryArgb 프라이머리 색 위의 글자(저장 버튼 등). `null`이면 프라이머리 색에 맞춰 흰색·검정 중
 *   명암비가 높은 쪽을 쓴다.
 */
@Parcelize
public data class EditorPalette(
    val backgroundArgb: Int? = null,
    val surfaceArgb: Int? = null,
    val raisedArgb: Int? = null,
    val foregroundArgb: Int? = null,
    val foregroundMutedArgb: Int? = null,
    val canvasArgb: Int? = null,
    val onPrimaryArgb: Int? = null,
) : Parcelable {

    /**
     * 글자와 배경의 명암비가 WCAG 기준(본문 4.5:1, 보조 글자 3:1)보다 낮은 조합을 알려 준다.
     *
     * @param base 바꾸지 않은 항목에 쓸 테마 기본값. 값 순서는 이 클래스의 프로퍼티와 같다.
     * @return 문제가 있는 조합 설명. 비어 있으면 문제 없음.
     */
    public fun contrastWarnings(base: EditorPalette, primaryArgb: Int): List<String> {
        val background = backgroundArgb ?: base.backgroundArgb ?: return emptyList()
        val surface = surfaceArgb ?: base.surfaceArgb ?: background
        val foreground = foregroundArgb ?: base.foregroundArgb ?: return emptyList()
        val muted = foregroundMutedArgb ?: base.foregroundMutedArgb ?: foreground
        val onPrimary = onPrimaryArgb ?: base.onPrimaryArgb ?: readableOn(primaryArgb)
        return buildList {
            if (contrast(foreground, background) < 4.5) add("foreground/background")
            if (contrast(foreground, surface) < 4.5) add("foreground/surface")
            if (contrast(muted, background) < 3.0) add("foregroundMuted/background")
            if (contrast(onPrimary, primaryArgb) < 3.0) add("onPrimary/primary")
            // 프라이머리 색은 패널 위 아이콘·체크 표시에도 쓰여, 배경과 너무 비슷하면 선택 표시가 보이지 않는다.
            if (contrast(primaryArgb, surface) < 1.5) add("primary/surface")
        }
    }

    public companion object {
        /**
         * [argb] 위에 올릴 글자색(불투명 ARGB). 흰색의 명암비가 3:1(버튼 글자 기준) 이상이면 흰색, 아니면 흰색·검정 중
         * 명암비가 높은 쪽이다. 파랑·보라처럼 둘 다 비슷한 색에서는 흔히 쓰는 흰 글자를 고른다.
         */
        public fun readableOn(argb: Int): Int {
            val white = 0xFFFFFFFF.toInt()
            val black = 0xFF000000.toInt()
            val onWhite = contrast(white, argb)
            return if (onWhite >= MIN_BUTTON_CONTRAST || onWhite >= contrast(black, argb)) white else black
        }

        private const val MIN_BUTTON_CONTRAST = 3.0

        /** WCAG 명암비. 1(같은 색)..21(검정·흰색). 알파는 무시한다. */
        public fun contrast(first: Int, second: Int): Double {
            val a = luminance(first)
            val b = luminance(second)
            return (max(a, b) + 0.05) / (min(a, b) + 0.05)
        }

        private fun luminance(argb: Int): Double {
            fun channel(shift: Int): Double {
                val c = ((argb shr shift) and 0xFF) / 255.0
                return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
    }
}
