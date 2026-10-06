package com.naury.framekit.ui.layout

import android.app.Activity
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [activity]를 호스팅하는 창의 폴드 자세.
 *
 * composable이 아니라 Activity에서 수집한다. 에디터 안의 composition context는 Activity가 아닌
 * 로케일 래퍼일 수 있기 때문이다.
 */
public fun foldPostureFlow(activity: Activity): Flow<FoldPosture> =
    WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
        .map { info ->
            val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
            when {
                fold == null || fold.state != FoldingFeature.State.HALF_OPENED -> FoldPosture.Flat
                fold.orientation == FoldingFeature.Orientation.HORIZONTAL ->
                    FoldPosture.Tabletop(fold.bounds.top, fold.bounds.bottom)
                else -> FoldPosture.Book(fold.bounds.left, fold.bounds.right)
            }
        }
        .distinctUntilChanged()
