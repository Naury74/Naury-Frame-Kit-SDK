package com.naury.framekit.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.R
import com.naury.framekit.ui.design.FrameKitTheme

/**
 * 에디터 상단 바. 왼쪽은 닫기(또는 뒤로 가기), 오른쪽은 저장.
 *
 * 도구 화면에서는 [toolTitle]을 넘긴다. 그러면 왼쪽 버튼이 닫기(X) 대신 뒤로 가기 화살표가 되고, 그 옆에 도구 이름을 표시한다.
 * 편집 중에 X를 잘못 눌러 에디터를 닫으려는 일을 막고, 지금 어느 도구에 있는지 위에서 바로 알 수 있게 한다.
 *
 * @param onClose 왼쪽 버튼. 도구 화면이면 도구를 닫는 뒤로 가기(시스템 뒤로 가기와 같은 동작)를 넘긴다.
 * @param toolTitle 열린 도구의 이름. `null`이면 도구 목록 화면으로 보고 닫기 버튼과 빈 제목을 쓴다.
 */
@Composable
public fun EditorTopBar(
    onClose: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    modifier: Modifier = Modifier,
    toolTitle: String? = null,
) {
    val colors = FrameKitTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            if (toolTitle != null) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.framekit_action_back), tint = colors.foreground)
            } else {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.framekit_action_close), tint = colors.foreground)
            }
        }
        Text(
            toolTitle.orEmpty(),
            color = colors.foreground,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { heading() },
        )
        Button(
            onClick = onSave,
            enabled = saveEnabled,
            modifier = Modifier.padding(end = 12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
        ) {
            Text(stringResource(R.string.framekit_action_save))
        }
    }
}
