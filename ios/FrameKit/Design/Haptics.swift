import UIKit

/// 햅틱. 꺼져 있으면 아무것도 하지 않는다.
@MainActor
final class Haptics: Sendable {
    let enabled: Bool

    nonisolated init(enabled: Bool) {
        self.enabled = enabled
    }

    /// 눈금 하나.
    func tick() {
        guard enabled else { return }
        UISelectionFeedbackGenerator().selectionChanged()
    }

    /// 큰 눈금·기준값·끝.
    func mark() {
        guard enabled else { return }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }

    func selection() {
        guard enabled else { return }
        UISelectionFeedbackGenerator().selectionChanged()
    }
}
