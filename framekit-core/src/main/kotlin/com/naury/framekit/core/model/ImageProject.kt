package com.naury.framekit.core.model

import com.naury.framekit.core.effect.Adjustments
import com.naury.framekit.core.effect.ColorEffectSpec
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.overlay.DrawingStroke
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.PrivacyMask
import com.naury.framekit.core.overlay.SubjectCutout

/**
 * 이미지 하나의 최종 편집 상태.
 *
 * 프로젝트는 편집을 만든 명령 목록이 아니라 편집 결과를 저장한다. 렌더러는 이 스냅샷만으로 계획을
 * 세우므로, 히스토리를 다르게 재생해서 미리보기와 내보내기가 어긋나는 일이 없다.
 *
 * @property source 원본 이미지의 키. 원본은 절대 덮어쓰지 않는다.
 * @property geometry 바로 세운 원본에 적용하는 회전, 뒤집기, 수평 보정, 자르기.
 * @property cutout 다른 모든 처리보다 먼저 원본에 적용하는 배경 제거. 없으면 `null`.
 * @property adjustments 색상·톤 보정. geometry 다음에 적용한다.
 * @property filter 프리셋과 강도. adjustments 다음에 적용한다.
 * @property privacyMasks 블러·모자이크 영역. 색상 처리 뒤, 그리기 레이어 전에 적용한다.
 * @property overlays z-order 순(마지막이 맨 위)의 텍스트와 스티커. 출력 캔버스 기준으로 배치한다.
 * @property drawing 그리기 레이어의 획. [overlays] 아래에 그린다. 지우개는 이 레이어에만
 *   영향을 준다.
 * @property grainSeed 다시 그릴 때나 미리보기와 내보내기 사이에서 grain 패턴이 바뀌지 않도록
 *   고정한다.
 */
public data class ImageProject(
    val id: ProjectId,
    val source: SourceId,
    val geometry: GeometryEdit = GeometryEdit(),
    val cutout: SubjectCutout? = null,
    val adjustments: Adjustments = Adjustments(),
    val filter: FilterSelection = FilterSelection(),
    val privacyMasks: List<PrivacyMask> = emptyList(),
    val overlays: List<ImageOverlay> = emptyList(),
    val drawing: List<DrawingStroke> = emptyList(),
    val grainSeed: Long = 0L,
    override val revision: Long = 0L,
) : ProjectSnapshot<ImageProject> {

    /** 이 스냅샷에 대해 확정된 색상 파이프라인. */
    public val colorSpec: ColorEffectSpec get() = ColorEffectSpec.of(adjustments, filter, grainSeed)

    override fun withRevision(revision: Long): ImageProject = copy(revision = revision)

    override fun sameContentAs(other: ImageProject): Boolean =
        id == other.id && source == other.source && geometry == other.geometry && cutout == other.cutout &&
            adjustments == other.adjustments && filter == other.filter && privacyMasks == other.privacyMasks && overlays == other.overlays &&
            drawing == other.drawing && grainSeed == other.grainSeed
}
