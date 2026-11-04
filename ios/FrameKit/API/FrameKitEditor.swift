import SwiftUI
import UIKit

/// FrameKit 진입점 화면(UIKit). 전체 화면으로 띄우고, 끝나면 스스로 닫은 뒤 [completion]을 한 번 부른다.
@MainActor
public final class FrameKitViewController: UIViewController {
    private let model: RouterModel
    private var result: FrameKitResult?
    private let completion: (FrameKitResult) -> Void
    private var completed = false

    public init(request: FrameKitRequest, completion: @escaping @MainActor (FrameKitResult) -> Void) {
        self.completion = completion
        var finish: ((FrameKitResult) -> Void)?
        model = RouterModel(request: request) { finish?($0) }
        super.init(nibName: nil, bundle: nil)
        finish = { [weak self] in self?.finished($0) }
        modalPresentationStyle = .fullScreen
        isModalInPresentation = true
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) { nil }

    override public func viewDidLoad() {
        super.viewDidLoad()
        let host = UIHostingController(rootView: RouterRoot(model: model))
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
        // 호스트가 직접 내린 경우에도 결과를 한 번 보낸다. 위에 다른 화면을 띄우거나 탭을 바꾼 것은 내린 것이 아니다.
        frameKitWatchRemoval(self) { [weak self] in
            self?.model.dismissedWithoutResult()
            self?.deliver()
        }
    }

    private func finished(_ result: FrameKitResult) {
        self.result = result
        frameKitClose(self) { [weak self] in self?.deliver() }
    }

    private func deliver() {
        guard !completed, let result else { return }
        completed = true
        completion(result)
    }
}

/// FrameKit 진입점(SwiftUI). 보통은 `frameKitEditor(isPresented:request:onResult:)`를 쓴다.
public struct FrameKitEditor: UIViewControllerRepresentable {
    private let request: FrameKitRequest
    private let onResult: @MainActor (FrameKitResult) -> Void

    public init(request: FrameKitRequest, onResult: @escaping @MainActor (FrameKitResult) -> Void) {
        self.request = request
        self.onResult = onResult
    }

    public func makeUIViewController(context _: Context) -> UIViewController {
        EmbeddedRouterController(request: request, onResult: onResult)
    }

    public func updateUIViewController(_: UIViewController, context _: Context) {}

    public static func dismantleUIViewController(_ controller: UIViewController, coordinator _: ()) {
        (controller as? EmbeddedRouterController)?.dismantled()
    }
}

@MainActor
private final class EmbeddedRouterController: UIHostingController<AnyView> {
    private let model: RouterModel

    init(request: FrameKitRequest, onResult: @escaping @MainActor (FrameKitResult) -> Void) {
        model = RouterModel(request: request) { onResult($0) }
        super.init(rootView: AnyView(EmptyView()))
        rootView = AnyView(RouterRoot(model: model))
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) { nil }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if frameKitIsLeaving(self) { model.dismissedWithoutResult() }
    }

    /// SwiftUI가 이 화면을 뺐다(내비게이션 뒤로 가기·조건부 표시 해제 등).
    func dismantled() {
        model.dismissedWithoutResult()
    }
}

extension View {
    /// [isPresented]가 `true`가 되면 FrameKit 진입점을 전체 화면으로 띄운다. 끝나면 닫고 [onResult]를 한 번 부른다.
    public func frameKitEditor(
        isPresented: Binding<Bool>,
        request: FrameKitRequest,
        onResult: @escaping @MainActor (FrameKitResult) -> Void
    ) -> some View {
        fullScreenCover(isPresented: isPresented) {
            FrameKitEditor(request: request) { result in
                isPresented.wrappedValue = false
                onResult(result)
            }
            .ignoresSafeArea()
            .interactiveDismissDisabled()
        }
    }
}

/// 이 화면이나 조상이 내려지거나 빠지는 중이면 `true`.
@MainActor
func frameKitIsLeaving(_ controller: UIViewController) -> Bool {
    var current: UIViewController? = controller
    while let vc = current {
        if vc.isBeingDismissed || vc.isMovingFromParent { return true }
        current = vc.parent
    }
    return false
}

/// 화면이 사라진 뒤 호스트가 치운 것이면 [removed]를 부른다.
@MainActor
func frameKitWatchRemoval(_ controller: UIViewController, removed: @escaping @MainActor () -> Void) {
    if frameKitIsLeaving(controller) { removed(); return }
    // 아래 화면에서 한꺼번에 내리거나 창의 첫 화면을 바꾸면 위 표시가 없을 수 있어 다음 차례에 다시 본다.
    DispatchQueue.main.async { [weak controller] in
        guard let controller else { return }
        if controller.view.window == nil && controller.parent == nil && controller.presentingViewController == nil { removed() }
    }
}

/// 띄운 화면이면 내리고, 내비게이션에 쌓인 화면이면 뺀다. 그 뒤 [completion]을 부른다.
@MainActor
func frameKitClose(_ controller: UIViewController, completion: @escaping @MainActor () -> Void) {
    if let navigation = controller.navigationController, navigation.viewControllers.count > 1,
       navigation.viewControllers.first !== controller, navigation.topViewController === controller {
        navigation.popViewController(animated: true)
        completion()
    } else if let presenting = controller.presentingViewController, !controller.isBeingDismissed {
        presenting.dismiss(animated: true) { completion() }
    } else {
        completion()
    }
}
