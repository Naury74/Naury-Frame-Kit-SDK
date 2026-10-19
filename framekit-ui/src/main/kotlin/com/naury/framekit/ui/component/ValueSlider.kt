package com.naury.framekit.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.kinetica.ui.KSlider
import com.naury.kinetica.ui.KSliderBadgePosition
import com.naury.kinetica.ui.KSliderSnap
import com.naury.kinetica.ui.KSliderThumb

/**
 * 숫자 라벨, 더블탭 초기화, [resetValue]에 스냅될 때 한 번의 햅틱을 갖춘 슬라이더.
 *
 * [onValueChange]는 움직일 때마다, [onValueChangeFinished]는 제스처가 끝날 때 한 번 호출되므로
 * 호출자는 드래그 한 번을 히스토리 한 단계로 대응시킬 수 있다.
 *
 * @param snapThreshold 값이 [resetValue]로 스냅되는 [resetValue]로부터의 거리(값과 같은 단위). 0 이하면 스냅하지 않고
 *   정확히 [resetValue]에 닿을 때만 진동한다. [valueRange]의 끝이 시작보다 크지 않으면 조작할 수 없게 흐리게 그린다.
 */
@Composable
public fun ValueSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    formatValue: (Float) -> String,
    modifier: Modifier = Modifier,
    resetValue: Float = 0f,
    snapThreshold: Float = 0f,
) {
    val colors = FrameKitTheme.colors

    KSlider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        // 빈 범위는 Kinetica가 조작을 막고 흐리게 그린다.
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        thumb = KSliderThumb.Circle,
        // 범위 0이어도 기준값에 정확히 닿는 순간은 한 번 진동해야 하므로 스냅은 항상 건다.
        snap = KSliderSnap(resetValue, snapThreshold.coerceAtLeast(0f)),
        resetValue = resetValue,
        // 값은 작은 배지로 보여 준다. 기준값에서 벗어나면 프라이머리 색으로 강조한다.
        valueBadge = { shown ->
            Text(
                formatValue(shown),
                color = if (shown != resetValue) colors.accent else colors.foreground,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(colors.raised, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        },
        badgePosition = KSliderBadgePosition.Top,
        inactiveTrackColor = colors.raised,
        // 화면 가장자리에서 시작한 드래그가 시스템 뒤로 가기로 처리되지 않게 한다.
        excludeSystemGestures = true,
        label = label,
        stateDescription = { formatValue(it) },
        // 패널의 다른 조작부와 같은 48dp 터치 높이를 맞춘다.
        minHeight = 48.dp,
        // 이름은 다른 패널 글자와 같은 크기로 두고, 트랙과 붙여 패널 높이를 늘리지 않는다.
        labelTextStyle = MaterialTheme.typography.labelLarge.copy(color = colors.foregroundMuted),
        labelSpacing = 0.dp,
    )
}
