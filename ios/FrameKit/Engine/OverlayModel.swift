import CoreGraphics
import Foundation

/// 정규화 점(0...1, 출력 캔버스 기준).
struct NPoint: Hashable, Sendable, Codable {
    var x: Double
    var y: Double
}

/// 겹친 요소의 위치. 좌표는 출력 캔버스 기준 0...1.
struct OverlayTransform: Hashable, Sendable, Codable {
    var center = NPoint(x: 0.5, y: 0.5)
    /// 0.1...10.
    var scale = 1.0
    /// 시계 방향(도).
    var rotationDegrees = 0.0
    /// 0...1.
    var opacity = 1.0

    static let scaleRange = 0.1...10.0
}

enum TextAlign: String, Hashable, Sendable, Codable, CaseIterable { case start, center, end }

/// 글자 모양. 크기 값은 캔버스 높이 대비 비율이다.
struct TextStyle: Hashable, Sendable, Codable {
    struct Stroke: Hashable, Sendable, Codable { var color: UInt32; var widthRatio: Double }
    struct Shadow: Hashable, Sendable, Codable { var color: UInt32; var offsetXRatio: Double; var offsetYRatio: Double; var blurRatio: Double }
    struct Background: Hashable, Sendable, Codable { var color: UInt32; var paddingRatio = 0.008; var cornerRatio = 0.006 }

    var fontId = "sans"
    var fontSizeRatio = 0.06
    var color: UInt32 = 0xFFFF_FFFF
    var alignment = TextAlign.center
    /// -0.1...0.5.
    var letterSpacingEm = 0.0
    /// 0.8...2.
    var lineSpacing = 1.0
    var maxWidthRatio = 0.8
    var stroke: Stroke?
    var shadow: Shadow?
    var background: Background?
}

enum ShapeKind: String, Hashable, Sendable, Codable, CaseIterable {
    case rectangle, roundedRectangle, ellipse, triangle, star, line, arrow, doubleArrow

    var isLine: Bool { self == .line || self == .arrow || self == .doubleArrow }
}

struct ShapeStyle: Hashable, Sendable, Codable {
    var color: UInt32 = 0xFFFF_FFFF
    /// 짧은 변 대비, 최대 0.1.
    var strokeRatio = 0.008
    var filled = false
    var dashed = false
}

/// 사진 위에 겹치는 요소. 목록 뒤쪽이 위에 그려진다.
struct Overlay: Hashable, Sendable, Codable, Identifiable {
    enum Content: Hashable, Sendable, Codable {
        case text(String, TextStyle)
        /// `emoji:<글자>` 또는 `sticker:<호스트 id>`. 폭은 캔버스 너비 대비.
        case sticker(assetId: String, widthRatio: Double)
        /// 크기는 캔버스 너비·높이 대비(0...4). 선 종류는 높이를 쓰지 않는다.
        case shape(ShapeKind, widthRatio: Double, heightRatio: Double, ShapeStyle)
    }

    var id: String
    var content: Content
    var transform = OverlayTransform()

    var isText: Bool { if case .text = content { true } else { false } }
}

enum BrushKind: String, Hashable, Sendable, Codable, CaseIterable { case pen, marker, highlighter, eraser }

/// 그리기 한 획. 점은 캔버스 기준 0...1, 최대 2000개.
struct DrawingStroke: Hashable, Sendable, Codable, Identifiable {
    static let maxPoints = 2000
    var id: String
    var points: [NPoint]
    /// 짧은 변 대비 굵기.
    var widthRatio: Double
    var color: UInt32
    var opacity: Double
    var brush: BrushKind
}

/// 가리기 영역.
struct PrivacyMask: Hashable, Sendable, Codable, Identifiable {
    enum Shape: Hashable, Sendable, Codable {
        case brush([NPoint], widthRatio: Double)
        case rectangle(NRect)
        case ellipse(NRect)
    }

    enum Effect: Hashable, Sendable, Codable {
        /// 짧은 변 대비 흐림 반경(시그마).
        case blur(radiusRatio: Double)
        /// 짧은 변 대비 블록 크기. 격자는 캔버스 원점 기준.
        case mosaic(blockRatio: Double)
    }

    static let maxRatio = 0.2
    var id: String
    var shape: Shape
    var effect: Effect
}

enum WatermarkPattern: String, Hashable, Sendable, Codable, CaseIterable { case single, tile, diagonal, band }

/// 워터마크 위치. horizontal·vertical은 -1(시작·위), 0(가운데), 1(끝·아래).
enum WatermarkPosition: String, Hashable, Sendable, Codable, CaseIterable {
    case topStart, topCenter, topEnd, centerStart, center, centerEnd, bottomStart, bottomCenter, bottomEnd

    private var index: Int { Self.allCases.firstIndex(of: self)! }
    var horizontal: Int { index % 3 - 1 }
    var vertical: Int { index / 3 - 1 }
}

/// 워터마크. Android `Watermark`와 같은 범위다.
struct WatermarkSpec: Hashable, Sendable, Codable {
    static let maxTextLength = 60
    var text: String
    var pattern = WatermarkPattern.single
    var position = WatermarkPosition.bottomEnd
    /// 짧은 변 대비 글자 크기, 0.015...0.2.
    var sizeRatio = 0.04
    /// 0.05...1.
    var opacity = 0.7
    var color: UInt32 = 0xFFFF_FFFF
    /// -90...90, 반복 무늬에만 쓴다.
    var rotationDegrees = -30.0
    /// 0.5...2, 반복 무늬에만 쓴다.
    var density = 1.0
    var shadow = true
    var background = false
    var fontId = "sans-bold"

    var isValid: Bool {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        return !trimmed.isEmpty && trimmed.count <= Self.maxTextLength && !text.contains("\n")
            && (0.015...0.2).contains(sizeRatio) && (0.05...1).contains(opacity)
            && (-90...90).contains(rotationDegrees) && (0.5...2).contains(density)
    }
}
