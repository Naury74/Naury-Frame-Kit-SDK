package com.naury.framekit.ui.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.kinetica.ui.KAlertDialog
import com.naury.kinetica.ui.KDialogAction
import com.naury.kinetica.ui.KDialogActionStyle
import com.naury.kinetica.ui.KDialogPresentation

/** [FrameKitDialog] 버튼의 강조 단계. */
public enum class DialogActionStyle {
    /** 권장 동작. 프라이머리 색으로 채운다. */
    PRIMARY,

    /** 되돌리기 어려운 동작(버리기·삭제). 오류 색으로 표시한다. */
    DESTRUCTIVE,

    /** 그 밖의 선택(계속 편집·취소). 배경 없는 버튼이다. */
    SECONDARY,
}

/** [FrameKitDialog]의 버튼 하나. 목록 순서대로 위에서 아래로 쌓인다. */
public data class DialogAction(
    val label: String,
    val style: DialogActionStyle,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * FrameKit 편집기의 대화상자.
 *
 * 폰 화면에서는 아래에서 올라오는 시트로 뜬다. 위쪽 손잡이를 아래로 끌거나 바깥을 누르면 내려가며 닫힌다.
 * 넓은 화면(가로 600dp 이상)에서는 가운데 카드로 뜬다. 아이콘·제목·설명·버튼은 가운데 정렬이며, 버튼은
 * 화면이 좁아도 잘리지 않도록 알약 모양으로 세로로 쌓는다.
 *
 * @param icon 위쪽 원 안의 아이콘. `null`이면 아이콘 없이 제목부터 보인다.
 * @param iconTint 아이콘과 원 배경의 색. `null`이면 프라이머리 색.
 * @param message 제목 아래 설명. `null`이면 생략한다.
 * @param onDismiss 바깥을 누르거나, 끌어 내리거나, 뒤로 가기를 눌렀을 때. 닫히는 애니메이션이 끝난 뒤 호출된다.
 * @param content 설명과 버튼 사이에 넣을 내용(입력 칸 등).
 */
@Composable
public fun FrameKitDialog(
    title: String,
    actions: List<DialogAction>,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    message: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    // 구성에 있는 동안 열려 있다. 버튼은 닫지 않고 동작만 알리며, 닫을지는 호출한 쪽이 구성에서 빼는 것으로 정한다.
    // 바깥 누름·끌어 내림·뒤로 가기만 여기서 닫고, 닫히는 애니메이션이 끝난 뒤 onDismiss를 부른다.
    var visible by remember { mutableStateOf(true) }
    KAlertDialog(
        visible = visible,
        onDismissRequest = { visible = false },
        title = title,
        actions = actions.map { it.toKinetica() },
        message = message,
        icon = icon,
        stacked = true,
        // Kinetica는 위험 동작이 있으면 아이콘을 빨갛게 칠하므로, 기존처럼 지정하지 않으면 프라이머리 색이 되게 넘긴다.
        iconTint = iconTint ?: FrameKitTheme.colors.accent,
        presentation = KDialogPresentation.Auto,
        onDismissed = onDismiss,
        keepActionOrder = true,
        // 넓은 화면의 카드는 예전 FrameKit 대화상자처럼 거의 제 크기에서 살짝 커지며 나타나게 한다.
        appearScale = APPEAR_SCALE,
        content = content,
    )
}

private const val APPEAR_SCALE = 0.94f

private fun DialogAction.toKinetica(): KDialogAction = KDialogAction(
    label = label,
    style = when (style) {
        DialogActionStyle.PRIMARY -> KDialogActionStyle.Preferred
        DialogActionStyle.DESTRUCTIVE -> KDialogActionStyle.DestructiveTonal
        DialogActionStyle.SECONDARY -> KDialogActionStyle.Text
    },
    enabled = enabled,
    // 녹음이 너무 짧다는 안내처럼 같은 창에 결과를 보여 주는 호출이 있어, 닫기는 늘 호출한 쪽에 맡긴다.
    dismissOnClick = false,
    onClick = onClick,
)
