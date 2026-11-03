import SwiftUI

/// 텍스트·스티커·가리기·워터마크 패널과 사진 위 조작층이 쓰는 동작. 사진·영상 편집기가 함께 쓴다.
@MainActor
protocol LayerToolHost: AnyObject, Observable {
    var catalog: EditorCatalog { get }
    var editingText: (text: String, style: TextStyle)? { get }
    func setText(_ text: String)
    func setTextStyle(_ change: (inout TextStyle) -> Void)
    func mutateSelected(_ change: (inout Overlay) -> Void)
    var selectedOverlay: Overlay? { get }
    var selectedOverlayId: String? { get }
    var stickerCategory: Int { get set }
    var customStickerIds: [String] { get }
    func addSticker(_ assetId: String)
    var privacy: PrivacySettings { get set }
    var privacyModes: [PrivacySettings.Mode] { get }
    var watermarkDraft: WatermarkSpec? { get }
    func setWatermark(_ change: (inout WatermarkSpec) -> Void)
    func removeWatermark()
    var hasCommittedWatermark: Bool { get }
    var watermarkSuggestions: [String] { get }
    // 사진 위 조작
    var renderedCanvas: CGSize { get }
    var liveStroke: [NPoint]? { get }
    var canManipulateOverlays: Bool { get }
    var isDrawingMode: Bool { get }
    var liveStrokeIsShape: Bool { get }
    /// 그리는 중인 획 모양(짧은 변 대비 굵기, 색, 네모 끝).
    var liveStrokeStyle: (widthRatio: Double, color: Color, square: Bool) { get }
    func toggleChrome()
    func selectOverlay(at point: CGPoint)
    func editSelectedText()
    func strokeBegan(_ point: NPoint)
    func strokeMoved(_ point: NPoint)
    func strokeEnded()
    func transformSelected(from start: OverlayTransform, move: CGSize, scale: Double, rotation: Double)
    func commitGesture()
    func deleteSelected()
    func duplicateSelected()
}

extension EditorModel: LayerToolHost {
    var privacyModes: [PrivacySettings.Mode] { PrivacySettings.Mode.allCases }
    var hasCommittedWatermark: Bool { history.current.watermark != nil }
    var watermarkSuggestions: [String] { request.config.watermark?.suggestions ?? [] }
    var isDrawingMode: Bool { activeTool == .draw || activeTool == .privacy }
    var liveStrokeIsShape: Bool { (activeTool == .draw && brush.shapes) || (activeTool == .privacy && privacy.mode != .brush) }
    var liveStrokeStyle: (widthRatio: Double, color: Color, square: Bool) {
        if activeTool == .privacy { return (privacy.brushRatio, Color.white.opacity(0.5), false) }
        if brush.kind == .eraser { return (brush.widthRatio, Color.white.opacity(0.35), false) }
        return (brush.widthRatio, Color(argb: brush.color).opacity(brush.opacity * (brush.kind == .highlighter ? 0.4 : 1)), brush.kind == .marker)
    }
    func editSelectedText() { if activeTool == nil { selectTool(.text) } }
}

/// 글자·그리기 색(Android PaletteColors와 같은 순서).
enum Palette {
    static let colors: [(argb: UInt32, key: String)] = [
        (0xFFFF_FFFF, "color_white"), (0xFF00_0000, "color_black"), (0xFF8E_8E93, "color_gray"), (0xFFFF_3B30, "color_red"),
        (0xFFFF_9500, "color_orange"), (0xFFFF_CC00, "color_yellow"), (0xFF34_C759, "color_green"), (0xFF00_C7BE, "color_teal"),
        (0xFF00_7AFF, "color_blue"), (0xFF58_56D6, "color_indigo"), (0xFFAF_52DE, "color_purple"), (0xFFFF_2D55, "color_pink"),
    ]
}

