package com.naury.framekit.chooser

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naury.framekit.EditorAction
import com.naury.framekit.R
import com.naury.framekit.ui.component.DialogAction
import com.naury.framekit.ui.component.DialogActionStyle
import com.naury.framekit.ui.component.FrameKitDialog
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.R as UiR
import com.naury.framekit.ui.motion.R as MotionR

/**
 * 넘겨받은 원본으로 무엇을 할지 고르는 하단 시트. 위에 원본 미리보기 줄, 그 아래에 편집 선택지 카드, 맨 아래에 "편집 없이 보내기"를 둔다.
 *
 * @param thumbnails 원본 순서의 미리보기. 아직 읽는 중이면 `null` 자리에 빈 칸을 그린다.
 */
@Composable
internal fun ChooserSheet(
    count: Int,
    thumbnails: List<Bitmap?>,
    options: ChooserOptions,
    onChoose: (EditorAction) -> Unit,
    onDismiss: () -> Unit,
) {
    FrameKitDialog(
        title = stringResource(R.string.framekit_chooser_title),
        message = pluralStringResource(R.plurals.framekit_chooser_count, count, count),
        onDismiss = onDismiss,
        actions = listOf(DialogAction(stringResource(UiR.string.framekit_action_cancel), DialogActionStyle.SECONDARY, onClick = onDismiss)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ThumbnailStrip(count, thumbnails)
            if (options.mixed) Note(stringResource(R.string.framekit_chooser_mixed))
            options.actions.filter { it != EditorAction.SEND_ORIGINAL }.forEach { action ->
                val (title, body) = labels(action, count, options.singleAnimated)
                OptionCard(iconOf(action), accentOf(action), title, body) { onChoose(action) }
            }
            if (EditorAction.SEND_ORIGINAL in options.actions) {
                SendRow { onChoose(EditorAction.SEND_ORIGINAL) }
            }
        }
    }
}

@Composable
private fun ThumbnailStrip(count: Int, thumbnails: List<Bitmap?>) {
    val colors = FrameKitTheme.colors
    val shown = minOf(count, MAX_THUMBS)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(shown) { index ->
            val last = index == shown - 1 && count > shown
            Box(Modifier.size(THUMB.dp).clip(RoundedCornerShape(12.dp)).background(colors.raised), contentAlignment = Alignment.Center) {
                thumbnails.getOrNull(index)?.takeIf { !it.isRecycled }?.let {
                    Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                }
                if (last) {
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        Text("+${count - shown + 1}", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** 아이콘 원·제목·설명·화살표로 된 선택지 카드. */
@Composable
private fun OptionCard(icon: Painter, accent: Color, title: String, body: String, onClick: () -> Unit) {
    val colors = FrameKitTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(colors.background)
            .border(1.dp, colors.raised, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).background(accent.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, color = colors.foreground, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(body, color = colors.foregroundMuted, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.foregroundMuted)
    }
}

/** 편집 없이 보내기. 편집 선택지보다 가볍게 보이도록 테두리 없는 줄로 둔다. */
@Composable
private fun SendRow(onClick: () -> Unit) {
    val colors = FrameKitTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.raised.copy(alpha = 0.6f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = colors.foreground, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.framekit_chooser_send), color = colors.foreground, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.framekit_chooser_send_body), color = colors.foregroundMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Note(text: String) {
    val colors = FrameKitTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.background, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = colors.foregroundMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = colors.foregroundMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun labels(action: EditorAction, count: Int, singleAnimated: Boolean): Pair<String, String> = when (action) {
    EditorAction.EDIT_PHOTO -> when {
        singleAnimated -> stringResource(R.string.framekit_chooser_photo_frame) to stringResource(R.string.framekit_chooser_photo_frame_body)
        count > 1 -> stringResource(R.string.framekit_chooser_photo_each) to stringResource(R.string.framekit_chooser_photo_each_body)
        else -> stringResource(R.string.framekit_chooser_photo) to stringResource(R.string.framekit_chooser_photo_body)
    }
    EditorAction.MAKE_PDF -> stringResource(R.string.framekit_chooser_pdf) to stringResource(R.string.framekit_chooser_pdf_body)
    EditorAction.MOTION -> if (singleAnimated) {
        stringResource(R.string.framekit_chooser_motion_edit) to stringResource(R.string.framekit_chooser_motion_edit_body)
    } else {
        stringResource(R.string.framekit_chooser_motion) to stringResource(R.string.framekit_chooser_motion_body)
    }
    EditorAction.EDIT_VIDEO -> stringResource(R.string.framekit_chooser_video) to stringResource(R.string.framekit_chooser_video_body)
    EditorAction.SEND_ORIGINAL -> stringResource(R.string.framekit_chooser_send) to stringResource(R.string.framekit_chooser_send_body)
}

@Composable
private fun iconOf(action: EditorAction): Painter = when (action) {
    EditorAction.EDIT_PHOTO -> painterResource(UiR.drawable.framekit_ic_adjust)
    EditorAction.MAKE_PDF -> painterResource(UiR.drawable.framekit_ic_document)
    EditorAction.MOTION -> painterResource(MotionR.drawable.framekit_ic_motion)
    EditorAction.EDIT_VIDEO -> painterResource(UiR.drawable.framekit_ic_trim)
    EditorAction.SEND_ORIGINAL -> rememberVectorPainter(Icons.AutoMirrored.Filled.Send)
}

// 선택지마다 색을 달리해 한눈에 구분되게 한다. 사진 편집은 테마 강조색을 따른다.
@Composable
private fun accentOf(action: EditorAction): Color = when (action) {
    EditorAction.EDIT_PHOTO -> FrameKitTheme.colors.accent
    EditorAction.MAKE_PDF -> Color(0xFF3B9CFF)
    EditorAction.MOTION -> Color(0xFFFF6B9A)
    EditorAction.EDIT_VIDEO -> Color(0xFF2EB67D)
    EditorAction.SEND_ORIGINAL -> FrameKitTheme.colors.foreground
}

private const val MAX_THUMBS = 6
private const val THUMB = 64
