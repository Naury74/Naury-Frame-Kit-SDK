import CoreText
import Foundation
import UIKit
internal import FrameKitCore

/// 호스트가 더하는 필터·스티커·글꼴. 편집 결과에는 id만 남으므로 앱 버전이 바뀌어도 id를 유지한다.
public struct EditorCatalog: Sendable, Hashable {
    public var filters: [CustomFilter]
    public var stickers: [CustomSticker]
    public var fonts: [CustomFont]
    /// `false`면 기본 필터를 숨긴다. '원본'은 항상 남는다.
    public var showDefaultFilters: Bool
    /// `false`면 이모지를 숨긴다.
    public var showDefaultStickers: Bool
    /// `false`면 기본 글꼴 중 '기본'만 남긴다.
    public var showDefaultFonts: Bool

    public static let maxItems = 200
    public static let maxFonts = 20

    public init(filters: [CustomFilter] = [], stickers: [CustomSticker] = [], fonts: [CustomFont] = [],
                showDefaultFilters: Bool = true, showDefaultStickers: Bool = true, showDefaultFonts: Bool = true) {
        self.filters = filters
        self.stickers = stickers
        self.fonts = fonts
        self.showDefaultFilters = showDefaultFilters
        self.showDefaultStickers = showDefaultStickers
        self.showDefaultFonts = showDefaultFonts
    }

    static let builtInFonts = ["sans", "sans-bold", "serif", "serif-bold", "mono", "handwriting"]

    func issues() -> [ConfigurationIssue] {
        var issues: [ConfigurationIssue] = []
        let idPattern = /^[a-z0-9_.-]{1,64}$/
        func check(_ ids: [String], labels: [String], reserved: Set<String>, path: String, limit: Int) {
            if ids.count > limit { issues.append(.init(path: path, reason: .outOfRange)) }
            if Set(ids).count != ids.count { issues.append(.init(path: path, reason: .invalidValue)) }
            for (index, id) in ids.enumerated() where (try? idPattern.wholeMatch(in: id)) == nil || reserved.contains(id) {
                issues.append(.init(path: "\(path)[\(index)].id", reason: .invalidValue))
            }
            for (index, label) in labels.enumerated() where label.trimmingCharacters(in: .whitespaces).isEmpty {
                issues.append(.init(path: "\(path)[\(index)].label", reason: .invalidValue))
            }
        }
        let builtInFilters = Set(FilterCatalog.shared.presets.map(\.id))
        check(filters.map(\.id), labels: filters.map(\.label), reserved: builtInFilters, path: "catalog.filters", limit: Self.maxItems)
        check(stickers.map(\.id), labels: stickers.map(\.label), reserved: [], path: "catalog.stickers", limit: Self.maxItems)
        check(fonts.map(\.id), labels: fonts.map(\.label), reserved: Set(Self.builtInFonts), path: "catalog.fonts", limit: Self.maxFonts)
        for (index, filter) in filters.enumerated() where !filter.isValid {
            issues.append(.init(path: "catalog.filters[\(index)]", reason: .outOfRange))
        }
        return issues
    }
}

/// 사용자 정의 필터. 값은 -1...1, 색조는 세 값(빨강·초록·파랑) 각 ±0.2, `fade`는 0...1.
public struct CustomFilter: Sendable, Hashable {
    public var id: String
    public var label: String
    public var temperature: Double
    public var tint: Double
    public var brightness: Double
    public var contrast: Double
    public var saturation: Double
    public var shadowTint: [Double]
    public var highlightTint: [Double]
    public var fade: Double
    /// 1 이상. 같은 id의 모양을 바꾸면 올린다.
    public var version: Int

    public init(id: String, label: String, temperature: Double = 0, tint: Double = 0, brightness: Double = 0, contrast: Double = 0,
                saturation: Double = 0, shadowTint: [Double] = [0, 0, 0], highlightTint: [Double] = [0, 0, 0], fade: Double = 0, version: Int = 1) {
        self.id = id
        self.label = label
        self.temperature = temperature
        self.tint = tint
        self.brightness = brightness
        self.contrast = contrast
        self.saturation = saturation
        self.shadowTint = shadowTint
        self.highlightTint = highlightTint
        self.fade = fade
        self.version = version
    }