/// 색 견본 줄. 고른 견본은 테두리와 체크로도 표시한다.
struct ColorPalette: View {
    let selected: UInt32
    let onSelect: (UInt32) -> Void
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 2) {
                ForEach(Palette.colors, id: \.argb) { item in
                    let isSelected = item.argb & 0x00FF_FFFF == selected & 0x00FF_FFFF
                    Button { onSelect(item.argb) } label: {
                        ZStack {
                            Circle().strokeBorder(isSelected ? theme.accent : theme.raised, lineWidth: isSelected ? 2 : 1)
                            Circle().fill(Color(argb: item.argb)).padding(4)
                            if isSelected {
                                Image(systemName: "checkmark").font(.system(size: 13, weight: .bold))
                                    .foregroundStyle(EditorTheme.readableOn(item.argb) == 0xFFFF_FFFF ? Color.white : Color.black)
                            }
                        }
                        .frame(width: 36, height: 36)
                        .frame(width: 48, height: 48)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(PressFadeStyle())
                    .accessibilityLabel(strings(item.key))
                    .accessibilityAddTraits(isSelected ? [.isSelected] : [])
                }
            }
            .padding(.horizontal, 16)
        }
    }
}

/// 이름과 값이 보이는 슬라이더.
struct ValueSlider: View {
    let label: String
    let value: Double
    let range: ClosedRange<Double>
    var step: Double?
    let format: (Double) -> String
    let onChange: (Double) -> Void
    var onFinish: () -> Void = {}
    @Environment(\.editorTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(label).scaledFont(14, weight: .medium).foregroundStyle(theme.foregroundMuted)
                Spacer()
                Text(format(value)).scaledFont(13, weight: .semibold, monospacedDigit: true)
                    .foregroundStyle(theme.foreground)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(theme.raised, in: Capsule())
            }
            Slider(value: Binding(get: { value }, set: { onChange($0) }), in: range) { editing in
                if !editing { onFinish() }
            }
            .tint(theme.accent)
            .frame(minHeight: 44)
            .accessibilityLabel(label)
            .accessibilityValue(format(value))
        }
        .padding(.horizontal, 16)
    }
}

/// 켜고 끄는 칩.
struct ToggleChip: View {
    let title: String
    let isOn: Bool
    let action: () -> Void

    var body: some View {
        ChoiceChip(title: title, selected: isOn, action: action)
            .accessibilityAddTraits(.isToggle)
    }
}

private func percent(_ v: Double) -> String { "\(Int((v * 100).rounded()))%" }

// MARK: - 글자

struct TextPanel<Host: LayerToolHost>: View {
    let model: Host
    @Environment(\.strings) private var strings
    @FocusState private var focused: Bool

    private let fonts = ["sans", "sans-bold", "serif", "serif-bold", "mono", "handwriting"]

