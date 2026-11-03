package com.naury.framekit.core.model

/**
 * 편집 히스토리에 저장할 수 있는 불변 프로젝트 상태.
 *
 * [revision]은 캐시 무효화와 내보내기 추적을 위해 커밋된 스냅샷 하나를 식별한다. 내용의 일부는
 * 아니므로, revision이 달라도 편집이 같은 두 스냅샷은 [sameContentAs]에서 같다.
 */
public interface ProjectSnapshot<T : ProjectSnapshot<T>> {
    public val revision: Long

    /** [revision]을 찍은 이 스냅샷의 사본을 반환한다. */
    public fun withRevision(revision: Long): T

    /** [revision]을 제외한 모든 편집 필드를 비교한다. */
    public fun sameContentAs(other: T): Boolean
}
