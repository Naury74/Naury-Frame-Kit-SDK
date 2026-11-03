/// 실행 취소 기록. 기준 상태와 비교해 바뀌었는지 판단하므로 처음까지 되돌리면 다시 '안 바뀜'이 된다.
struct EditHistory {
    static let capacity = 50

    private(set) var current: PhotoEdit
    private(set) var baseline: PhotoEdit
    private var past: [PhotoEdit] = []
    private var future: [PhotoEdit] = []

    init(_ initial: PhotoEdit) {
        current = initial
        baseline = initial
    }

    var canUndo: Bool { !past.isEmpty }
    var canRedo: Bool { !future.isEmpty }
    var isDirty: Bool { !current.sameContent(as: baseline) }

    /// 내용이 같으면 기록하지 않는다.
    mutating func commit(_ next: PhotoEdit) {
        guard !next.sameContent(as: current) else { return }
        past.append(current)
        if past.count > Self.capacity { past.removeFirst(past.count - Self.capacity) }
        current = next
        future.removeAll()
    }

    mutating func undo() {
        guard let previous = past.popLast() else { return }
        future.append(current)
        current = previous
    }

    mutating func redo() {
        guard let next = future.popLast() else { return }
        past.append(current)
        current = next
    }
}
