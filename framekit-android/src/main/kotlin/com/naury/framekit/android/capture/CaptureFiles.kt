package com.naury.framekit.android.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.android.output.AppFileOutputStore
import java.io.File
import java.util.UUID

/**
 * 카메라 앱이 찍은 사진·영상을 받을 임시 파일.
 *
 * 파일은 앱 캐시의 `framekit/captures/`에 두고, 촬영 Intent에는 SDK FileProvider Uri로 쓰기 권한만 넘긴다.
 * 편집이 끝나면 지우고, 중간에 프로세스가 끝나 남은 파일은 [deleteStale]이 정리한다.
 */
public object CaptureFiles {

    /** 촬영 결과를 받을 빈 파일과 카메라 앱에 넘길 Uri. */
    public fun create(context: Context, kind: MediaKind): Pair<File, Uri> {
        require(kind == MediaKind.IMAGE || kind == MediaKind.VIDEO) { "Capture needs IMAGE or VIDEO" }
        val directory = directory(context)
        if (!directory.isDirectory && !directory.mkdirs()) error("Could not create capture directory")
        val file = File(directory, "${UUID.randomUUID()}.${if (kind == MediaKind.VIDEO) "mp4" else "jpg"}")
        val uri = FileProvider.getUriForFile(context, AppFileOutputStore.authorityFor(context), file)
        return file to uri
    }

    /** 카메라 앱이 실제로 파일을 채웠으면 `true`. 취소하면 0바이트로 남는다. */
    public fun isFilled(file: File): Boolean = file.isFile && file.length() > 0

    public fun delete(path: String?) {
        if (path == null) return
        runCatching { File(path).takeIf { it.parentFile?.name == DIRECTORY_NAME }?.delete() }
    }

    /** 하루가 지난 임시 촬영 파일을 지운다. */
    public fun deleteStale(context: Context, maxAgeMillis: Long = DAY_MILLIS) {
        val now = System.currentTimeMillis()
        directory(context).listFiles()?.forEach { file -> if (now - file.lastModified() > maxAgeMillis) file.delete() }
    }

    /**
     * 촬영 전에 CAMERA 권한을 요청해야 하면 `true`.
     *
     * 앱이 manifest에 CAMERA를 선언하지 않았다면 카메라 앱 호출에 권한이 필요 없다. 선언했는데 허용되지
     * 않았다면 시스템이 촬영 Intent를 막으므로 먼저 요청해야 한다.
     */
    public fun needsCameraPermission(context: Context): Boolean {
        val declared = runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
            info.requestedPermissions?.contains(Manifest.permission.CAMERA) == true
        }.getOrDefault(false)
        return declared && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
    }

    private fun directory(context: Context) = File(context.cacheDir, "framekit/$DIRECTORY_NAME")

    private const val DIRECTORY_NAME = "captures"
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
}
