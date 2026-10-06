package com.naury.framekit.ui.tool

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

/** 카테고리별로 묶인 이모지 목록. 이모지를 탭하면 캔버스 중앙에 놓인다. */
@Composable
public fun StickerToolPanel(
    category: EmojiCatalog.Category,
    onCategory: (EmojiCatalog.Category) -> Unit,
    onAdd: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val group = EmojiCatalog.groups.first { it.category == category }
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        ChoiceChips(
            options = EmojiCatalog.groups.map { it.category },
            selected = category,
            label = { stringResource(it.labelRes()) },
            onSelect = onCategory,
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(48.dp),
            modifier = Modifier.fillMaxWidth().height(184.dp).padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            items(group.emoji, key = { it }) { emoji ->
                Box(Modifier.size(48.dp).clickable { onAdd(emoji) }, contentAlignment = Alignment.Center) {
                    Text(emoji, fontSize = 28.sp)
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
