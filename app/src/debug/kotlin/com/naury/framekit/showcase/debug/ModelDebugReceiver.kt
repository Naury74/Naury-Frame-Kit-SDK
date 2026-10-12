package com.naury.framekit.showcase.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.common.Feature
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.android.gms.common.moduleinstall.ModuleInstall

/**
 * debug 빌드 전용. 배경 제거 모델(Play 서비스 선택 모듈)의 설치 여부를 보거나 해제를 요청한다. 결과는 logcat `FrameKitDebug` 태그.
 *
 * ML Kit 클라이언트를 만들면 모델 코드가 메모리에 올라와 Play 서비스가 "사용 중"으로 보고 삭제를 미룬다. 그래서 모듈의 기능
 * 이름(`mlkit.segmentation.subject`)만 담은 가벼운 [OptionalModuleApi]로 묻는다.
 */
class ModelDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val op = intent.getStringExtra("op") ?: "status"
        val pending = goAsync()
        val client = ModuleInstall.getClient(context.applicationContext)
        val task = when (op) {
            "release" -> client.releaseModules(SubjectSegmentationModule).continueWithTask { client.areModulesAvailable(SubjectSegmentationModule) }
            else -> client.areModulesAvailable(SubjectSegmentationModule)
        }
        task.addOnCompleteListener { result ->
            if (result.isSuccessful) {
                Log.i(TAG, "op=$op available=${result.result.areModulesAvailable()} status=${result.result.availabilityStatus}")
            } else {
                Log.w(TAG, "op=$op failed", result.exception)
            }
            pending.finish()
        }
    }

    private object SubjectSegmentationModule : OptionalModuleApi {
        override fun getOptionalFeatures(): Array<Feature> = arrayOf(Feature("mlkit.segmentation.subject", 1L))
    }

    private companion object {
        const val TAG = "FrameKitDebug"
    }
}
