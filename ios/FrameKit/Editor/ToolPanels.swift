import SwiftUI
internal import FrameKitCore

extension ImageTool {
    var labelKey: String {
        switch self {
        case .crop: "tool_crop"
        case .rotate: "tool_rotate"
        case .document: "tool_document"
        case .adjust: "tool_adjust"
        case .filter: "tool_filter"
        case .text: "tool_text"
        case .sticker: "tool_sticker"
        case .draw: "tool_draw"
        case .privacy: "tool_privacy"
        case .watermark: "tool_watermark"
        }
    }

    var icon: Image {
        switch self {
        case .crop: Icons.image("crop")
        case .rotate: Icons.image("rotate_right")
        case .document: Icons.image("document")
        case .adjust: Icons.image("adjust")
        case .filter: Icons.image("filter")
        case .text: Icons.image("text")
        case .sticker: Icons.image("sticker")
        case .draw: Icons.image("draw")
        case .privacy: Icons.image("privacy")
        case .watermark: Icons.image("watermark")
        }
    }

    var resettable: Bool { [.crop, .rotate, .adjust, .filter].contains(self) }
}

extension AdjustKind {
    var labelKey: String { "adjust_\(rawValue)" }

    var symbol: String {
        switch self {
        case .brightness: "sun.max"
        case .exposure: "plusminus.circle"
        case .contrast: "circle.lefthalf.filled"
        case .highlights: "circle.tophalf.filled"
        case .shadows: "circle.bottomhalf.filled"
        case .saturation: "drop"
        case .temperature: "thermometer.medium"
        case .tint: "eyedropper"
        case .sharpness: "triangle"
        case .fade: "circle.dotted"
        case .vignette: "smallcircle.filled.circle"
        case .grain: "circle.grid.3x3"
        }
    }
}

/// 열린 도구의 조작부와 아래 적용 막대.
struct ToolPanel: View {
    let model: EditorModel
    let tool: ImageTool
    @Environment(\.strings) private var strings

    var body: some View {
        VStack(spacing: 0) {
            ScrollView(.vertical, showsIndicators: false) {
                Group {
                    switch tool {
                    case .crop: CropPanel(model: model)
                    case .rotate: RotatePanel(model: model)
                    case .document: DocumentPanel(model: model)
                    case .adjust: AdjustPanel(model: model)
                    case .filter: FilterPanel(model: model)
                    case .text: TextPanel(model: model)
                    case .sticker: StickerPanel(model: model)
                    case .draw: DrawPanel(model: model)
                    case .privacy: PrivacyPanel(model: model)
                    case .watermark: WatermarkPanel(model: model)
                    }
                }
                .padding(.vertical, 8)
            }
            .scrollBounceBehavior(.basedOnSize)
            HStack {
                if tool.resettable {
                    EditorButton(title: strings("action_reset"), systemImage: "arrow.counterclockwise", kind: .tonal) { model.resetTool() }
                }
                Spacer(minLength: 12)
                EditorButton(title: strings("action_apply"), systemImage: "checkmark") { model.applyTool() }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
    }
}

// MARK: - 자르기

struct CropPanel: View {
    let model: EditorModel
    @Environment(\.strings) private var strings
    @State private var showCustom = false
    @State private var customWidth = ""
    @State private var customHeight = ""

    var body: some View {
        VStack(spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(CropAspect.presets, id: \.self) { aspect in
                        ChoiceChip(title: title(aspect), selected: model.cropAspect == aspect) { model.setAspect(aspect) }
                    }
                    ChoiceChip(title: customTitle, selected: isCustom) {
                        if case .fixed(let w, let h) = model.cropAspect { customWidth = "\(w)"; customHeight = "\(h)" }
                        showCustom = true
                    }
                }
                .padding(.horizontal, 16)
            }
            HStack(spacing: 24) {
                IconButton(icon: Icons.image("rotate_left"), label: strings("rotate_left")) { model.rotate(clockwise: false) }
                IconButton(icon: Icons.image("rotate_right"), label: strings("rotate_right")) { model.rotate(clockwise: true) }
                IconButton(icon: Icons.image("flip_horizontal"), label: strings("flip_horizontal")) { model.flip(horizontal: true) }
            }
        }
        .alert(strings("ratio_custom"), isPresented: $showCustom) {
            TextField(strings("ratio_width"), text: $customWidth).keyboardType(.numberPad)
            TextField(strings("ratio_height"), text: $customHeight).keyboardType(.numberPad)
            Button(strings("action_cancel"), role: .cancel) {}
            Button(strings("action_apply")) {
                if let w = Int(customWidth.prefix(4)), let h = Int(customHeight.prefix(4)), (1...9999).contains(w), (1...9999).contains(h) {
                    model.setAspect(.fixed(w, h))
                }
            }
        }
    }

