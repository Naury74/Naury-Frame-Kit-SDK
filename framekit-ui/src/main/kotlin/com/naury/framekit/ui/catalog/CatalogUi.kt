package com.naury.framekit.ui.catalog

import android.graphics.Typeface
import android.graphics.Bitmap
import androidx.compose.runtime.staticCompositionLocalOf
import com.naury.framekit.android.catalog.EditorCatalog
import com.naury.framekit.core.effect.FilterCatalog
import com.naury.framekit.core.overlay.FontCatalog
import com.naury.framekit.core.overlay.StickerCatalog

/**
 * 도구 패널이 보여 줄 필터·스티커·폰트 목록과 호스트 항목의 이름.
 *
 * @property stickerImage 호스트 스티커 에셋 id의 미리보기 이미지. 이미지를 불러오는 모듈이 채운다.
 * @property typeface 호스트 폰트 id의 Typeface. 편집기 문구 폰트([com.naury.framekit.ui.config.EditorUiConfig.uiFontId])에도 쓴다.
 */
public class CatalogUi(
    public val catalog: EditorCatalog = EditorCatalog(),
    public val stickerImage: (String) -> Bitmap? = { null },
    public val typeface: (String) -> Typeface? = { null },
) {
    /** 필터 패널 순서. 원본(필터 없음)은 항상 맨 앞이다. */
    public val filterIds: List<String>
        get() = (if (catalog.showDefaultFilters) FilterCatalog.presets.map { it.id } else listOf(FilterCatalog.ORIGINAL_ID)) +
            catalog.filters.map { it.id }

    public val fontIds: List<String>
        get() = (if (catalog.showDefaultFonts) FontCatalog.fontIds else listOf(FontCatalog.fontIds.first())) + catalog.fonts.map { it.id }

    /** 호스트 스티커의 에셋 id와 이름. */
    public val stickers: List<Pair<String, String>> get() = catalog.stickers.map { StickerCatalog.assetId(it.id) to it.label }

    public val showEmoji: Boolean get() = catalog.showDefaultStickers

    public fun filterLabel(id: String): String? = catalog.filters.firstOrNull { it.id == id }?.label

    public fun fontLabel(id: String): String? = catalog.fonts.firstOrNull { it.id == id }?.label
}

/** 편집 화면이 제공하는 카탈로그. 기본값은 내장 항목만 있는 목록이다. */
public val LocalCatalogUi: androidx.compose.runtime.ProvidableCompositionLocal<CatalogUi> = staticCompositionLocalOf { CatalogUi() }
