import Foundation

/// 사진 편집기 요청.
public struct ImageEditorRequest: Sendable, Hashable {
    public var input: EditorInput
    public var config: ImageEditorConfig
    public var export: ImageExportConfig
    public var ui: EditorUIConfig
    public var catalog: EditorCatalog

    public init(
        input: EditorInput,
        config: ImageEditorConfig = ImageEditorConfig(),
        export: ImageExportConfig = ImageExportConfig(),
        ui: EditorUIConfig = EditorUIConfig(),
        catalog: EditorCatalog = EditorCatalog()
    ) {
        self.input = input
        self.config = config
        self.export = export
        self.ui = ui
        self.catalog = catalog
    }

    /// 설정 오류 목록. 비어 있으면 편집기를 열 수 있다. 오류가 있으면 편집기는 `invalidConfiguration`으로 끝난다.
    public func validate() -> [ConfigurationIssue] {
        var issues: [ConfigurationIssue] = []
        if case .file(let url) = input, !url.isFileURL { issues.append(.init(path: "input", reason: .invalidValue)) }
        if case .data(let data) = input, data.isEmpty { issues.append(.init(path: "input", reason: .invalidValue)) }
        if !(1...ImageEditorConfig.maxImages).contains(config.maxImageCount) {
            issues.append(.init(path: "config.maxImageCount", reason: .outOfRange))
        }
        if case .pickMultiple(let max) = input, !(1...config.maxImageCount).contains(max) {
            issues.append(.init(path: "input", reason: .outOfRange))
        }
        if case .files(let urls) = input, urls.isEmpty || urls.count > config.maxImageCount || urls.contains(where: { !$0.isFileURL }) {
            issues.append(.init(path: "input", reason: .invalidValue))
        }
        if !(0...50).contains(export.pdf.marginMm) { issues.append(.init(path: "export.pdf.marginMm", reason: .outOfRange)) }
        if !(72...600).contains(export.pdf.dpi) { issues.append(.init(path: "export.pdf.dpi", reason: .outOfRange)) }
        if config.allowRedo && !config.allowUndo { issues.append(.init(path: "config.allowRedo", reason: .requiresOther)) }
        if let watermark = config.watermark { issues += watermark.issues(path: "config.watermark") }
        issues += catalog.issues()
        if !(0...100).contains(export.quality) { issues.append(.init(path: "export.quality", reason: .outOfRange)) }
        if let w = export.maxWidth, w <= 0 { issues.append(.init(path: "export.maxWidth", reason: .outOfRange)) }
        if let h = export.maxHeight, h <= 0 { issues.append(.init(path: "export.maxHeight", reason: .outOfRange)) }
        if !(1...ImageExportConfig.maxPixelsLimit).contains(export.maxOutputPixels) {
            issues.append(.init(path: "export.maxOutputPixels", reason: .outOfRange))
        }
        issues += ui.issues(catalog: catalog)
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