    var body: some View {
        if let editing = model.editingText {
            let style = editing.style
            VStack(alignment: .leading, spacing: 8) {
                TextField(strings("text_hint"), text: Binding(get: { editing.text }, set: { model.setText($0) }), axis: .vertical)
                    .lineLimit(1...4)
                    .focused($focused)
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 10).fill(Color.gray.opacity(0.18)))
                    .padding(.horizontal, 16)
                    .onAppear { focused = editing.text.isEmpty }
                chips((model.catalog.showDefaultFonts ? fonts : ["sans"]) + FontAssets.ids, selected: style.fontId, title: { id in
                    FontAssets.label(for: id) ?? strings("font_\(id.replacingOccurrences(of: "-", with: "_"))")
                }) { id in model.setTextStyle { $0.fontId = id } }
                label("text_color")
                ColorPalette(selected: style.color) { color in model.setTextStyle { $0.color = color } }
                chips(TextAlign.allCases.map(\.rawValue), selected: style.alignment.rawValue, title: { strings("align_\($0)") }) { raw in
                    model.setTextStyle { $0.alignment = TextAlign(rawValue: raw) ?? .center }
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ToggleChip(title: strings("text_outline"), isOn: style.stroke != nil) {
                            model.setTextStyle { s in
                                s.stroke = s.stroke == nil ? .init(color: contrast(s.color), widthRatio: 0.006) : nil
                            }
                        }
                        ToggleChip(title: strings("text_background"), isOn: style.background != nil) {
                            model.setTextStyle { s in
                                s.background = s.background == nil ? .init(color: contrast(s.color) & 0xCCFF_FFFF) : nil
                            }
                        }
                        ToggleChip(title: strings("text_shadow"), isOn: style.shadow != nil) {
                            model.setTextStyle { s in
                                s.shadow = s.shadow == nil ? .init(color: 0x9900_0000, offsetXRatio: 0.002, offsetYRatio: 0.003, blurRatio: 0.006) : nil
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                }
                if let stroke = style.stroke {
                    label("text_outline_color")
                    ColorPalette(selected: stroke.color) { c in model.setTextStyle { $0.stroke?.color = c } }
                    ValueSlider(label: strings("text_outline_width"), value: stroke.widthRatio / 0.002, range: 1...10,
                                format: { "\(Int($0.rounded()))" }) { v in model.setTextStyle { $0.stroke?.widthRatio = v.rounded() * 0.002 } }
                }
                if let background = style.background {
                    label("text_background_color")
                    ColorPalette(selected: background.color) { c in
                        model.setTextStyle { $0.background?.color = (c & 0x00FF_FFFF) | (background.color & 0xFF00_0000) }
                    }
                    ValueSlider(label: strings("text_background_opacity"), value: Double(background.color >> 24) / 255, range: 0.1...1,
                                format: percent) { v in
                        model.setTextStyle { $0.background?.color = (background.color & 0x00FF_FFFF) | (UInt32((v * 255).rounded()) << 24) }
                    }
                }
                if let shadow = style.shadow {
                    ValueSlider(label: strings("text_shadow_blur"), value: shadow.blurRatio / 0.03, range: 0...1, format: percent) { v in
                        model.setTextStyle { $0.shadow?.blurRatio = v * 0.03 }
                    }
                    ValueSlider(label: strings("text_shadow_opacity"), value: Double(shadow.color >> 24) / 255, range: 0...1, format: percent) { v in
                        model.setTextStyle { $0.shadow?.color = (shadow.color & 0x00FF_FFFF) | (UInt32((v * 255).rounded()) << 24) }
                    }
                }
                ValueSlider(label: strings("text_opacity"), value: model.selectedOverlay?.transform.opacity ?? 1, range: 0.1...1, format: percent) { v in
                    model.mutateSelected { $0.transform.opacity = v }
                }
                ValueSlider(label: strings("text_letter_spacing"), value: style.letterSpacingEm, range: -0.1...0.5,
                            format: { String(format: "%.2f", $0) }) { v in model.setTextStyle { $0.letterSpacingEm = v } }
                ValueSlider(label: strings("text_line_spacing"), value: style.lineSpacing, range: 0.8...2,
                            format: { String(format: "%.1f×", $0) }) { v in model.setTextStyle { $0.lineSpacing = v } }
            }
        }
    }

    private func label(_ key: String) -> some View {
        Text(strings(key)).scaledFont(14, weight: .medium).foregroundStyle(.secondary).padding(.horizontal, 16)
    }

    private func contrast(_ argb: UInt32) -> UInt32 { EditorTheme.readableOn(argb) == 0xFFFF_FFFF ? 0xFFFF_FFFF : 0xFF00_0000 }
}

/// 가로로 스크롤하는 칩 줄.
@MainActor
func chips(_ ids: [String], selected: String, title: @escaping (String) -> String, onSelect: @escaping (String) -> Void) -> some View {
    ScrollView(.horizontal, showsIndicators: false) {
        HStack(spacing: 8) {
            ForEach(ids, id: \.self) { id in
                ChoiceChip(title: title(id), selected: id == selected) { onSelect(id) }
            }
        }
        .padding(.horizontal, 16)
    }
}

// MARK: - 스티커

struct StickerPanel<Host: LayerToolHost>: View {
    @Bindable var model: Host
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    private var groups: [(key: String, items: [String])] {
        var result: [(String, [String])] = []
        let custom = model.customStickerIds
        if !custom.isEmpty { result.append(("sticker_custom", custom)) }
        guard model.catalog.showDefaultStickers else { return result }
        result += EditorModel.emojiGroups.map { ($0.key, $0.emoji.map { EmojiCatalog.prefix + $0 }) }
        return result
    }

