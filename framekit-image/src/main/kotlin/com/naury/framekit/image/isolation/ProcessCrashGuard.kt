package com.naury.framekit.image.isolation

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import java.util.concurrent.ConcurrentHashMap

/**
 * 분리 프로세스에서 돌던 기능이 이 기기에서 프로세스를 죽인 적이 있는지 기억한다. 기능마다 [feature] 이름으로 따로 둔다.
 *
 * 앱이나 OS가 업데이트되면(모델·드라이버가 바뀌었을 수 있으므로) 기록을 무시하고 다시 시도하게 한다.
 */
public class ProcessCrashGuard(context: Context, private val feature: String) {

    private val appContext = context.applicationContext

    /** 이 빌드(앱 버전 + OS 빌드)에서 [feature]가 프로세스를 죽인 적이 있으면 `true`. */
    public val isBlocked: Boolean
        get() = cache.getOrPut(feature) { prefs().getString(feature, null) == buildKey() }

    /** 지금 빌드에서 [feature]를 쓰지 않도록 기록한다. */
    public fun block() {
        prefs().edit { putString(feature, buildKey()) }
        cache[feature] = true
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun buildKey(): String {
        val version = try {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (_: PackageManager.NameNotFoundException) {
            0L
        }
        return "$version|${Build.FINGERPRINT}"
    }

    private companion object {
        const val PREFS = "framekit_isolation"
        val cache = ConcurrentHashMap<String, Boolean>()
    }
}
