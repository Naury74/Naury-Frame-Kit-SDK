import Foundation
internal import FrameKitCore

/// 정규화 사각형(0...1). core `RectN`과 같다.
struct NRect: Hashable, Sendable {
    var left: Double
    var top: Double
    var right: Double
    var bottom: Double

    static let full = NRect(left: 0, top: 0, right: 1, bottom: 1)

    var width: Double { right - left }
    var height: Double { bottom - top }

    init(left: Double, top: Double, right: Double, bottom: Double) {
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    }

    init(_ rect: RectN) {
        self.init(left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom)
    }

    var kotlin: RectN { RectN(left: left, top: top, right: right, bottom: bottom) }
}

/// 기하 편집. core `GeometryEdit`과 같다.
struct GeometryState: Hashable, Sendable {
    /// 0...3, 시계 방향.
    var quarterTurns = 0
    /// -45...45, 시계 방향.
    var straightenDegrees = 0.0
    var flipX = false
    var flipY = false
    /// 회전한 경계(G 공간) 기준.
    var crop = NRect.full

    init() {}

    init(_ edit: GeometryEdit) {
        quarterTurns = Int(edit.quarterTurns)
        straightenDegrees = edit.straightenDegrees
        flipX = edit.flipX
        flipY = edit.flipY
        crop = NRect(edit.crop)
    }

    var kotlin: GeometryEdit {
        GeometryEdit(quarterTurns: Int32(quarterTurns), straightenDegrees: straightenDegrees, flipX: flipX, flipY: flipY, crop: crop.kotlin)
    }

    var isIdentity: Bool { self == GeometryState() }
}

/// 보정 종류. 순서는 화면에 보이는 순서다.
enum AdjustKind: String, CaseIterable, Sendable {
    case brightness, exposure, contrast, highlights, shadows, saturation, temperature, tint, sharpness, fade, vignette, grain

    var kotlin: AdjustmentKind {
        switch self {
        case .brightness: .brightness
        case .exposure: .exposure
        case .contrast: .contrast
        case .highlights: .highlights
        case .shadows: .shadows
        case .saturation: .saturation
        case .temperature: .temperature
        case .tint: .tint
        case .sharpness: .sharpness
        case .fade: .fade
        case .vignette: .vignette
        case .grain: .grain
        }
    }

    var signed: Bool { kotlin.signed_ }
    var minimum: Double { kotlin.minimum }
    var maximum: Double { kotlin.maximum }

    /// 모델 값 → 슬라이더 값(-100...100 또는 0...100).
    func toDisplay(_ value: Double) -> Double { value / maximum * 100 }

    /// 슬라이더 값 → 모델 값(범위로 제한).
    func fromDisplay(_ display: Double) -> Double { kotlin.fromDisplay(display: Float(display)) }
}

/// 사진 한 장의 편집 상태.
struct PhotoEdit: Hashable, Sendable {
    var geometry = GeometryState()
    var adjustments: [AdjustKind: Double] = [:]
    var filterId = "original"
    /// 0...1.
    var filterIntensity = 0.0
    var grainSeed: Int64
    var privacyMasks: [PrivacyMask] = []
    var drawing: [DrawingStroke] = []
    /// 목록 뒤쪽이 위.
    var overlays: [Overlay] = []
    var watermark: WatermarkSpec?

    init(grainSeed: Int64 = Int64.random(in: Int64.min...Int64.max)) {
        self.grainSeed = grainSeed
    }

    func value(_ kind: AdjustKind) -> Double { adjustments[kind] ?? 0 }

    var hasColorEdits: Bool {
        adjustments.values.contains { $0 != 0 } || (filterId != "original" && filterIntensity > 0)
    }

    /// 기하를 뺀 색 편집만 같은지.
    func sameColor(as other: PhotoEdit) -> Bool {
        adjustments.filter { $0.value != 0 } == other.adjustments.filter { $0.value != 0 }
            && filterId == other.filterId && filterIntensity == other.filterIntensity
    }

    /// 화면상 같은 결과인지. 0인 보정 항목 유무는 무시한다.
    func sameContent(as other: PhotoEdit) -> Bool {
        geometry == other.geometry && sameColor(as: other) && privacyMasks == other.privacyMasks && drawing == other.drawing
            && overlays == other.overlays && watermark == other.watermark
    }

    var hasCanvasLayers: Bool { !privacyMasks.isEmpty || !drawing.isEmpty || !overlays.isEmpty || watermark?.isValid == true }

