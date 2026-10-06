package com.naury.framekit.core.overlay

import com.naury.framekit.core.geometry.PointN

/**
 * 출력 캔버스(C 공간, 자르기 후 `0..1`)에서의 오버레이 배치.
 *
 * 오버레이는 자르기가 바뀌어도 같은 정규화 위치에 머문다. 사진 속 대상에 고정되지 않는다.
 *
 * @property center C에서 오버레이의 중심.
 * @property scale 크기 배율. `1.0`은 오버레이를 만들 때의 크기다.
 * @property rotationDegrees 시계 방향 회전.
 * @property opacity `0..1`.
 */
public data class OverlayTransform(
    val center: PointN = PointN(0.5, 0.5),
    val scale: Double = 1.0,
    val rotationDegrees: Double = 0.0,
    val opacity: Double = 1.0,
) {
    public companion object {
        public const val MIN_SCALE: Double = 0.1
        public const val MAX_SCALE: Double = 10.0
    }
}

/** 텍스트 상자 안의 문단 정렬. */
public enum class TextAlignment {
    START,
    CENTER,
    END,
}

/** 글리프 외곽선. [widthHeightRatio]는 캔버스 높이에 대한 비율이다. */
public data class StrokeSpec(val colorArgb: Int, val widthHeightRatio: Double)

/** 그림자. 오프셋과 블러는 캔버스 높이에 대한 비율이다. */
public data class ShadowSpec(
    val colorArgb: Int,
    val offsetXHeightRatio: Double,
    val offsetYHeightRatio: Double,
    val blurHeightRatio: Double,
)

/** 텍스트 뒤의 둥근 상자. 패딩과 모서리 반경은 캔버스 높이에 대한 비율이다. */
public data class BackgroundSpec(val colorArgb: Int, val paddingHeightRatio: Double = 0.008, val cornerHeightRatio: Double = 0.006)

/**
 * 텍스트 모양. 크기는 출력 캔버스에 대한 비율이므로 같은 프로젝트는 어떤 내보내기 해상도에서도
 * 똑같이 보인다.
 *
 * @property fontId 폰트 카탈로그의 폰트 id. 예: `sans`, `serif-bold`.
 * @property fontSizeHeightRatio 텍스트 크기를 캔버스 높이로 나눈 값.
 * @property maxWidthRatio 줄바꿈 너비를 캔버스 너비로 나눈 값.
 */
public data class TextStyleSpec(
    val fontId: String = "sans",
    val fontSizeHeightRatio: Double = 0.06,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val alignment: TextAlignment = TextAlignment.CENTER,
    val letterSpacingEm: Double = 0.0,
    val lineSpacingMultiplier: Double = 1.0,
    val maxWidthRatio: Double = 0.8,
    val stroke: StrokeSpec? = null,
    val shadow: ShadowSpec? = null,
    val background: BackgroundSpec? = null,
)

/** 이미지 위에 놓는 텍스트나 스티커. 목록 순서가 z-order이며 마지막이 맨 위다. */
public sealed interface ImageOverlay {
    public val id: String
    public val transform: OverlayTransform

    public fun withTransform(transform: OverlayTransform): ImageOverlay

    public data class Text(
        override val id: String,
        val text: String,
        val style: TextStyleSpec = TextStyleSpec(),
        override val transform: OverlayTransform = OverlayTransform(),
    ) : ImageOverlay {
        override fun withTransform(transform: OverlayTransform): Text = copy(transform = transform)
    }

    /**
     * 카탈로그 에셋으로 그리는 스티커.
     *
     * @property assetId 시스템 이모지 폰트로 렌더링하는 표준 이모지면 `emoji:<characters>`,
     *   아니면 호스트 스티커 카탈로그의 id.
     * @property widthRatio 배율 1에서의 스티커 너비를 캔버스 너비로 나눈 값.
     */
    public data class Sticker(
        override val id: String,
        val assetId: String,
        val widthRatio: Double = 0.25,
        override val transform: OverlayTransform = OverlayTransform(),
    ) : ImageOverlay {
        override fun withTransform(transform: OverlayTransform): Sticker = copy(transform = transform)
    }
}

/** 그리기 레이어의 브러시 종류. */
public enum class BrushKind {
    PEN,
    MARKER,

    /** 반투명 획. 한 획 안에서 겹쳐도 더 어두워지지 않는다. */
    HIGHLIGHTER,

    /** 획 아래의 그리기 레이어만 지운다. 사진, 텍스트, 스티커는 절대 지우지 않는다. */
    ERASER,
}

/**
 * C 공간에서 획의 샘플 하나.
 *
 * @property pressure `0..1`. 굵기 변화에 쓴다.
 */
public data class StrokePoint(val x: Double, val y: Double, val pressure: Double = 1.0)

/**
 * 손가락을 대고 뗄 때까지의 획 하나. 굵기는 캔버스 짧은 변에 대한 비율이다.
 */
public data class DrawingStroke(
    val id: String,
    val points: List<StrokePoint>,
    val widthShortEdgeRatio: Double,
    val colorArgb: Int,
    val opacity: Double,
    val brush: BrushKind,
) {
    public companion object {
        /** 획당 점 개수의 상한. 더 긴 획은 기록할 때 솎아낸다. */
        public const val MAX_POINTS: Int = 2_000
    }
}

/**
 * 텍스트 폰트 id 목록. 모든 기기가 렌더링할 수 있는 내장 폰트([fontIds])와 호스트가 등록한 폰트([custom]).
 * 호스트 폰트 파일은 Android 모듈이 불러오고, 여기에는 id만 둔다.
 */
public object FontCatalog {
    public val fontIds: List<String> = listOf("sans", "sans-bold", "serif", "serif-bold", "mono", "handwriting")

    @Volatile
    private var installed: List<String> = emptyList()

    /** 호스트가 등록한 폰트 id. */
    public val custom: List<String> get() = installed

    public fun contains(id: String): Boolean = id in fontIds || id in installed

    /**
     * 호스트 폰트 id를 바꾼다.
     *
     * @throws IllegalArgumentException 내장 폰트와 id가 겹치거나 id가 중복될 때.
     */
    public fun install(ids: List<String>) {
        require(ids.none { it in fontIds }) { "Custom font ids must not reuse built-in ids" }
        require(ids.toSet().size == ids.size) { "Custom font ids must be unique" }
        installed = ids.toList()
    }
}

/**
 * 호스트가 등록한 이미지 스티커. 에셋 id는 [ASSET_PREFIX] + 호스트 id이고, 이미지는 Android 모듈이 불러온다.
 * 이모지 스티커는 [EmojiCatalog]를 쓴다.
 */
public object StickerCatalog {
    public const val ASSET_PREFIX: String = "sticker:"

    @Volatile
    private var installed: List<String> = emptyList()

    /** 등록한 스티커의 에셋 id. */
    public val assetIds: List<String> get() = installed

    public fun assetId(id: String): String = ASSET_PREFIX + id

    /** 이모지 스티커이거나 등록한 이미지 스티커이면 `true`. */
    public fun contains(assetId: String): Boolean = EmojiCatalog.emojiOf(assetId) != null || assetId in installed

    /** @throws IllegalArgumentException id가 중복될 때. */
    public fun install(ids: List<String>) {
        require(ids.toSet().size == ids.size) { "Custom sticker ids must be unique" }
        installed = ids.map(::assetId)
    }
}
