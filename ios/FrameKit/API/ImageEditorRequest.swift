import Foundation

/// 사진 편집기 요청.
public struct ImageEditorRequest: Sendable, Hashable {
    public var input: EditorInput
    public var config: ImageEditorConfig
    public var export: ImageExportConfig
    public var ui: EditorUIConfig

    public init(
        input: EditorInput,
        config: ImageEditorConfig = ImageEditorConfig(),
        export: ImageExportConfig = ImageExportConfig(),
        ui: EditorUIConfig = EditorUIConfig()
    ) {
        self.input = input
        self.config = config
        self.export = export
        self.ui = ui
    }

    /// 설정 오류 목록. 비어 있으면 편집기를 열 수 있다. 오류가 있으면 편집기는 `invalidConfiguration`으로 끝난다.
    public func validate() -> [ConfigurationIssue] {
        var issues: [ConfigurationIssue] = []
        if case .file(let url) = input, !url.isFileURL { issues.append(.init(path: "input", reason: .invalidValue)) }
        if case .data(let data) = input, data.isEmpty { issues.append(.init(path: "input", reason: .invalidValue)) }
        if config.allowRedo && !config.allowUndo { issues.append(.init(path: "config.allowRedo", reason: .requiresOther)) }
        if !(0...100).contains(export.quality) { issues.append(.init(path: "export.quality", reason: .outOfRange)) }
        if let w = export.maxWidth, w <= 0 { issues.append(.init(path: "export.maxWidth", reason: .outOfRange)) }
        if let h = export.maxHeight, h <= 0 { issues.append(.init(path: "export.maxHeight", reason: .outOfRange)) }
        if !(1...ImageExportConfig.maxPixelsLimit).contains(export.maxOutputPixels) {
            issues.append(.init(path: "export.maxOutputPixels", reason: .outOfRange))
        }
        if !(0...32).contains(ui.cornerRadius) { issues.append(.init(path: "ui.cornerRadius", reason: .outOfRange)) }
        if let id = ui.localeIdentifier, id.trimmingCharacters(in: .whitespaces).isEmpty {
            issues.append(.init(path: "ui.localeIdentifier", reason: .invalidValue))
        }
        return issues
    }
}

/// 잘못된 설정 하나.
public struct ConfigurationIssue: Sendable, Hashable, CustomStringConvertible {
    public enum Reason: String, Sendable {
        case outOfRange
        case invalidValue
        /// 함께 켜야 하는 다른 설정이 꺼져 있다.
        case requiresOther
    }

    /// 문제 항목 경로. 예: `export.quality`.
    public let path: String
    public let reason: Reason

    public var description: String { "\(path): \(reason.rawValue)" }
}
