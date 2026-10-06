package com.naury.framekit.ui.design

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import java.util.Locale

/**
 * Resolves editor strings in [localeTag] instead of the device locale. `null` keeps the device
 * locale. Only the editor's composition is affected; the host's locale stays untouched.
 */
// 언어별 split을 쓰는 App Bundle에서는 기기 언어가 아닌 리소스가 빠질 수 있다. 호스트가 localeTag를 쓸 때
// bundle language split을 끄도록 integration 문서에 안내한다.
@SuppressLint("AppBundleLocaleChanges")
@Composable
public fun ProvideEditorLocale(localeTag: String?, content: @Composable () -> Unit) {
    if (localeTag == null) {
        content()
        return
    }
    val context = LocalContext.current
    val baseConfiguration = LocalConfiguration.current
    val localized = remember(localeTag, baseConfiguration) {
        val configuration = Configuration(baseConfiguration).apply { setLocale(Locale.forLanguageTag(localeTag)) }
        context.createConfigurationContext(configuration)
    }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
        LocalResources provides localized.resources,
        content = content,
    )
}