    var isValid: Bool {
        let unit = [temperature, tint, brightness, contrast, saturation].allSatisfy { $0.isFinite && (-1...1).contains($0) }
        let tints = [shadowTint, highlightTint].allSatisfy { $0.count == 3 && $0.allSatisfy { $0.isFinite && (-0.2...0.2).contains($0) } }
        return unit && tints && (0...1).contains(fade) && version >= 1
    }

    var preset: FilterPreset {
        FilterPreset(id: id, version: Int32(version), grade: FilterGrade(
            temperature: temperature, tint: tint, brightness: brightness, contrast: contrast, saturation: saturation,
            shadowTint: RgbShift(red: shadowTint[0], green: shadowTint[1], blue: shadowTint[2]),
            highlightTint: RgbShift(red: highlightTint[0], green: highlightTint[1], blue: highlightTint[2]), fade: fade))
    }
}

/// 사용자 정의 스티커. PNG·HEIC·JPEG 파일(앱 번들이나 앱 폴더). 긴 변 512px로 읽는다.
public struct CustomSticker: Sendable, Hashable {
    public var id: String
    public var label: String
    public var file: URL

    public init(id: String, label: String, file: URL) {
        self.id = id
        self.label = label
        self.file = file
    }
}

/// 사용자 정의 글꼴. TTF·OTF 파일. 이 앱 안에서만 등록한다.
public struct CustomFont: Sendable, Hashable {
    public var id: String
    public var label: String
    public var file: URL

    public init(id: String, label: String, file: URL) {
        self.id = id
        self.label = label
        self.file = file
    }
}

/// 목록을 엔진에 등록한다. 읽지 못한 스티커·글꼴은 건너뛰고 로그를 남긴다.
enum CatalogInstaller {
    static func install(_ catalog: EditorCatalog) throws(EditorError) {
        do {
            try FilterCatalog.shared.install(custom: catalog.filters.map(\.preset))
        } catch {
            throw .logged(.invalidConfiguration, "filters: \(error)")
        }
        var stickers: [String: UIImage] = [:]
        for sticker in catalog.stickers {
            guard let image = loadSticker(sticker.file) else {
                frameKitLog.error("sticker \(sticker.id, privacy: .public) could not be read")
                continue
            }
            stickers[StickerPrefix + sticker.id] = image
        }
        StickerAssets.install(stickers, labels: Dictionary(uniqueKeysWithValues: catalog.stickers.map { (StickerPrefix + $0.id, $0.label) }))
        var fonts: [String: String] = [:]
        for font in catalog.fonts {
            guard let name = registerFont(font.file) else {
                frameKitLog.error("font \(font.id, privacy: .public) could not be registered")
                continue
            }
            fonts[font.id] = name
        }
        FontAssets.install(fonts, labels: Dictionary(uniqueKeysWithValues: catalog.fonts.map { ($0.id, $0.label) }))
        FilterLabels.install(Dictionary(uniqueKeysWithValues: catalog.filters.map { ($0.id, $0.label) }))
    }

    static let StickerPrefix = "sticker:"

    private static func loadSticker(_ url: URL) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                                                         kCGImageSourceCreateThumbnailWithTransform: true,
                                                                         kCGImageSourceThumbnailMaxPixelSize: 512] as CFDictionary)
        else { return nil }
        return UIImage(cgImage: image)
    }

    private static func registerFont(_ url: URL) -> String? {
        guard let descriptors = CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor],
              let descriptor = descriptors.first,
              let name = CTFontDescriptorCopyAttribute(descriptor, kCTFontNameAttribute) as? String else { return nil }
        if UIFont(name: name, size: 12) == nil {
            var error: Unmanaged<CFError>?
            guard CTFontManagerRegisterFontsForURL(url as CFURL, .process, &error) else { return nil }
        }
        return name
    }
}

/// 사용자 정의 필터 이름.
enum FilterLabels {
    nonisolated(unsafe) private static var labels: [String: String] = [:]
    private static let lock = NSLock()

    static func install(_ entries: [String: String]) { lock.withLock { labels = entries } }
    static func label(for id: String) -> String? { lock.withLock { labels[id] } }
}
