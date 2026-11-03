import Foundation

/// 음성 편집 도구.
public enum AudioTool: String, Sendable, CaseIterable {
    /// 녹음해 재생 위치에 끼워 넣거나 겹친다. 앱 Info.plist에 `NSMicrophoneUsageDescription`이 없으면 숨는다.
    case record
    /// 소리 파일을 재생 위치에 넣는다.
    case importFile = "import"
    /// 재생 위치에서 조각을 나눈다.
    case split
    /// 구간을 골라 잘라 내거나 그 구간만 남긴다.
    case range
    /// 조각 볼륨·페이드.
    case volume

    public static let defaults: [AudioTool] = allCases
}

/// 녹음 단추 방식.
public enum RecordMode: String, Sendable, CaseIterable {
    /// 눌러서 시작하고 다시 눌러 끝낸다. 일시정지할 수 있다.
    case tap
    /// 누르고 있는 동안 녹음한다. 위로 밀면 고정, 왼쪽으로 밀면 취소.
    case hold
    /// 사용자가 둘 중 고른다.
    case both
}

/// 음성 편집기 동작 설정.
public struct AudioEditorConfig: Sendable, Hashable {
    public var recordMode: RecordMode
    /// 녹음 한 번의 최대 길이(초), 1...3600. 닿으면 녹음을 끝낸다.
    public var maxRecordingDuration: TimeInterval
    /// `false`면 `.capture`로 녹음한 파일을 편집 없이 바로 돌려준다.
    public var editAfterRecording: Bool
    public var enabledTools: [AudioTool]
    /// 불러올 수 있는 소리 파일 수 상한, 1...20.
    public var maxSourceCount: Int
    public var allowUndo: Bool
    public var allowRedo: Bool

    public static let maxSources = 20

    public init(recordMode: RecordMode = .both, maxRecordingDuration: TimeInterval = 600, editAfterRecording: Bool = true,
                enabledTools: [AudioTool] = AudioTool.defaults, maxSourceCount: Int = 10, allowUndo: Bool = true, allowRedo: Bool = true) {
        self.recordMode = recordMode
        self.maxRecordingDuration = maxRecordingDuration
        self.editAfterRecording = editAfterRecording
        self.enabledTools = enabledTools
        self.maxSourceCount = maxSourceCount
        self.allowUndo = allowUndo
        self.allowRedo = allowRedo
    }
}

/// 음성 저장 설정. 결과는 AAC M4A다.
public struct AudioExportConfig: Sendable, Hashable {
    /// 비트레이트(bps), 32_000...320_000.
    public var bitrate: Int

    public init(bitrate: Int = 128_000) {
        self.bitrate = bitrate
    }
}

/// 음성 편집기 요청.
///
/// `input`은 `.pick`·`.pickMultiple`(파일 앱에서 소리 파일), `.file`·`.files`, `.capture`(녹음)를 쓴다. `.data`는 쓸 수 없다.
/// `.capture`는 앱 Info.plist에 `NSMicrophoneUsageDescription`이 있어야 한다.
public struct AudioEditorRequest: Sendable, Hashable {
    public var input: EditorInput
    public var config: AudioEditorConfig
    public var export: AudioExportConfig
    public var ui: EditorUIConfig

    public init(input: EditorInput, config: AudioEditorConfig = AudioEditorConfig(), export: AudioExportConfig = AudioExportConfig(),
                ui: EditorUIConfig = EditorUIConfig()) {
        self.input = input
        self.config = config
        self.export = export
        self.ui = ui
    }

    /// 설정 오류 목록. 비어 있으면 열 수 있다.
    public func validate() -> [ConfigurationIssue] {
        var issues: [ConfigurationIssue] = []
        switch input {
        case .data: issues.append(.init(path: "input", reason: .invalidValue))
        case .pickMultiple(let max) where !(1...config.maxSourceCount).contains(max): issues.append(.init(path: "input", reason: .outOfRange))
        case .files(let urls) where urls.isEmpty || urls.count > config.maxSourceCount || urls.contains(where: { !$0.isFileURL }):
            issues.append(.init(path: "input", reason: .invalidValue))
        case .file(let url) where !url.isFileURL: issues.append(.init(path: "input", reason: .invalidValue))
        default: break
        }
        if config.allowRedo && !config.allowUndo { issues.append(.init(path: "config.allowRedo", reason: .requiresOther)) }
        if !(1...3600).contains(config.maxRecordingDuration) { issues.append(.init(path: "config.maxRecordingDuration", reason: .outOfRange)) }
        if !(1...AudioEditorConfig.maxSources).contains(config.maxSourceCount) { issues.append(.init(path: "config.maxSourceCount", reason: .outOfRange)) }
        if !(32_000...320_000).contains(export.bitrate) { issues.append(.init(path: "export.bitrate", reason: .outOfRange)) }
        issues += ui.issues(catalog: nil)
        return issues
    }
}
