package com.naury.framekit.core.validation

import com.naury.framekit.core.model.MediaType
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.model.SourceMetadata
import com.naury.framekit.core.overlay.MaskShape
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.core.video.Timeline
import com.naury.framekit.core.video.TimelineTimeMapper
import com.naury.framekit.core.video.VideoClip
import com.naury.framekit.core.video.VideoProject

/** Checks that a [VideoProject] can be played and exported with the given sources. */
public object VideoProjectValidator {

    /**
     * @param sources metadata of every source the project refers to.
     * @param minClipOutputDurationUs shortest allowed clip in output time, after speed.
     */
    public fun validate(project: VideoProject, sources: Map<SourceId, SourceMetadata>, minClipOutputDurationUs: Long): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        val timeline = project.timeline
        if (timeline.videoClips.isEmpty()) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "timeline.videoClips", "At least one clip is required")
        }
        val ids = timeline.videoClips.map { it.id } + timeline.audioClips.map { it.id } +
            timeline.overlays.map { it.overlay.id } + timeline.privacyMasks.map { it.mask.id }
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { id ->
            issues += ValidationIssue(ValidationCode.DUPLICATE_ID, "timeline", "Duplicate id $id")
        }
        timeline.videoClips.forEachIndexed { index, clip -> validateClip(clip, "timeline.videoClips[$index]", sources, minClipOutputDurationUs, issues) }
        timeline.audioClips.forEachIndexed { index, audio ->
            val path = "timeline.audioClips[$index]"
            val metadata = sources[audio.source]
            if (metadata == null || !metadata.hasAudio) {
                issues += ValidationIssue(ValidationCode.SOURCE_MISMATCH, "$path.source", "Source has no audio")
            } else {
                validateRange(audio.sourceRange, metadata.durationUs ?: 0L, "$path.sourceRange", issues)
            }
            if (audio.timelineStartUs < 0) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.timelineStartUs", "Must not be negative")
            if (!audio.volume.isFinite() || audio.volume !in 0.0..VideoClip.MAX_VOLUME) {
                issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.volume", "Expected 0..2")
            }
        }
        val duration = TimelineTimeMapper.durationUs(timeline)
        (timeline.overlays.map { it.range } + timeline.privacyMasks.map { it.range }).forEachIndexed { index, range ->
            if (range.isEmpty || range.startUs < 0 || range.endExclusiveUs > duration) {
                issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "timeline.timed[$index].range", "Must lie inside the timeline")
            }
        }
        OverlayValidator.validate(timeline.overlays.map { it.overlay }, emptyList(), issues)
        OverlayValidator.validatePrivacy(timeline.privacyMasks.map { it.mask }, issues)
        if (timeline.privacyMasks.size > Timeline.MAX_PRIVACY_MASKS) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "timeline.privacyMasks", "At most ${Timeline.MAX_PRIVACY_MASKS} masks")
        }
        timeline.privacyMasks.forEachIndexed { index, timed ->
            if (timed.mask.shape is MaskShape.Brush) {
                issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "timeline.privacyMasks[$index].shape", "Brush masks are image-only")
            }
        }
        return ValidationResult.of(issues)
    }

    private fun validateClip(
        clip: VideoClip,
        path: String,
        sources: Map<SourceId, SourceMetadata>,
        minDurationUs: Long,
        issues: MutableList<ValidationIssue>,
    ) {
        val metadata = sources[clip.source]
        if (metadata == null || metadata.mediaType != MediaType.VIDEO) {
            issues += ValidationIssue(ValidationCode.SOURCE_MISMATCH, "$path.source", "Not a registered video source")
            return
        }
        validateRange(clip.sourceRange, metadata.durationUs ?: 0L, "$path.sourceRange", issues)
        if (!clip.speed.isFinite() || clip.speed !in VideoClip.MIN_SPEED..VideoClip.MAX_SPEED) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.speed", "Expected 0.25..4")
            return
        }
        if (!clip.volume.isFinite() || clip.volume !in 0.0..VideoClip.MAX_VOLUME) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.volume", "Expected 0..2")
        }
        if (!clip.sourceRange.isEmpty && clip.outputDurationUs < minDurationUs) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.duration", "Clip is shorter than ${minDurationUs}us after speed")
        }
        val geometryIssues = mutableListOf<ValidationIssue>()
        ImageProjectValidator.validateGeometry(clip.effects.geometry, geometryIssues, "$path.geometry")
        if (geometryIssues.isEmpty() && metadata.uprightSize.isValid) {
            ImageProjectValidator.validateCropArea(clip.effects.geometry, metadata.uprightSize, "$path.geometry", geometryIssues)
        }
        issues += geometryIssues
        ImageProjectValidator.validateEffects(clip.effects.adjustments, clip.effects.filter, "$path.", issues)
    }

    private fun validateRange(range: TimeRangeUs, sourceDurationUs: Long, path: String, issues: MutableList<ValidationIssue>) {
        if (range.startUs < 0 || range.isEmpty || range.endExclusiveUs > sourceDurationUs) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, path, "Range must be non-empty and inside the source duration")
        }
    }
}
