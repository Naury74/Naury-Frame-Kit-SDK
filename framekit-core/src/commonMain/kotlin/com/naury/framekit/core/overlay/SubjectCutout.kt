package com.naury.framekit.core.overlay

/**
 * 배경 제거: 원본의 배경을 투명하게 만든다.
 *
 * 피사체 마스크는 한 번만 계산해 프로젝트 에셋으로 저장하고 프로젝트에는 그 id만 남긴다. 그래서
 * 미리보기, 내보내기, 복원된 세션이 모두 같은 마스크를 쓰며, 누끼를 끄면 원본으로 돌아간다.
 *
 * @property maskAssetId 프로젝트 에셋 저장소에 있는 alpha 마스크 PNG의 id. 바로 세운 원본과
 *   정렬되어 있다.
 */
public data class SubjectCutout(val maskAssetId: String) {
    public companion object {
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}\\.png")

        public fun isValidAssetId(id: String): Boolean = ID_PATTERN.matches(id)
    }
}
