import SwiftUI
import UIKit

/// 녹음 시트로 띄운 UIKit 화면의 높이와 끌어 닫기를 맡는다.
///
/// 끌어 닫기는 닫을 수 있고 녹음 중이 아닐 때만 바로 받는다. 녹음 중이면 시트를 되돌리고 호스트 닫기 흐름(녹음을 버리고 `.cancelled`)으로 닫는다.
@MainActor
final class RecorderSheetPresenter: NSObject, UIAdaptivePresentationControllerDelegate {
    /// 내용 높이를 재기 전에 쓰는 높이.
    static let fallbackHeight: CGFloat = 360

    private weak var controller: UIViewController?
    private weak var target: (any HostCloseTarget)?
    private let control: FrameKitEditorControl
    private let recording: () -> Bool
    private var height: CGFloat = 0

    init(controller: UIViewController, target: any HostCloseTarget, control: FrameKitEditorControl, recording: @escaping () -> Bool) {
        self.controller = controller
        self.target = target
        self.control = control
        self.recording = recording
        super.init()
        controller.modalPresentationStyle = .pageSheet
        controller.isModalInPresentation = false
        if let sheet = controller.sheetPresentationController {
            sheet.prefersGrabberVisible = true
            if #available(iOS 16, *) {
                sheet.detents = [.custom(identifier: .init("framekit.recorder")) { [weak self] context in
                    MainActor.assumeIsolated {
                        min(self.map { $0.height > 0 ? $0.height : Self.fallbackHeight } ?? Self.fallbackHeight, context.maximumDetentValue)
                    }
                }]
            } else {
                sheet.detents = [.medium()]
            }
        }
        controller.presentationController?.delegate = self
    }

    /// 녹음 화면의 맞는 높이. 커질 때만 시트를 다시 맞춘다.
    func contentHeight(_ value: CGFloat) {
        guard value > height + 0.5 else { return }
        height = value
        guard #available(iOS 16, *), let sheet = controller?.sheetPresentationController else { return }
        sheet.animateChanges { sheet.invalidateDetents() }
    }

    func presentationControllerShouldDismiss(_: UIPresentationController) -> Bool {
        (target?.hostCanDismiss ?? false) && !recording()
    }

    func presentationControllerDidAttemptToDismiss(_: UIPresentationController) {
        target?.hostRequestClose()
        control.refresh()
    }
}

/// 녹음 시트(SwiftUI). 편집기 상태를 시트가 갖고, 시트가 사라지면 결과 없이 닫힌 것으로 끝낸다.
///
/// 끌어 닫기는 닫을 수 있을 때만 받는다. 녹음 중에 끌어 닫으면 녹음을 버리고 `.cancelled`로 끝난다.
struct RecorderSheetContent<Model: ObservableObject & HostCloseTarget, Root: View>: View {
    @StateObject private var model: Model
    @StateObject private var ownControl = FrameKitEditorControl()
    private let control: FrameKitEditorControl?
    private let dismissed: (Model) -> Void
    private let root: (Model) -> Root

    /// [make]는 결과를 받을 함수를 받아 편집기 상태를 만든다. [dismissed]는 시트가 사라질 때 부른다.
    init(control: FrameKitEditorControl?, make: @escaping () -> Model, dismissed: @escaping (Model) -> Void, root: @escaping (Model) -> Root) {
        _model = StateObject(wrappedValue: make())
        self.control = control
        self.dismissed = dismissed
        self.root = root
    }

    var body: some View {
        let active = control ?? ownControl
        RecorderSheetBody(control: active, content: root(model))
            .onAppear { active.attach(model) }
            .onDisappear {
                dismissed(model)
                active.detach(model)
            }
    }
}

private struct RecorderSheetBody<Content: View>: View {
    @ObservedObject var control: FrameKitEditorControl
    let content: Content
    @State private var height: CGFloat = 0

    var body: some View {
        let sized = content
            .environment(\.recorderSheetHeight) { [binding = $height] value in
                if value > binding.wrappedValue + 0.5 { binding.wrappedValue = value }
            }
            .interactiveDismissDisabled(!control.canDismissInteractively)
        if #available(iOS 16, *) {
            sized
                .presentationDetents([.height(height > 0 ? height : RecorderSheetPresenter.fallbackHeight)])
                .presentationDragIndicator(.visible)
        } else {
            sized
        }
    }
}
