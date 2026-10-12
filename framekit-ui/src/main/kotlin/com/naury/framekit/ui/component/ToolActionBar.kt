package com.naury.framekit.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.R
import com.naury.framekit.ui.design.FrameKitTheme

/**
 * 열린 도구의 하단 바. 왼쪽에 초기화, 오른쪽에 적용을 둔다. 두 버튼 모두 아이콘과 글자를 함께 보여 준다.
 *
 * 도구를 취소하고 나가는 동작은 이 바가 아니라 상단 바의 뒤로 가기 버튼([EditorTopBar]의 `toolTitle`)이 맡는다.
 * 넓은 화면에서는 호출하는 쪽이 이 바를 패널 맨 아래에 고정해 패널을 스크롤해도 늘 보이게 둔다.
 *
 * @param onApply 적용. 초안 도구는 초안을 확정하고, 바로 기록되는 도구는 도구를 닫는다.
 * @param onReset 초기화. 되돌릴 기준이 없는 도구는 `null`로 두어 버튼을 숨긴다.
 */
@Composable
public fun ToolActionBar(
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
) {
    val colors = FrameKitTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        // 초기화가 없으면 적용만 오른쪽에 둔다. 번역이 길면 두 버튼이 너비를 반씩 나눠 가진다.
        horizontalArrangement = if (onReset != null) Arrangement.SpaceBetween else Arrangement.End,
    ) {
        if (onReset != null) {
            ActionButton(
                icon = Icons.Filled.Refresh,
                label = stringResource(R.string.framekit_action_reset),
                container = colors.raised,
                content = colors.foreground,
                onClick = onReset,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(12.dp))
        }
        ActionButton(
            icon = Icons.Filled.Check,
            label = stringResource(R.string.framekit_action_apply),
            container = colors.accent,
            content = colors.onAccent,
            onClick = onApply,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, container: Color, content: Color, onClick: () -> Unit, modifier: Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(start = 16.dp, end = 20.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        // 번역이 길어도 버튼이 두 줄로 커지지 않게 한 줄로 자른다.
        Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
    }
}
