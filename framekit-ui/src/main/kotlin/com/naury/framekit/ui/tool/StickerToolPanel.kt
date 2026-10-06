package com.naury.framekit.ui.tool

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.remember
import androidx.compose.foundation.Image
import com.naury.framekit.ui.catalog.LocalCatalogUi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naury.framekit.core.overlay.EmojiCatalog
import com.naury.framekit.ui.component.ChoiceChips
import com.naury.framekit.ui.R

/**
 * 카테고리별 이모지와 호스트 스티커 목록. 탭하면 캔버스 중앙에 놓인다.
 *
 * @param category 고른 이모지 분류. `null`이면 호스트 스티커 탭이다.
 * @param onAdd 고른 스티커의 에셋 id(이모지는 [EmojiCatalog.assetId], 호스트 스티커는 `sticker:` id).
 */
@Composable
public fun StickerToolPanel(
    category: EmojiCatalog.Category?,
    onCategory: (EmojiCatalog.Category?) -> Unit,
    onAdd: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val catalog = LocalCatalogUi.current
    val custom = catalog.stickers
    val tabs = buildList<EmojiCatalog.Category?> {
        if (custom.isNotEmpty()) add(null)
        if (catalog.showEmoji) addAll(EmojiCatalog.groups.map { it.category })
    }
    val selected = category.takeIf { it in tabs } ?: tabs.firstOrNull()
    val customLabel = stringResource(R.string.framekit_sticker_custom)
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (tabs.size > 1) {
            ChoiceChips(
                options = tabs,
                selected = selected,
                label = { it?.let { tab -> stringResource(tab.labelRes()) } ?: customLabel },
                onSelect = onCategory,
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (selected == null) 64.dp else 48.dp),
            modifier = Modifier.fillMaxWidth().height(184.dp).padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            if (selected == null) {
                items(custom, key = { it.first }) { (assetId, label) ->
                    Box(Modifier.size(64.dp).padding(4.dp).clickable { onAdd(assetId) }, contentAlignment = Alignment.Center) {
                        val image = remember(assetId) { catalog.stickerImage(assetId) }
                        if (image != null) {
                            Image(image.asImageBitmap(), contentDescription = label, contentScale = ContentScale.Fit, modifier = Modifier.size(56.dp))
                        } else {
                            Text(label, fontSize = 11.sp, maxLines = 2)
                        }
                    }
                }
            } else {
                val group = EmojiCatalog.groups.first { it.category == selected }
                items(group.emoji, key = { it }) { emoji ->
                    Box(Modifier.size(48.dp).clickable { onAdd(EmojiCatalog.assetId(emoji)) }, contentAlignment = Alignment.Center) {
                        Text(emoji, fontSize = 28.sp)
                    }
                }
            }
        }
    }
}

private fun EmojiCatalog.Category.labelRes(): Int = when (this) {
    EmojiCatalog.Category.SMILEYS -> R.string.framekit_sticker_smileys
    EmojiCatalog.Category.GESTURES -> R.string.framekit_sticker_gestures
    EmojiCatalog.Category.HEARTS -> R.string.framekit_sticker_hearts
    EmojiCatalog.Category.ANIMALS -> R.string.framekit_sticker_animals
    EmojiCatalog.Category.FOOD -> R.string.framekit_sticker_food
    EmojiCatalog.Category.ACTIVITIES -> R.string.framekit_sticker_activities
    EmojiCatalog.Category.TRAVEL -> R.string.framekit_sticker_travel
    EmojiCatalog.Category.OBJECTS -> R.string.framekit_sticker_objects
    EmojiCatalog.Category.SYMBOLS -> R.string.framekit_sticker_symbols
}
