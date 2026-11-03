package com.naury.framekit.core.history

import com.naury.framekit.core.model.ProjectSnapshot

/**
 * 프로젝트 스냅샷의 불변 실행 취소/다시 실행 히스토리.
 *
 * 모든 연산은 새 인스턴스를 반환한다. 스냅샷은 [ProjectSnapshot.sameContentAs]로 비교하므로
 * 내용이 바뀌지 않은 커밋은 아무 동작도 하지 않고 revision도 올리지 않는다. 새 커밋은 이 히스토리에서
 * 한 번도 쓰이지 않은 revision을 받으므로, 실행 취소 후 다른 편집을 해도 revision 키 기반 캐시가
 * 올바르게 유지된다.
 *
 * 프로젝트 스냅샷만 저장한다. Bitmap이나 렌더 캐시는 절대 히스토리에 넣으면 안 된다.
 *
 * @property baseline 편집기가 열릴 때의 상태. [isDirty]는 실행 취소 스택 길이가 아니라 이 값과
 *   비교하므로, baseline까지 실행 취소하면 프로젝트가 다시 변경 없음 상태가 된다.
 * @property capacity 보관하는 최대 실행 취소 단계 수. 가장 오래된 단계부터 버린다.
 */
public class EditHistory<T : ProjectSnapshot<T>> private constructor(
    public val current: T,
    public val baseline: T,
    public val past: List<T>,
    public val future: List<T>,
    public val capacity: Int,
    private val highestRevision: Long,
) {
    public val canUndo: Boolean get() = past.isNotEmpty()
    public val canRedo: Boolean get() = future.isNotEmpty()
    public val isDirty: Boolean get() = !current.sameContentAs(baseline)

    /**
     * [next]를 현재 상태로 만들고 다시 실행 스택을 비운다.
     *
     * [next]가 [current]와 내용이 같으면 이 인스턴스를 그대로 반환한다.
     */
    public fun commit(next: T): EditHistory<T> {
        if (next.sameContentAs(current)) return this
        val revision = highestRevision + 1
        val newPast = (past + current).takeLast(capacity)
        return EditHistory(next.withRevision(revision), baseline, newPast, emptyList(), capacity, revision)
    }

    /** 이전 스냅샷으로 되돌린다. 실행 취소할 것이 없으면 이 인스턴스를 반환한다. */
    public fun undo(): EditHistory<T> {
        if (!canUndo) return this
        return EditHistory(past.last(), baseline, past.dropLast(1), listOf(current) + future, capacity, highestRevision)
    }

    /** 다음 스냅샷을 다시 적용한다. 다시 실행할 것이 없으면 이 인스턴스를 반환한다. */
    public fun redo(): EditHistory<T> {
        if (!canRedo) return this
        return EditHistory(future.first(), baseline, past + current, future.drop(1), capacity, highestRevision)
    }

    public companion object {
        /** 기본 실행 취소 단계 수. */
        public const val DEFAULT_CAPACITY: Int = 50

        /**
         * baseline과 현재 상태가 모두 [initial]인 히스토리를 시작한다.
         *
         * @throws IllegalArgumentException [capacity]가 양수가 아닐 때.
         */
        public fun <T : ProjectSnapshot<T>> start(initial: T, capacity: Int = DEFAULT_CAPACITY): EditHistory<T> {
            require(capacity > 0) { "History capacity must be positive: $capacity" }
            return EditHistory(initial, initial, emptyList(), emptyList(), capacity, initial.revision)
        }

        /**
         * 프로세스 종료 후 히스토리를 다시 만든다. [current]는 마지막으로 커밋된 스냅샷이고
         * [baseline]은 편집기가 처음 열릴 때의 손대지 않은 상태다. 실행 취소·다시 실행 스택은
         * 복원하지 않지만 [isDirty]는 여전히 원래 baseline과 비교한다.
         *
         * @throws IllegalArgumentException [capacity]가 양수가 아닐 때.
         */
        public fun <T : ProjectSnapshot<T>> restore(baseline: T, current: T, capacity: Int = DEFAULT_CAPACITY): EditHistory<T> {
            require(capacity > 0) { "History capacity must be positive: $capacity" }
            return EditHistory(current, baseline, emptyList(), emptyList(), capacity, maxOf(baseline.revision, current.revision))
        }
    }
}
