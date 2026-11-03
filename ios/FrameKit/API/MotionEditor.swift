import SwiftUI
import UIKit

/// 움직이는 이미지 편집기 화면(UIKit). 전체 화면으로 띄우고, 끝나면 스스로 닫은 뒤 [completion]을 한 번 부른다.
@MainActor
public final class FrameKitMotionEditorViewController: UIViewController {
    private let model: MotionEditorModel
    private var result: FrameKitResult?
    private let completion: (FrameKitResult) -> Void
    private var completed = false

    public init(request: MotionEditorRequest, completion: @escaping @MainActor (FrameKitResult) -> Void) {
        self.completion = completion
        var finish: ((FrameKitResult) -> Void)?
        model = MotionEditorModel(request: request, session: EditSession(slot: "motion", input: request.input)) { finish?($0) }
        super.init(nibName: nil, bundle: nil)
        finish = { [weak self] in self?.finished($0) }
        modalPresentationStyle = .fullScreen
        isModalInPresentation = true
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) { nil }

    override public func viewDidLoad() {
        super.viewDidLoad()
        let host = UIHostingController(rootView: MotionEditorRoot(model: model))
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        host.view.backgroundColor = .clear
        view.addSubview(host.view)
        host.didMove(toParent: self)
    }

    override public var preferredStatusBarStyle: UIStatusBarStyle {
        switch model.request.ui.themeMode {
        case .dark: .lightContent
        case .light: .darkContent
        case .system: .default
        }
    }

    override public func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if isBeingDismissed || isMovingFromParent || presentingViewController == nil {
            model.dismissedWithoutResult()
            deliver()
        }
    }

    private func finished(_ result: FrameKitResult) {
        self.result = result
        if let presenting = presentingViewController, !isBeingDismissed {
            presenting.dismiss(animated: true) { [weak self] in self?.deliver() }
        } else {
            deliver()
        }
    }

    private func deliver() {
        guard !completed, let result else { return }
        completed = true
        completion(result)
    }
}

/// 움직이는 이미지 편집기(SwiftUI). 보통은 `frameKitMotionEditor(isPresented:request:onResult:)`를 쓴다.
public struct FrameKitMotionEditor: UIViewControllerRepresentable {
    private let request: MotionEditorRequest
    private let onResult: @MainActor (FrameKitResult) -> Void

    public init(request: MotionEditorRequest, onResult: @escaping @MainActor (FrameKitResult) -> Void) {
        self.request = request
        self.onResult = onResult
    }

    public func makeUIViewController(context _: Context) -> UIViewController {
        EmbeddedMotionController(request: request, onResult: onResult)
    }

    public func updateUIViewController(_: UIViewController, context _: Context) {}
}

@MainActor
private final class EmbeddedMotionController: UIHostingController<AnyView> {
    private let model: MotionEditorModel

    init(request: MotionEditorRequest, onResult: @escaping @MainActor (FrameKitResult) -> Void) {
        model = MotionEditorModel(request: request, session: EditSession(slot: "motion", input: request.input)) { onResult($0) }
        super.init(rootView: AnyView(EmptyView()))
        rootView = AnyView(MotionEditorRoot(model: model))
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) { nil }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if isBeingDismissed || isMovingFromParent || parent?.isBeingDismissed == true { model.dismissedWithoutResult() }
    }
}

extension View {
    /// [isPresented]가 `true`가 되면 움직이는 이미지 편집기를 전체 화면으로 띄운다. 끝나면 닫고 [onResult]를 한 번 부른다.
    public func frameKitMotionEditor(
        isPresented: Binding<Bool>,
        request: MotionEditorRequest,
        onResult: @escaping @MainActor (FrameKitResult) -> Void
    ) -> some View {
        fullScreenCover(isPresented: isPresented) {
            FrameKitMotionEditor(request: request) { result in
                isPresented.wrappedValue = false
                onResult(result)
            }
            .ignoresSafeArea()
            .interactiveDismissDisabled()
        }
    }
}