    private var isCustom: Bool {
        if case .fixed = model.cropAspect { return !CropAspect.presets.contains(model.cropAspect) }
        return false
    }

    private var customTitle: String {
        if isCustom, case .fixed(let w, let h) = model.cropAspect { return strings("ratio_custom_value", "\(w):\(h)") }
        return strings("ratio_custom")
    }

    private func title(_ aspect: CropAspect) -> String {
        switch aspect {
        case .free: strings("ratio_free")
        case .original: strings("ratio_original")
        case .fixed(let w, let h): "\(w):\(h)"
        }
    }
}

// MARK: - 회전

struct RotatePanel: View {
    let model: EditorModel
    @Environment(\.strings) private var strings

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Spacer()
                IconButton(icon: Icons.image("rotate_left"), label: strings("rotate_left")) { model.rotate(clockwise: false) }
                Spacer()
                IconButton(icon: Icons.image("rotate_right"), label: strings("rotate_right")) { model.rotate(clockwise: true) }
                Spacer()
                IconButton(icon: Icons.image("flip_horizontal"), label: strings("flip_horizontal")) { model.flip(horizontal: true) }
                Spacer()
                IconButton(icon: Icons.image("flip_vertical"), label: strings("flip_vertical")) { model.flip(horizontal: false) }
                Spacer()
            }
            DialSlider(
                label: strings("straighten"),
                value: model.displayed.geometry.straightenDegrees,
                range: GeometryEdit.companion.MIN_STRAIGHTEN_DEGREES...GeometryEdit.companion.MAX_STRAIGHTEN_DEGREES,
                spacing: 10, tickEvery: 1, majorEvery: 5, snapThreshold: 0.4,
                format: { strings("degrees", String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), $0)) },
                onChange: { model.setStraighten($0) },
                onFinish: { model.finishStraighten() }
            )
            .padding(.horizontal, 16)
        }
    }
}

// MARK: - 보정

struct AdjustPanel: View {
    @Bindable var model: EditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme
    @Environment(\.haptics) private var haptics

