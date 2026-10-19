package com.naury.framekit.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naury.kinetica.ui.KDialSlider

/**
 * 눈금자를 좌우로 끌어 값을 고르는 슬라이더(사진 앱의 보정 다이얼 방식).
 *
 * 가운데 기준선은 고정되고 눈금이 손가락을 따라 움직인다. [resetValue]부터 현재 값까지의 눈금은 프라이머리 색으로
 * 칠하고, 값은 위에 크게 보여 준다. 세게 밀고 놓으면 그 속도로 이어서 미끄러지다 서서히 멈추며(다시 잡으면 멈춤),
 * [onValueChangeFinished]는 미끄러짐이 끝난 뒤 한 번 호출된다. 눈금을 지날 때마다 다이얼 톱니처럼 짧게 진동하고(큰 눈금은 조금 더 또렷하게),
 * [resetValue]와 양 끝에서는 분명하게 진동한다. 두 번 탭하면 [resetValue]로
 * 돌아간다. [onValueChange]는 움직일 때마다, [onValueChangeFinished]는 제스처 하나(관성 포함)가 끝날 때 한 번 호출된다.
 * RTL에서는 큰 값이 왼쪽에 있다. [valueRange]의 끝이 시작보다 크지 않으면 조작할 수 없게 흐리게 그린다.
 *
 * @param spacing 값 1만큼의 눈금 간격(dp). 클수록 세밀하게 조절된다.
 * @param tickEvery 작은 눈금을 그리는 값 간격.
 * @param majorEvery 큰 눈금을 그리는 값 간격.
 * @param snapThreshold 값이 [resetValue]에 이만큼 가까워지면 [resetValue]로 붙는다. 0 이하면 붙지 않는다.
 */
@Composable
public fun DialSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    formatValue: (Float) -> String,
    modifier: Modifier = Modifier,
    resetValue: Float = 0f,
    spacing: Dp = 4.dp,
    tickEvery: Float = 2f,
    majorEvery: Float = 10f,
    snapThreshold: Float = 1.5f,
) {
    KDialSlider(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        valueRange = valueRange,
        formatValue = formatValue,
        onValueChangeFinished = onValueChangeFinished,
        resetValue = resetValue,
        spacing = spacing,
        tickEvery = tickEvery,
        majorEvery = majorEvery,
        // 음수는 예전에도 붙지 않았으므로 0(붙지 않음)과 같다.
        snapThreshold = snapThreshold.coerceAtLeast(0f),
        // 기준값과 0.5 미만으로 다르면 보이는 숫자는 기준값과 같으므로 바뀐 것으로 칠하지 않는다.
        changedThreshold = CHANGED_THRESHOLD,
        // 호스트 글꼴이 들어 있는 MaterialTheme 글자 단계를 써서 다른 편집 화면 글자와 맞춘다.
        valueTextStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        // 예전에는 범위 밖 기준값도 조작할 수 있었으므로, 막지 않고 가까운 끝값을 기준값으로 삼는다.
        coerceResetValue = true,
    )
}

private const val CHANGED_THRESHOLD = 0.5f
