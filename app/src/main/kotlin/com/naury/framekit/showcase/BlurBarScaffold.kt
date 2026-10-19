package com.naury.framekit.showcase

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.naury.kinetica.ui.GlassStyles
import com.naury.kinetica.ui.KTopAppBar
import com.naury.kinetica.ui.KineticaScaffold
import com.naury.kinetica.ui.KineticaTheme
import com.naury.kinetica.ui.SurfaceStyle
import com.naury.kinetica.ui.rememberKTopAppBarState

/**
 * 목록이 상단 바 아래로 지나가며 블러로 비치는 화면 골격.
 *
 * 블러를 그리려면 골격 표면이 글래스여야 해서 골격과 상단 바에만 글래스를 주고, 목록 안 부품은 테마의 단색 표면을 그대로 쓴다.
 * 콘텐츠 바탕은 단색 표면의 바탕색으로 칠해진다. 바 색은 갤러리 화면과 같게 앱 바탕색에 맞춘다.
 * Android 12 미만은 블러 대신 거의 불투명한 바탕색으로 칠한다. [content]가 받는 여백에는 상단 바 높이가 들어 있다.
 */
@Composable
fun BlurBarScaffold(title: String, listState: LazyListState, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    val solid = KineticaTheme.surface
    val background = MaterialTheme.colorScheme.background
    val glass = remember(solid.isDark, background) {
        val base = if (solid.isDark) GlassStyles.BarDark else GlassStyles.Bar
        SurfaceStyle.Glass(
            base.copy(
                tint = background.copy(alpha = base.tint.alpha),
                fallback = background.copy(alpha = base.fallback.alpha),
            ),
        )
    }
    KineticaScaffold(
        surface = glass,
        contentSurface = solid,
        topBar = { KTopAppBar(title, state = rememberKTopAppBarState(listState), onBack = onBack, surface = glass) },
        // 시스템 바 아이콘 색은 MainActivity가 앱 테마에 맞춰 정한다.
        systemBarIcons = false,
        content = content,
    )
}
