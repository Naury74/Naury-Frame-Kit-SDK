import Foundation
@testable import FrameKit
import Testing

/// 화면에 보이는 이름 키가 모든 언어에 있는지 확인한다. 없으면 키 이름이 그대로 보인다.
struct LabelKeyTests {
    private static let missing = "\u{0}missing"

    private var keys: [String] {
        var keys: [String] = []
        keys += ShapeKind.allCases.map(\.labelKey) + ShapeFill.allCases.map(\.labelKey)
        keys += BrushKind.allCases.map(\.labelKey) + TextAlign.allCases.map(\.labelKey)
        keys += PrivacySettings.Mode.allCases.map(\.labelKey) + ["privacy_mosaic", "privacy_blur", "draw_shapes"]
        keys += WatermarkPattern.allCases.map(\.labelKey) + WatermarkPosition.allCases.map(\.labelKey)
        keys += CanvasFit.allCases.map(\.labelKey)
        keys += MotionTransition.allCases.map(\.labelKey) + LoopMode.allCases.map(\.labelKey) + MotionFit.allCases.map(\.labelKey)
        keys += MotionFormat.allCases.flatMap { [$0.labelKey, $0.helpKey] } + MotionSize.allCases.map(\.labelKey)
        keys += ImageTool.allCases.map(\.labelKey) + VideoTool.allCases.map(\.labelKey)
        keys += MotionTool.allCases.map(\.labelKey) + AdjustKind.allCases.map(\.labelKey) + AudioTool.allCases.map(\.labelKey)
        keys += EditorCatalog.builtInFonts.map(CatalogLabelKeys.font)
        keys += CatalogLabelKeys.builtInFilterIds.map(CatalogLabelKeys.filter)
        return keys
    }

    @Test func everyLabelKeyIsTranslated() throws {
        let base = FrameKitBundle.bundle
        let languages = base.localizations.filter { $0 != "Base" }
        #expect(languages.count >= 13)
        for language in languages {
            let path = try #require(base.path(forResource: language, ofType: "lproj"))
            let bundle = try #require(Bundle(path: path))
            let absent = keys.filter { bundle.localizedString(forKey: $0, value: Self.missing, table: "Localizable") == Self.missing }
            #expect(absent.isEmpty, "\(language): \(absent)")
        }
    }

    @Test func roundedRectangleUsesSharedKey() {
        #expect(ShapeKind.roundedRectangle.labelKey == "shape_rounded")
        #expect(Strings(localeIdentifier: "ko")(ShapeKind.roundedRectangle.labelKey) != ShapeKind.roundedRectangle.labelKey)
    }
}
