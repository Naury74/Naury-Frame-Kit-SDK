import Foundation

/// 오류 종류. 화면에 보일 문구는 호스트 앱이 코드별로 정한다.
public enum EditorErrorCode: String, Sendable, CaseIterable {
    /// 형식·종류·메타데이터가 이 편집기가 여는 것과 맞지 않는다.
    case invalidSource
    /// 원본을 읽을 권한이 없다. 카메라·마이크 권한이 없을 때도 쓰며, 그때 권장 행동은 `openSettings`다.
    case permissionDenied
    /// 원본이 지워졌거나 옮겨졌거나 내려받을 수 없다.
    case sourceUnavailable
    /// 원본이 너무 커서 안전하게 열 수 없다(2억 5천만 픽셀 초과).
    case sourceTooLarge
    /// 지원하지 않는 형식이다.
    case unsupportedFormat
    /// 이 기기에서 할 수 없는 편집·출력이다.
    case unsupportedOperation
    /// 호스트가 범위를 벗어난 설정을 넘겼다.
    case invalidConfiguration
    /// 편집 값이 범위를 벗어났다.
    case invalidProject
    /// 원본이 깨져 디코딩할 수 없다.
    case decodeFailed
    /// 인코딩에 실패했다.
    case encodeFailed
    /// 이 기기의 메모리로는 처리할 수 없는 크기다.
    case insufficientMemory
    /// 저장 공간이 부족하다.
    case insufficientStorage
    /// 파일을 쓰거나 마무리하지 못했다.
    case outputWriteFailed
    /// 편집기가 결과 없이 끝났다.
    case resultUnavailable
    /// 그 밖의 실패.
    case unknown
    /// 카메라를 쓸 수 없다.
    case cameraUnavailable

    /// 사용자가 다시 시도하거나 다른 선택으로 풀 수 있으면 `true`.
    public var defaultRecoverable: Bool {
        switch self {
        case .invalidConfiguration, .invalidProject, .decodeFailed: false
        default: true
        }
    }

    public var defaultAction: SuggestedAction {
        switch self {
        case .invalidSource, .permissionDenied, .sourceUnavailable, .sourceTooLarge, .unsupportedFormat, .decodeFailed,
             .cameraUnavailable: .chooseAnotherSource
        case .unsupportedOperation: .changeOutputSettings
        case .invalidConfiguration, .invalidProject: .fixConfiguration
        case .encodeFailed, .insufficientMemory: .lowerResolution
        case .insufficientStorage: .freeStorage
        case .outputWriteFailed, .unknown: .retry
        case .resultUnavailable: .relaunch
        }
    }
}

/// 오류를 풀기 위해 권하는 다음 행동.
public enum SuggestedAction: String, Sendable {
    case chooseAnotherSource
    case changeOutputSettings
    case fixConfiguration
    case lowerResolution
    case freeStorage
    case retry
    case relaunch
    /// 카메라·마이크 권한을 설정 앱에서 허용한다.
    case openSettings
}

/// 편집기 오류.
public struct EditorError: Error, Sendable, Hashable {
    public let code: EditorErrorCode
    public let recoverable: Bool
    public let suggestedAction: SuggestedAction
    /// 로그와 맞춰 보기 위한 8자 식별자. 같은 값이 SDK 로그에 남는다.
    public let diagnosticId: String

    public init(code: EditorErrorCode, recoverable: Bool? = nil, suggestedAction: SuggestedAction? = nil) {
        self.code = code
        self.recoverable = recoverable ?? code.defaultRecoverable
        self.suggestedAction = suggestedAction ?? code.defaultAction
        self.diagnosticId = String(UUID().uuidString.prefix(8)).lowercased()
    }
}

extension EditorError {
    /// 카메라·마이크 권한이 없다.
    static var deviceAccessDenied: EditorError { EditorError(code: .permissionDenied, suggestedAction: .openSettings) }
}