    var body: some View {
        let kind = model.adjustKind
        VStack(spacing: 10) {
            DialSlider(
                label: strings(kind.labelKey),
                value: kind.toDisplay(model.displayed.value(kind)),
                range: (kind.signed ? -100 : 0)...100,
                snapThreshold: kind.signed ? 1.5 : 0,
                format: { v in
                    let n = Int(v.rounded())
                    return kind.signed && n > 0 ? "+\(n)" : "\(n)"
                },
                onChange: { model.setAdjustment(kind, display: $0) },
                onFinish: { model.commitGesture() }
            )
            .id(kind)
            .padding(.horizontal, 16)
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 4) {
                        ForEach(AdjustKind.allCases, id: \.self) { item in
                            ring(item, selected: item == kind).id(item)
                        }
                    }
                    .padding(.horizontal, 12)
                }
                .onChange(of: kind) { _, new in withAnimation { proxy.scrollTo(new, anchor: .center) } }
            }
        }
    }

    private func ring(_ item: AdjustKind, selected: Bool) -> some View {
        let display = item.toDisplay(model.displayed.value(item))
        let fraction = min(1, abs(display) / 100)
        return Button {
            haptics.selection()
            model.adjustKind = item
        } label: {
            VStack(spacing: 4) {
                ZStack {
                    Circle().stroke(selected ? theme.foreground.opacity(0.85) : theme.raised, lineWidth: 2.5)
                    if fraction > 0 {
                        Circle().trim(from: 0, to: fraction)
                            .stroke(theme.accent, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                            .scaleEffect(x: display < 0 ? -1 : 1, y: 1)
                    }
                    if selected && Int(display.rounded()) != 0 {
                        Text("\(Int(display.rounded()))").font(.system(size: 15, weight: .semibold).monospacedDigit())
                            .foregroundStyle(theme.foreground)
                    } else {
                        Image(systemName: item.symbol).font(.system(size: 18)).foregroundStyle(theme.foreground)
                    }
                }
                .frame(width: 52, height: 52)
                Text(strings(item.labelKey)).font(.system(size: 11, weight: .medium)).lineLimit(1)
                    .foregroundStyle(selected ? theme.foreground : theme.foregroundMuted)
            }
            .frame(width: 64)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressFadeStyle())
        .accessibilityLabel(strings(item.labelKey))
        .accessibilityValue("\(Int(display.rounded()))")
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

// MARK: - 필터

struct FilterPanel: View {
    let model: EditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme
    @Environment(\.haptics) private var haptics

    var body: some View {
        let edit = model.displayed
        VStack(spacing: 10) {
            Group {
                if edit.filterId == "original" {
                    Text(strings("filter_hint")).font(.system(size: 14)).foregroundStyle(theme.foregroundMuted)
                        .multilineTextAlignment(.center)
                } else {
                    DialSlider(
                        label: strings("filter_intensity"),
                        value: edit.filterIntensity * 100,
                        range: 0...100,
                        resetValue: 100,
                        snapThreshold: 0,
                        format: { "\(Int($0.rounded()))" },
                        onChange: { model.setFilterIntensity(display: $0) },
                        onFinish: { model.commitGesture() }
                    )
                }
            }
            .frame(height: 72)
            .padding(.horizontal, 16)
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 10) {
                        ForEach(FilterCatalog.shared.all.map(\.id), id: \.self) { id in
                            thumbnail(id, selected: id == edit.filterId).id(id)
                        }
                    }
                    .padding(.horizontal, 16)
                }
                .onAppear { proxy.scrollTo(edit.filterId, anchor: .center) }
            }
        }
    }

    private func thumbnail(_ id: String, selected: Bool) -> some View {
        let label = strings("filter_\(id.replacingOccurrences(of: "-", with: "_"))")
        return Button {
            haptics.selection()
            model.selectFilter(id)
        } label: {
            VStack(spacing: 6) {
                ZStack(alignment: .bottomTrailing) {
                    Group {
                        if let image = model.filterThumbnails[id] {
                            Image(decorative: image, scale: 1).resizable().scaledToFill()
                        } else {
                            theme.raised
                        }
                    }
                    .frame(width: 68, height: 68)
                    .clipShape(RoundedRectangle(cornerRadius: theme.radius / 2))
                    .overlay {
                        RoundedRectangle(cornerRadius: theme.radius / 2).strokeBorder(theme.accent, lineWidth: selected ? 2.5 : 0)
                    }
                    if selected {
                        Image(systemName: "checkmark.circle.fill").font(.system(size: 18))
                            .foregroundStyle(theme.onAccent, theme.accent)
                            .offset(x: 4, y: 4)
                    }
                }
                Text(label).font(.system(size: 11, weight: .medium)).lineLimit(1)
                    .foregroundStyle(selected ? theme.foreground : theme.foregroundMuted)
                    .frame(width: 72)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(PressFadeStyle())
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? [.isSelected, .isButton] : [.isButton])
    }
}
