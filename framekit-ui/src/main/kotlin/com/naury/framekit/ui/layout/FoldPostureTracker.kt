package com.naury.framekit.ui.layout

import android.app.Activity
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Fold posture of the window that hosts [activity].
 *
 * Collect it in the Activity rather than in a composable: inside the editor the composition context
 * may be a locale wrapper that is not an Activity.
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