    var body: some View {
        let groups = groups
        let index = min(model.stickerCategory, max(0, groups.count - 1))
        VStack(spacing: 8) {
            chips(groups.indices.map(String.init), selected: String(index), title: { strings(groups[Int($0)!].key) }) {
                model.stickerCategory = Int($0) ?? 0
            }
            if !groups.isEmpty {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 52), spacing: 4)], spacing: 4) {
                        ForEach(groups[index].items, id: \.self) { assetId in
                            Button { model.addSticker(assetId) } label: {
                                Group {
                                    if let emoji = EmojiCatalog.emoji(of: assetId) {
                                        Text(emoji).font(.system(size: 28))
                                    } else if let image = StickerAssets.image(for: assetId) {
                                        Image(uiImage: image).resizable().scaledToFit().padding(6)
                                    }
                                }
                                .frame(width: 52, height: 52)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(PressFadeStyle())
                            .accessibilityLabel(EmojiCatalog.emoji(of: assetId) ?? StickerAssets.label(for: assetId) ?? assetId)
                        }
                    }
                    .padding(.horizontal, 12)
                }
                .frame(height: 184)
            }
        }
    }
}

// MARK: - 그리기

struct DrawPanel: View {
    @Bindable var model: EditorModel
    @Environment(\.strings) private var strings

    var body: some View {
        let brush = model.brush
        VStack(alignment: .leading, spacing: 8) {
            let modes = ["pen", "marker", "highlighter", "shapes", "eraser"]
            let current = brush.shapes ? "shapes" : brush.kind.rawValue
            chips(modes, selected: current, title: { $0 == "shapes" ? strings("draw_shapes") : strings("brush_\($0)") }) { mode in
                if mode == "shapes" {
                    model.brush.shapes = true
                } else {
                    model.brush.shapes = false
                    model.brush.kind = BrushKind(rawValue: mode) ?? .pen
                }
            }
            if brush.shapes {
                chips(ShapeKind.allCases.map(\.rawValue), selected: brush.shape.rawValue, title: { raw in
                    strings("shape_\(raw.replacingOccurrences(of: "([a-z])([A-Z])", with: "$1_$2", options: .regularExpression).lowercased())")
                }) { model.brush.shape = ShapeKind(rawValue: $0) ?? .rectangle }
                let fills = brush.shape.isLine ? [ShapeFill.outline, .dashed] : ShapeFill.allCases
                chips(fills.map(\.rawValue), selected: brush.fill.rawValue, title: { strings("shape_\($0)") }) {
                    model.brush.fill = ShapeFill(rawValue: $0) ?? .outline
                }
                Text(strings("draw_shapes_help")).scaledFont(13).foregroundStyle(.secondary).padding(.horizontal, 16)
            }
            if brush.shapes || brush.kind != .eraser {
                ColorPalette(selected: brush.color) { model.brush.color = $0 }
            }
            if !(brush.shapes && brush.fill == .filled) {
                ValueSlider(label: strings("brush_width"), value: brush.widthDisplay, range: 1...100, format: { "\(Int($0.rounded()))" }) {
                    model.brush.widthRatio = BrushSettings.width(display: $0)
                }
            }
            if brush.shapes || brush.kind != .eraser {
                ValueSlider(label: strings("brush_opacity"), value: brush.opacity, range: 0.1...1, format: percent) { model.brush.opacity = $0 }
            }
        }
    }
}

// MARK: - 가리기

struct PrivacyPanel<Host: LayerToolHost>: View {
    @Bindable var model: Host
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        let settings = model.privacy
        VStack(alignment: .leading, spacing: 8) {
            chips(["mosaic", "blur"], selected: settings.mosaic ? "mosaic" : "blur", title: { strings("privacy_\($0)") }) {
                model.privacy.mosaic = $0 == "mosaic"
            }
            chips(model.privacyModes.map(\.rawValue), selected: settings.mode.rawValue, title: { strings("privacy_\($0)") }) {
                model.privacy.mode = PrivacySettings.Mode(rawValue: $0) ?? .brush
            }
            ValueSlider(label: strings("privacy_strength"), value: settings.strength * 100, range: 0...100, format: { "\(Int($0.rounded()))" }) {
                model.privacy.strength = $0 / 100
            }
            if settings.mode == .brush {
                ValueSlider(label: strings("privacy_brush_size"), value: settings.brushRatio * 1000, range: 20...150, format: { "\(Int($0.rounded()))" }) {
                    model.privacy.brushRatio = $0 / 1000
                }
            }
            if !settings.mosaic {
                Text(strings("privacy_help")).scaledFont(13).foregroundStyle(theme.foregroundMuted).padding(.horizontal, 16)
            }
        }
    }
}

