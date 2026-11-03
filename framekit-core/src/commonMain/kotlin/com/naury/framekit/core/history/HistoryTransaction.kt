package com.naury.framekit.core.history

import com.naury.framekit.core.model.ProjectSnapshot

/**
 * 히스토리와, 슬라이더 드래그·핀치·자르기 초안 같은 진행 중인 변경(선택)을 함께 담는다.
 *
 * 제스처는 `begin → update* → commit` 또는 `begin → update* → cancel`이다. update는 [displayed]만
 * 바꾸며, 히스토리에는 제스처를 커밋할 때 최대 한 항목이 추가되고 취소하면 아무것도 추가되지 않는다.
 */
public data class HistoryTransaction<T : ProjectSnapshot<T>>(
    val history: EditHistory<T>,
    val draft: T? = null,
) {
    /** 미리보기가 렌더링할 상태. 제스처가 진행 중이면 초안, 아니면 현재 스냅샷이다. */
    public val displayed: T get() = draft ?: history.current

    public val isActive: Boolean get() = draft != null

    /** 현재 스냅샷에서 제스처를 시작한다. 진행 중인 제스처를 다시 시작하면 초안을 유지한다. */
    public fun begin(): HistoryTransaction<T> = if (isActive) this else copy(draft = history.current)

    /** 초안을 교체한다. 진행 중인 제스처가 없으면 암묵적으로 시작한다. */
    public fun update(value: T): HistoryTransaction<T> = copy(draft = value)

    /** 초안을 히스토리 한 항목으로 커밋하고 제스처를 끝낸다. */
    public fun commit(): HistoryTransaction<T> {
        val value = draft ?: return this
        return HistoryTransaction(history.commit(value))
    }

    /** 초안을 버리고 [begin] 이전 상태로 돌아간다. */
    public fun cancel(): HistoryTransaction<T> = copy(draft = null)

    /** 초안이 히스토리 탐색과 섞이지 않도록 실행 취소는 제스처 사이에서만 적용한다. */
    public fun undo(): HistoryTransaction<T> = if (isActive) this else copy(history = history.undo())

    public fun redo(): HistoryTransaction<T> = if (isActive) this else copy(history = history.redo())

    public companion object {
        public fun <T : ProjectSnapshot<T>> start(
            initial: T,
            capacity: Int = EditHistory.DEFAULT_CAPACITY,
        ): HistoryTransaction<T> = HistoryTransaction(EditHistory.start(initial, capacity))
    }
}
