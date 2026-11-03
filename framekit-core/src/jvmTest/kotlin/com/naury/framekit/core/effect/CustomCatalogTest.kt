package com.naury.framekit.core.effect

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.overlay.FontCatalog
import com.naury.framekit.core.overlay.StickerCatalog
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class CustomCatalogTest {

    @After
    fun tearDown() {
        FilterCatalog.install(emptyList())
        FontCatalog.install(emptyList())
        StickerCatalog.install(emptyList())
    }

    @Test
    fun `host filters resolve by id and change the color spec`() {
        val brand = FilterPreset("brand-warm", 1, FilterGrade(temperature = 0.4, saturation = 0.2))
        FilterCatalog.install(listOf(brand))

        assertThat(FilterCatalog.find("brand-warm")).isEqualTo(brand)
        assertThat(FilterCatalog.all.last()).isEqualTo(brand)
        assertThat(ColorEffectSpec.of(Adjustments(), FilterSelection("brand-warm", 1.0), 0L).isIdentity).isFalse()
    }

    @Test
    fun `host ids may not shadow built-in ones`() {
        assertThrows(IllegalArgumentException::class.java) { FilterCatalog.install(listOf(FilterPreset("mono", 1, FilterGrade()))) }
        assertThrows(IllegalArgumentException::class.java) { FontCatalog.install(listOf("serif")) }
    }

    @Test
    fun `installed fonts and stickers become valid references`() {
        FontCatalog.install(listOf("brand-sans"))
        StickerCatalog.install(listOf("logo"))

        assertThat(FontCatalog.contains("brand-sans")).isTrue()
        assertThat(StickerCatalog.contains("sticker:logo")).isTrue()
        assertThat(StickerCatalog.contains("emoji:😀")).isTrue()
        assertThat(StickerCatalog.contains("sticker:other")).isFalse()
    }
}
