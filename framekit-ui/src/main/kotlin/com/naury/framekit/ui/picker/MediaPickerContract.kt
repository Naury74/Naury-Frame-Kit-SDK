package com.naury.framekit.ui.picker

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import com.naury.framekit.android.input.MediaKind

/**
 * 사진·영상을 고른다.
 *
 * `framekit-picker` 모듈이 있고 [MediaPickerRequest.style]이 [PickerStyle.AUTO]면 앱 안 갤러리를 연다. 그렇지 않으면
 * 시스템 사진 선택기(Photo Picker, 없는 기기는 문서 선택 화면)를 연다. 편집기들도 원본을 고를 때 이 계약을 쓴다.
 *
 * ```
 * val picker = registerForActivityResult(MediaPickerContract()) { result -> ... }
 * picker.launch(MediaPickerRequest(MediaKind.IMAGE, maxItems = 10))
 * ```
 */
public class MediaPickerContract : ActivityResultContract<MediaPickerRequest, MediaPickerResult>() {

    override fun createIntent(context: Context, input: MediaPickerRequest): Intent {
        if (input.style == PickerStyle.AUTO && isInAppAvailable(context)) {
            return Intent().setComponent(ComponentName(context.packageName, IN_APP_ACTIVITY)).putExtra(EXTRA_REQUEST, input)
        }
        return systemIntent(context, input)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): MediaPickerResult {
        if (resultCode != Activity.RESULT_OK || intent == null) return MediaPickerResult.Cancelled
        // 앱 안 갤러리는 정보가 담긴 목록을, 시스템 선택기는 Uri만 돌려준다.
        val picked = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_RESULT, PickedMedia::class.java)
        val items = picked ?: systemUris(intent).map { PickedMedia(it) }
        if (items.isEmpty()) return MediaPickerResult.Cancelled
        return MediaPickerResult.Picked(items, intent.getIntExtra(EXTRA_SKIPPED, 0))
    }

    public companion object {
        /** 앱 안 갤러리 화면(`framekit-picker`)의 클래스 이름. */
        internal const val IN_APP_ACTIVITY: String = "com.naury.framekit.picker.MediaPickerActivity"

        /** 앱 안 갤러리로 넘기는 [MediaPickerRequest]. `framekit-picker` 모듈에서 쓴다. */
        public const val EXTRA_REQUEST: String = "com.naury.framekit.picker.REQUEST"

        /** 앱 안 갤러리가 돌려주는 `ArrayList<PickedMedia>`. `framekit-picker` 모듈에서 쓴다. */
        public const val EXTRA_RESULT: String = "com.naury.framekit.picker.RESULT"

        /** 제한에 맞지 않아 뺀 개수(Int). `framekit-picker` 모듈에서 쓴다. */
        public const val EXTRA_SKIPPED: String = "com.naury.framekit.picker.SKIPPED"

        /** 이 앱에 `framekit-picker` 모듈이 들어 있으면 `true`. */
        public fun isInAppAvailable(context: Context): Boolean = try {
            context.packageManager.getActivityInfo(ComponentName(context.packageName, IN_APP_ACTIVITY), 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        /**
         * 시스템 사진 선택기를 여는 Intent. [MediaPickerRequest.maxItems]가 2 이상이면 여러 개를 고른다.
         * 앱 안 갤러리가 권한이 없을 때 대신 열 때도 쓴다.
         */
        public fun systemIntent(context: Context, input: MediaPickerRequest): Intent {
            val request = PickVisualMediaRequest(input.kind.toPickerType())
            return if (input.maxItems > 1) {
                ActivityResultContracts.PickMultipleVisualMedia(maxOf(2, input.maxItems)).createIntent(context, request)
            } else {
                ActivityResultContracts.PickVisualMedia().createIntent(context, request)
            }
        }

        /** 시스템 선택기 결과 Intent에서 Uri를 순서대로 꺼낸다. */
        public fun systemUris(intent: Intent): List<Uri> {
            val clip = intent.clipData
            val uris = if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else listOfNotNull(intent.data)
            return uris.distinct()
        }

        private fun MediaKind.toPickerType(): ActivityResultContracts.PickVisualMedia.VisualMediaType = when (this) {
            MediaKind.IMAGE -> ActivityResultContracts.PickVisualMedia.ImageOnly
            MediaKind.VIDEO -> ActivityResultContracts.PickVisualMedia.VideoOnly
            MediaKind.ANY, MediaKind.AUDIO -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
        }
    }
}
