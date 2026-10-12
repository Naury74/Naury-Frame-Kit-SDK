package com.naury.framekit.ui.video.timeline

import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.video.TimeRangeUs

/** 타임라인에 그릴 클립. 시간은 모두 µs다. */
internal data class TimelineClip(
    val id: String,
    val source: SourceId,
    val sourceRange: TimeRangeUs,
    val speed: Double,
    val outputStartUs: Long,
    val outputDurationUs: Long,
    val frameAspect: Float,
)

/** 텍스트·스티커·가리기 줄의 항목 종류. */
internal enum class TimelineItemKind { OVERLAY, MASK, MUSIC }

/** 출력 시간 [range] 동안 보이는 텍스트·스티커·마스크. */
internal data class TimelineItem(val kind: TimelineItemKind, val id: String, val range: TimeRangeUs, val selected: Boolean)
