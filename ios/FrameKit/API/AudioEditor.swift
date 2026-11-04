import SwiftUI
import UIKit

/// 음성 편집기 화면(UIKit). 전체 화면으로 띄우고, 끝나면 스스로 닫은 뒤 [completion]을 한 번 부른다.
@MainActor
public final class FrameKitAudioEditorViewController: UIViewController {
    private let model: AudioEditorModel
    private var result: FrameKitResult?
    private let completion: (FrameKitResult) -> Void
    private var completed = false
    /// 호스트 닫기 연결점. 화면 가장자리 밀어 닫기 같은 호스트 몸짓을 여기에 잇는다.
    public let control: FrameKitEditorControl
    private var sheet: RecorderSheetPresenter?

    public init(request: AudioEditorRequest, control: FrameKitEditorControl? = nil, completion: @escaping @MainActor (FrameKitResult) -> Void) {
        self.completion = completion
        self.control = control ?? FrameKitEditorControl()
        var finish: ((FrameKitResult) -> Void)?
        model = AudioEditorModel(request: request, session: EditSession(slot: "audio", input: request.input)) { finish?($0) }
        super.init(nibName: nil, bundle: nil)
        finish = { [weak self] in self?.finished($0) }
        if request.usesRecorderSheet {
            let model = model
            sheet = RecorderSheetPresenter(controller: self, target: model, control: self.control) { [weak model] in model?.hostRecording ?? false }
        } else {
            modalPresentationStyle = .fullScreen
            isModalInPresentation = true
        }
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) { nil }

    override public func viewDidLoad() {
        super.viewDidLoad()
        let root = AudioEditorRoot(model: model)
        let host: UIViewController
        if let sheet {
            host = UIHostingController(rootView: root.environment(\.recorderSheetHeight) { [weak sheet] in sheet?.contentHeight($0) })
        } else {
            host = UIHostingController(rootView: root)
        }
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        host.view.backgroundColor = .clear
        view.addSubview(host.view)
        host.didMove(toParent: self)
    }

    override public func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if result == nil { control.attach(model) }
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
        control.detach(model)
        frameKitClose(self) { [weak self] in self?.deliver() }
    }

    private func deliver() {
        guard !completed, let result else { return }
        completed = true
        completion(result)
    }
}

/// 음성 편집기(SwiftUI). 보통은 `frameKitAudioEditor(isPresented:request:onResult:)`를 쓴다.
public struct FrameKitAudioEditor: UIViewControllerRepresentable {
    private let request: AudioEditorRequest
    private let control: FrameKitEditorControl?
    private let onResult: @MainActor (FrameKitResult) -> Void

    /// [control]을 넘기면 호스트가 닫기를 요청할 수 있다. 없으면 닫기는 편집기 단추로만 한다.
    public init(request: AudioEditorRequest, control: FrameKitEditorControl? = nil, onResult: @escaping @MainActor (FrameKitResult) -> Void) {
        self.request = request
        self.control = control
        self.onResult = onResult
    }

    public func makeUIViewController(context _: Context) -> UIViewController {
        EmbeddedAudioController(request: request, control: control, onResult: onResult)
    }

    public func updateUIViewController(_: UIViewController, context _: Context) {}

    public static func dismantleUIViewController(_ controller: UIViewController, coordinator _: ()) {
        (controller as? EmbeddedAudioController)?.dismantled()
    }
}

@MainActor
private final class EmbeddedAudioController: UIHostingController<AnyView> {
    private let model: AudioEditorModel
    private let control: FrameKitEditorControl?
    private var done = false

    init(request: AudioEditorRequest, control: FrameKitEditorControl?, onResult: @escaping @MainActor (FrameKitResult) -> Void) {
        var finished: ((FrameKitResult) -> Void)?
        model = AudioEditorModel(request: request, session: EditSession(slot: "audio", input: request.input)) { finished?($0) }
        self.control = control
        super.init(rootView: AnyView(EmptyView()))
        finished = { [weak self] result in
            self?.done = true
            if let self { self.control?.detach(self.model) }
            onResult(result)
        }
        rootView = AnyView(AudioEditorRoot(model: model))
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) { nil }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if !done { control?.attach(model) }
    }

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
    /// [isPresented]가 `true`가 되면 음성 편집기를 전체 화면으로 띄운다. 끝나면 닫고 [onResult]를 한 번 부른다.
    /// 녹음만 하는 요청에 `recorderPresentation == .sheet`이면 녹음 조작부 높이의 시트로 띄운다(iOS 15는 큰 시트).
    /// [control]을 넘기면 호스트가 닫기를 요청할 수 있다. 닫히면 SDK가 [isPresented]를 `false`로 바꾼다.
    public func frameKitAudioEditor(
        isPresented: Binding<Bool>,
        request: AudioEditorRequest,
        control: FrameKitEditorControl? = nil,
        onResult: @escaping @MainActor (FrameKitResult) -> Void
    ) -> some View {
        modifier(FrameKitAudioEditorPresentation(isPresented: isPresented, request: request, control: control, onResult: onResult))
    }
}

private struct FrameKitAudioEditorPresentation: ViewModifier {
    let isPresented: Binding<Bool>
    let request: AudioEditorRequest
    let control: FrameKitEditorControl?
    let onResult: @MainActor (FrameKitResult) -> Void

    func body(content: Content) -> some View {
        if request.usesRecorderSheet {
            content.sheet(isPresented: isPresented) {
                RecorderSheetContent(control: control, make: { [isPresented, request, onResult] in
                    AudioEditorModel(request: request, session: EditSession(slot: "audio", input: request.input)) { result in
                        isPresented.wrappedValue = false
                        onResult(result)
                    }
                }, dismissed: { $0.dismissedWithoutResult() }, root: { AudioEditorRoot(model: $0) })
            }
        } else {
            content.fullScreenCover(isPresented: isPresented) {
                FrameKitAudioEditor(request: request, control: control) { result in
                    isPresented.wrappedValue = false
                    onResult(result)
                }
                .ignoresSafeArea()
                .interactiveDismissDisabled()
            }
        }
    }
}
