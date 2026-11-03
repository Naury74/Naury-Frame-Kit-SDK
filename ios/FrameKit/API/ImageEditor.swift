import SwiftUI
import UIKit

/// 사진 편집기 화면(UIKit). 전체 화면으로 띄우고, 끝나면 스스로 닫은 뒤 [completion]을 한 번 부른다.
///
/// ```swift
/// let editor = FrameKitImageEditorViewController(request: ImageEditorRequest(input: .pick)) { result in ... }
/// present(editor, animated: true)
/// ```
@MainActor
public final class FrameKitImageEditorViewController: UIViewController {
    private let model: EditorModel
    private var result: FrameKitResult?
    private let completion: (FrameKitResult) -> Void
    private var completed = false

    public init(request: ImageEditorRequest, completion: @escaping @MainActor (FrameKitResult) -> Void) {
        self.completion = completion
        var finish: ((FrameKitResult) -> Void)?
        model = EditorModel(request: request) { finish?($0) }
        super.init(nibName: nil, bundle: nil)
        finish = { [weak self] in self?.finished($0) }
        modalPresentationStyle = .fullScreen
        // 아래로 끌어 닫으면 결과 없이 사라지므로 막는다. 닫기는 편집기 단추로 한다.
        isModalInPresentation = true
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) { nil }

    override public func viewDidLoad() {
        super.viewDidLoad()
        let host = UIHostingController(rootView: EditorRoot(model: model))
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
        // 호스트가 직접 내린 경우에도 결과를 한 번 보낸다.
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

/// 사진 편집기(SwiftUI). 보통은 `frameKitImageEditor(isPresented:request:onResult:)`를 쓴다.
public struct FrameKitImageEditor: UIViewControllerRepresentable {
    private let request: ImageEditorRequest
    private let onResult: @MainActor (FrameKitResult) -> Void

    public init(request: ImageEditorRequest, onResult: @escaping @MainActor (FrameKitResult) -> Void) {
        self.request = request
        self.onResult = onResult
    }

    public func makeUIViewController(context _: Context) -> UIViewController {
        EmbeddedEditorController(request: request, onResult: onResult)
    }

    public func updateUIViewController(_: UIViewController, context _: Context) {}
}

/// SwiftUI 안에 넣는 편집기. 표시는 호스트가 맡으므로 스스로 닫지 않는다.
@MainActor
private final class EmbeddedEditorController: UIHostingController<AnyView> {
    private let model: EditorModel

    init(request: ImageEditorRequest, onResult: @escaping @MainActor (FrameKitResult) -> Void) {
        model = EditorModel(request: request) { onResult($0) }
        super.init(rootView: AnyView(EmptyView()))
        rootView = AnyView(EditorRoot(model: model))
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) { nil }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if isBeingDismissed || isMovingFromParent || parent?.isBeingDismissed == true { model.dismissedWithoutResult() }
    }
}

extension View {
    /// [isPresented]가 `true`가 되면 사진 편집기를 전체 화면으로 띄운다. 끝나면 닫고 [onResult]를 한 번 부른다.
    public func frameKitImageEditor(
        isPresented: Binding<Bool>,
        request: ImageEditorRequest,
        onResult: @escaping @MainActor (FrameKitResult) -> Void
    ) -> some View {
        fullScreenCover(isPresented: isPresented) {
            FrameKitImageEditor(request: request) { result in
                isPresented.wrappedValue = false
                onResult(result)
            }
            .ignoresSafeArea()
            .interactiveDismissDisabled()
        }
    }
}
