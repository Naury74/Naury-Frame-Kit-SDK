import CoreGraphics
import Foundation

extension EditorModel {
    /// 지금 보여 줄 도구. 배경 제거는 이 기기에서 될 때만 나온다.
    var visibleTools: [ImageTool] {
        tools.filter { $0 != .cutout || cutoutAvailable }
    }
}

extension EditorModel {

    var hasCutout: Bool { displayed.cutout != nil }

    func removeBackground() {
        guard let preview, !cutoutBusy else { return }
        cutoutBusy = true
        cutoutError = nil
        Task.detached(priority: .userInitiated) {
            do {
                let mask = try SubjectCutout.mask(for: preview.cgImage)
                await MainActor.run {
                    self.cutoutBusy = false
                    var edit = self.history.current
                    edit.cutout = mask
                    self.history.commit(edit)
                    self.requestRender()
                }
            } catch {
                await MainActor.run {
                    self.cutoutBusy = false
                    self.cutoutError = (error as? EditorError) ?? EditorError(code: .unknown)
                }
            }
        }
    }

    func restoreBackground() {
        var edit = history.current
        edit.cutout = nil
        history.commit(edit)
        requestRender()
    }
}
