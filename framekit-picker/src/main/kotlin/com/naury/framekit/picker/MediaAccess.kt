package com.naury.framekit.picker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.naury.framekit.android.input.MediaKind

/** 사진·영상 읽기 권한 상태. */
internal enum class Access {
    /** 모든 사진·영상을 읽을 수 있다. */
    FULL,

    /** Android 14 이상에서 사용자가 고른 일부만 읽을 수 있다. */
    PARTIAL,

    /** 읽을 수 없다. */
    NONE,
}

/**
 * 앱 안 갤러리에 필요한 권한을 따진다. SDK는 권한을 선언하지 않으므로 호스트 manifest에 있는 권한만 요청한다.
 *
 * - Android 13 이상: `READ_MEDIA_IMAGES`·`READ_MEDIA_VIDEO`(고를 종류에 맞게). Android 14 이상에서
 *   `READ_MEDIA_VISUAL_USER_SELECTED`도 선언했으면 함께 요청해 "일부만 허용"을 받을 수 있다.
 * - Android 12 이하: `READ_EXTERNAL_STORAGE`.
 */
internal object MediaAccess {

    /** 모든 항목을 읽는 데 필요한 권한. */
    fun fullPermissions(kind: MediaKind, sdk: Int = Build.VERSION.SDK_INT): List<String> = when {
        sdk >= Build.VERSION_CODES.TIRAMISU -> buildList {
            if (kind != MediaKind.VIDEO) add(READ_MEDIA_IMAGES)
            if (kind != MediaKind.IMAGE) add(READ_MEDIA_VIDEO)
        }
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** 호스트가 [fullPermissions]를 모두 선언했으면 `true`. 아니면 앱 안 갤러리를 쓰지 않는다. */
    fun declared(context: Context, kind: MediaKind): Boolean {
        val requested = requestedPermissions(context)
        return fullPermissions(kind).all { it in requested }
    }

    /** 요청할 권한. 선언한 것만 담는다. */
    fun requestable(context: Context, kind: MediaKind): Array<String> {
        val requested = requestedPermissions(context)
        val wanted = fullPermissions(kind) + listOfNotNull(USER_SELECTED.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE })
        return wanted.filter { it in requested }.toTypedArray()
    }

    fun current(context: Context, kind: MediaKind): Access {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (fullPermissions(kind).all(::granted)) return Access.FULL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && granted(USER_SELECTED)) return Access.PARTIAL
        return Access.NONE
    }

    private fun requestedPermissions(context: Context): Set<String> = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        info.requestedPermissions.orEmpty().toSet()
    } catch (_: PackageManager.NameNotFoundException) {
        emptySet()
    }

    // API 33·34에서 생긴 권한이라 상수 대신 이름을 쓴다(아래 버전에서는 요청하지 않는다).
    private const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    private const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    private const val USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
}
