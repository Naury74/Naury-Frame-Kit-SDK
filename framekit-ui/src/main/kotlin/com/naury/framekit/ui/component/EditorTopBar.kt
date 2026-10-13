package com.naury.framekit.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naury.framekit.ui.R
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.design.frameKitGlass
import androidx.compose.foundation.shape.CircleShape

/** [FrameKitNavigationBar] 왼쪽 버튼의 역할. */
public enum class NavigationIcon {
    /** 화면을 닫는다. */
    CLOSE,

    /** 이전 단계로 돌아간다. */
    BACK,
}

/**
 * FrameKit 화면 공통 상단 바.
 *
 * @param title 제목. [titleContent]를 주면 무시한다.
 * @param actionLabel 오른쪽 버튼 글자. `null`이면 버튼을 두지 않는다.
 * @param titleContent 제목 자리에 둘 내용(예: 앨범을 고르는 버튼).
 */
@Composable
public fun FrameKitNavigationBar(
    title: String?,
    navigationIcon: NavigationIcon,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null,
) {
    val colors = FrameKitTheme.colors
    val container = colors.background
    // 아래에 오는 내용보다 나중에 그려야 페이드가 그 위에 덮인다.
    Box(
        modifier.zIndex(1f).fillMaxWidth().height(BAR_HEIGHT)
            .drawWithContent {
                drawRect(container)
                val fade = FADE_HEIGHT.toPx()
                drawRect(
                    Brush.verticalGradient(listOf(container, container.copy(alpha = 0f)), startY = size.height, endY = size.height + fade),
                    topLeft = Offset(0f, size.height),
                    size = Size(size.width, fade),
                )
                drawContent()
            }
            .padding(horizontal = 12.dp),
    ) {
        val navigationLabel = stringResource(if (navigationIcon == NavigationIcon.BACK) R.string.framekit_action_back else R.string.framekit_action_close)
        PressFade(
            onClick = onNavigate,
            modifier = Modifier.align(Alignment.CenterStart).size(BUTTON_SIZE).frameKitGlass(CircleShape).semantics { contentDescription = navigationLabel },
        ) {
            when (navigationIcon) {
                NavigationIcon.BACK -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = colors.foreground, modifier = Modifier.size(28.dp))
                NavigationIcon.CLOSE -> Icon(Icons.Filled.Close, contentDescription = null, tint = colors.foreground, modifier = Modifier.size(20.dp))
            }
        }
        // 양쪽 버튼과 겹치지 않도록 가운데 폭을 줄인다.
        Box(Modifier.align(Alignment.Center).padding(horizontal = TITLE_INSET), contentAlignment = Alignment.Center) {
            if (titleContent != null) {
                titleContent()
            } else if (!title.isNullOrEmpty()) {
                Text(
                    title,
                    color = colors.foreground,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }
        if (actionLabel != null) {
            PressFade(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = Modifier.align(Alignment.CenterEnd).height(BUTTON_SIZE).widthIn(min = BUTTON_SIZE)
                    .frameKitGlass(CircleShape, tint = if (actionEnabled) colors.accent else null)
                    .padding(horizontal = 18.dp),
            ) {
                Text(
                    actionLabel,
                    color = if (actionEnabled) colors.onAccent else colors.foregroundMuted,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 에디터 상단 바. 닫기(또는 뒤로 가기)와 저장 버튼이 있다.
 *
 * 도구 화면에서는 [toolTitle]을 넘긴다. 그러면 왼쪽 버튼이 닫기 대신 뒤로 가기가 되고 도구 이름이 제목이 된다.
 * 편집 중에 닫기를 잘못 눌러 에디터를 닫으려는 일을 막고, 지금 어느 도구에 있는지 위에서 바로 알 수 있게 한다.
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
    FrameKitNavigationBar(
        title = toolTitle,
        navigationIcon = if (toolTitle != null) NavigationIcon.BACK else NavigationIcon.CLOSE,
        onNavigate = onClose,
        modifier = modifier,
        actionLabel = stringResource(R.string.framekit_action_save),
        actionEnabled = saveEnabled,
        onAction = onSave,
    )
}

@Composable
private fun PressFade(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick).alpha(if (pressed) 0.4f else 1f),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private val BAR_HEIGHT = 60.dp
private val BUTTON_SIZE = 42.dp
private val TITLE_INSET = 100.dp
private val FADE_HEIGHT = 20.dp
