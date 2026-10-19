package com.naury.framekit.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.R
import com.naury.kinetica.ui.KButton
import com.naury.kinetica.ui.KTone
import com.naury.kinetica.ui.KVariant

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
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        // 초기화가 없으면 적용만 오른쪽에 둔다. 번역이 길면 두 버튼이 너비를 반씩 나눠 가진다.
        horizontalArrangement = if (onReset != null) Arrangement.SpaceBetween else Arrangement.End,
    ) {
        // 손가락으로 누르기 쉽도록 두 버튼 모두 48dp 높이를 지킨다. 번역이 길면 한 줄로 자른다.
        if (onReset != null) {
            KButton(
                stringResource(R.string.framekit_action_reset),
                onClick = onReset,
                modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp),
                variant = KVariant.Tonal,
                tone = KTone.Neutral,
                icon = Icons.Filled.Refresh,
            )
            Spacer(Modifier.width(12.dp))
        }
        KButton(
            stringResource(R.string.framekit_action_apply),
            onClick = onApply,
            modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp),
            icon = Icons.Filled.Check,
        )
    }
}