    var colorSpec: ColorEffectSpec {
        let adjustments = Adjustments(
            brightness: value(.brightness), exposure: value(.exposure), contrast: value(.contrast),
            highlights: value(.highlights), shadows: value(.shadows), saturation: value(.saturation),
            temperature: value(.temperature), tint: value(.tint), sharpness: value(.sharpness),
            fade: value(.fade), vignette: value(.vignette), grain: value(.grain)
        )
        let filter = FilterSelection(presetId: filterId, intensity: filterIntensity)
        return ColorEffectSpec.companion.of(adjustments: adjustments, filter: filter, grainSeed: grainSeed)
    }

    /// 범위를 벗어난 값이 있으면 `false`. 저장 전에 확인한다.
    func isValid(uprightSize: PixelSizeValue) -> Bool {
        guard (0...3).contains(geometry.quarterTurns), (-45...45).contains(geometry.straightenDegrees) else { return false }
        guard (0...1).contains(filterIntensity), FilterCatalog.shared.find(id: filterId) != nil else { return false }
        for (kind, value) in adjustments where !value.isFinite || value < kind.minimum || value > kind.maximum { return false }
        for overlay in overlays {
            let t = overlay.transform
            guard t.center.x.isFinite, t.center.y.isFinite, OverlayTransform.scaleRange.contains(t.scale),
                  (0...1).contains(t.opacity), t.rotationDegrees.isFinite else { return false }
        }
        guard Set(overlays.map(\.id) + drawing.map(\.id) + privacyMasks.map(\.id)).count == overlays.count + drawing.count + privacyMasks.count,
              drawing.allSatisfy({ !$0.points.isEmpty && $0.points.count <= DrawingStroke.maxPoints }) else { return false }
        if let watermark, !watermark.isValid { return false }
        let frame = GeometryFrame(sourceSize: uprightSize.kotlin, geometry: geometry.kotlin)
        return CropBoundsCalculator.shared.isValidCrop(frame: frame, crop: geometry.crop.kotlin)
    }
}

/// 정수 픽셀 크기.
struct PixelSizeValue: Hashable, Sendable {
    var width: Int
    var height: Int

    var kotlin: PixelSize { PixelSize(width: Int32(width), height: Int32(height)) }
}

/// 기하 계산 묶음. core `GeometryFrame`을 감싼다.
struct GeometryMath {
    let frame: GeometryFrame
    let source: PixelSizeValue

    init(source: PixelSizeValue, geometry: GeometryState) {
        self.source = source
        frame = GeometryFrame(sourceSize: source.kotlin, geometry: geometry.kotlin)
    }

    /// 회전한 경계 크기(px).
    var bounds: CGSize { CGSize(width: frame.bounds.width, height: frame.bounds.height) }

    func cropPixelSize(_ crop: NRect) -> CGSize {
        let size = frame.cropPixelSize(crop: crop.kotlin)
        return CGSize(width: size.width, height: size.height)
    }

    /// 업라이트 원본 픽셀 → 출력 픽셀 변환(y 아래 방향).
    func sourceToOutput(crop: NRect, output: PixelSizeValue) -> CGAffineTransform {
        frame.sourceToOutput(crop: crop.kotlin, outputSize: output.kotlin).cgTransform
    }

    /// 자른 크기와 한도로 정한 출력 크기.
    /// - Throws: 자른 영역이 비었거나 한도가 0 이하일 때 `invalidProject`.
    func outputSize(crop: NRect, maxPixels: Int, maxWidth: Int?, maxHeight: Int?) throws(EditorError) -> PixelSizeValue {
        let crop = frame.cropPixelSize(crop: crop.kotlin)
        do {
            let size = try OutputSizeCalculator.shared.compute(
                cropSize: crop,
                maxPixels: Int64(max(1, maxPixels)),
                maxWidth: maxWidth.map { KotlinInt(int: Int32(clamping: max(1, $0))) },
                maxHeight: maxHeight.map { KotlinInt(int: Int32(clamping: max(1, $0))) }
            )
            return PixelSizeValue(width: Int(size.width), height: Int(size.height))
        } catch {
            throw .logged(.invalidProject, "output size: \(error)")
        }
    }
}

extension Affine2D {
    /// core 행렬(x' = sx·x + kx·y + tx, y' = ky·x + sy·y + ty) → CGAffineTransform.
    var cgTransform: CGAffineTransform {
        CGAffineTransform(a: scaleX, b: skewY, c: skewX, d: scaleY, tx: translateX, ty: translateY)
    }
}