// MARK: - 워터마크

struct WatermarkPanel<Host: LayerToolHost>: View {
    let model: Host
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        let spec = model.watermarkDraft ?? WatermarkSpec(text: "")
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                TextField(strings("watermark_hint"), text: Binding(get: { spec.text }, set: { v in model.setWatermark { $0.text = v } }))
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 10).fill(Color.gray.opacity(0.18)))
                if model.hasCommittedWatermark {
                    Button(strings("watermark_remove")) { model.removeWatermark() }.foregroundStyle(theme.error)
                }
            }
            .padding(.horizontal, 16)
            if !model.watermarkSuggestions.isEmpty {
                let suggestions = model.watermarkSuggestions
                chips(suggestions, selected: spec.text, title: { $0 }) { v in model.setWatermark { $0.text = v } }
            }
            chips(WatermarkPattern.allCases.map(\.rawValue), selected: spec.pattern.rawValue, title: { strings("watermark_\($0)") }) { raw in
                model.setWatermark { $0.pattern = WatermarkPattern(rawValue: raw) ?? .single }
            }
            if spec.pattern == .single || spec.pattern == .band {
                positionGrid(spec)
            }
            if spec.pattern == .tile {
                ValueSlider(label: strings("watermark_angle"), value: spec.rotationDegrees, range: -90...90, format: { "\(Int($0.rounded()))°" }) { v in
                    model.setWatermark { $0.rotationDegrees = v.rounded() }
                }
                ValueSlider(label: strings("watermark_density"), value: spec.density, range: 0.5...2, format: { String(format: "%.1f", $0) }) { v in
                    model.setWatermark { $0.density = v }
                }
            }
            if spec.pattern != .diagonal {
                ValueSlider(label: strings("watermark_size"), value: spec.sizeRatio, range: 0.015...0.12, format: { percent($0 / 0.04) }) { v in
                    model.setWatermark { $0.sizeRatio = v }
                }
            }
            ValueSlider(label: strings("watermark_opacity"), value: spec.opacity, range: 0.05...1, format: percent) { v in
                model.setWatermark { $0.opacity = v }
            }
            ColorPalette(selected: spec.color) { c in model.setWatermark { $0.color = c } }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    if spec.pattern == .single || spec.pattern == .tile {
                        ToggleChip(title: strings("watermark_box"), isOn: spec.background) { model.setWatermark { $0.background.toggle() } }
                    }
                    ToggleChip(title: strings("watermark_shadow"), isOn: spec.shadow) { model.setWatermark { $0.shadow.toggle() } }
                }
                .padding(.horizontal, 16)
            }
        }
    }

    private func positionGrid(_ spec: WatermarkSpec) -> some View {
        let positions = spec.pattern == .band
            ? [WatermarkPosition.topStart, .topCenter, .topEnd, .bottomStart, .bottomCenter, .bottomEnd]
            : WatermarkPosition.allCases
        return VStack(alignment: .leading, spacing: 4) {
            Text(strings("watermark_position")).scaledFont(14, weight: .medium).foregroundStyle(theme.foregroundMuted)
            LazyVGrid(columns: Array(repeating: GridItem(.fixed(48), spacing: 4), count: 3), spacing: 4) {
                ForEach(positions, id: \.self) { position in
                    let selected = position == spec.position
                    Button { model.setWatermark { $0.position = position } } label: {
                        RoundedRectangle(cornerRadius: 8)
                            .fill(selected ? theme.accent : theme.raised)
                            .frame(width: 44, height: 32)
                            .frame(width: 48, height: 48)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(PressFadeStyle())
                    .accessibilityLabel(strings("watermark_\(position.rawValue.replacingOccurrences(of: "([a-z])([A-Z])", with: "$1_$2", options: .regularExpression).lowercased())"))
                    .accessibilityAddTraits(selected ? [.isSelected] : [])
                }
            }
        }
        .padding(.horizontal, 16)
    }
}
