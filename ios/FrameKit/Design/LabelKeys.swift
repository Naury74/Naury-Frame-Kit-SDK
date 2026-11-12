import Foundation
internal import FrameKitCore

// 화면에 이름을 보이는 값들의 문자열 키. 키는 Android 문자열 이름에서 `framekit_`를 뺀 것과 같아야 한다(LabelKeyTests가 확인).

extension ShapeKind {
    var labelKey: String {
        switch self {
        case .rectangle: "shape_rectangle"
        case .roundedRectangle: "shape_rounded"
        case .ellipse: "shape_ellipse"
        case .triangle: "shape_triangle"
        case .star: "shape_star"
        case .line: "shape_line"
        case .arrow: "shape_arrow"
        case .doubleArrow: "shape_double_arrow"
        }
    }
}

extension ShapeFill {
    var labelKey: String { "shape_\(rawValue)" }
}

extension BrushKind {
    var labelKey: String { "brush_\(rawValue)" }
}

extension TextAlign {
    var labelKey: String { "align_\(rawValue)" }
}

extension PrivacySettings.Mode {
    var labelKey: String { "privacy_\(rawValue)" }
}

extension WatermarkPattern {
    var labelKey: String { "watermark_\(rawValue)" }
}

extension WatermarkPosition {
    var labelKey: String {
        switch self {
        case .topStart: "watermark_top_start"
        case .topCenter: "watermark_top_center"
        case .topEnd: "watermark_top_end"
        case .centerStart: "watermark_center_start"
        case .center: "watermark_center"
        case .centerEnd: "watermark_center_end"
        case .bottomStart: "watermark_bottom_start"
        case .bottomCenter: "watermark_bottom_center"
        case .bottomEnd: "watermark_bottom_end"
        }
    }
}

extension CanvasFit {
    var labelKey: String { "canvas_\(rawValue)" }
}

extension MotionTransition {
    var labelKey: String { self == .none ? "motion_none" : "motion_transition_\(rawValue)" }
}

extension LoopMode {
    var labelKey: String { "motion_loop_\(rawValue)" }
}

extension MotionFit {
    var labelKey: String { "motion_fit_\(rawValue)" }
}

extension MotionFormat {
    var labelKey: String { "motion_format_\(rawValue)" }
    var helpKey: String { "motion_format_\(rawValue)_help" }
}

extension MotionSize {
    var labelKey: String { "motion_size_\(rawValue)" }
}

enum CatalogLabelKeys {
    /// 기본 글꼴 id(`sans-bold` 등)의 이름 키.
    static func font(_ id: String) -> String { "font_\(id.replacingOccurrences(of: "-", with: "_"))" }
    /// 기본 필터 id.
    static var builtInFilterIds: [String] { FilterCatalog.shared.presets.map(\.id) }
    /// 기본 필터 id의 이름 키.
    static func filter(_ id: String) -> String { "filter_\(id.replacingOccurrences(of: "-", with: "_"))" }
}
