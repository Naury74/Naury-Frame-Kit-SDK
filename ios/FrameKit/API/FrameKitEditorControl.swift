import Combine
import Foundation

/// 호스트가 편집기 닫기를 다루는 연결점. 화면 가장자리 밀어 닫기 같은 호스트 몸짓을 편집기 닫기 흐름에 잇는다.
///
/// 호스트가 만들어 편집기에 넘긴다. 편집기 화면이 처음 나타날 때 연결되고, 결과를 넘기면 끊긴다.
/// 한 번에 편집기 하나에만 연결된다. 끝난 뒤 새 편집기에 다시 넘기면 그 편집기에 연결된다.
///
/// ```swift
/// let control = FrameKitEditorControl()
/// let editor = FrameKitImageEditorViewController(request: request, control: control) { result in ... }
/// // 호스트 몸짓에서:
/// if control.canDismissInteractively { control.requestClose() }
/// ```
@MainActor
public final class FrameKitEditorControl: ObservableObject {
    /// 지금 호스트 몸짓으로 닫기를 시작해도 되면 `true`.
    ///
    /// `false`인 경우: 연결 전, 끝난 뒤, 저장 중이거나 저장 실패 안내가 떠 있을 때, 확인 대화상자나 SDK 시트가 떠 있을 때,
    /// 원본을 여는 중일 때. 녹음 중이거나 바꾼 내용이 있어도 `true`다(`requestClose()`가 처리한다).
    @Published public private(set) var canDismissInteractively = false

    /// `canDismissInteractively`의 바뀐 값. 구독하면 지금 값을 먼저 보내고, 같은 값은 거듭 보내지 않는다. 메인 스레드에서 보낸다.
    public var canDismissInteractivelyPublisher: AnyPublisher<Bool, Never> {
        $canDismissInteractively.removeDuplicates().eraseToAnyPublisher()
    }

    private weak var target: (any HostCloseTarget)?
    private var changes: AnyCancellable?

    public init() {}

    /// 편집기의 닫기 단추와 같은 흐름으로 닫는다. 호스트는 `dismiss`를 부르지 않는다. 편집기가 스스로 내려가고 결과를 한 번 넘긴다.
    ///
    /// - 바꾼 내용이 있으면(도구에서 적용 전인 내용 포함) 버리기 확인을 띄운다. 버리면 `.cancelled`.
    /// - 녹음 중이면 녹음을 멈추고 그 파일을 지운 뒤 이어서 닫는다. 녹음한 조각은 더하지 않는다.
    /// - SDK 시트가 떠 있으면 시트를 닫고 이어서 닫는다.
    /// - 원본 열기에 실패한 화면이면 그 오류로 끝난다(`.failure`).
    /// - 연결 전, 끝난 뒤, 저장 중, 원본을 여는 중, 확인 대화상자가 떠 있을 때는 아무것도 하지 않는다.
    public func requestClose() {
        guard let target else { return }
        target.hostRequestClose()
        refresh()
    }

    // MARK: - 내부

    /// 편집기 화면이 나타났다. 이전 연결은 끊는다.
    func attach(_ target: any HostCloseTarget) {
        guard self.target !== target else { refresh(); return }
        self.target = target
        // 바뀌기 직전에 알리므로 다음 차례에 읽는다.
        changes = target.hostCloseChanges.sink { [weak self] in
            DispatchQueue.main.async { self?.refresh() }
        }
        refresh()
    }

    /// 편집기가 결과를 넘겼다. 다른 편집기에 이미 다시 연결됐으면 그대로 둔다.
    func detach(_ target: AnyObject) {
        guard self.target == nil || self.target === target else { return }
        self.target = nil
        changes = nil
        refresh()
    }

    func refresh() {
        let value = target?.hostCanDismiss ?? false
        if value != canDismissInteractively { canDismissInteractively = value }
    }
}

/// 호스트 닫기를 받는 편집기 상태.
@MainActor
protocol HostCloseTarget: AnyObject {
    /// 호스트 몸짓으로 닫기를 시작해도 되면 `true`.
    var hostCanDismiss: Bool { get }
    /// 호스트가 닫기를 요청했다.
    func hostRequestClose()
    /// `hostCanDismiss`가 바뀔 수 있을 때 알린다.
    var hostCloseChanges: AnyPublisher<Void, Never> { get }
}

extension HostCloseTarget where Self: ObservableObject, Self.ObjectWillChangePublisher == ObservableObjectPublisher {
    var hostCloseChanges: AnyPublisher<Void, Never> { objectWillChange.map { _ in () }.eraseToAnyPublisher() }
}
