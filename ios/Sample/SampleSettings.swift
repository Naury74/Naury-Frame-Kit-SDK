import FrameKit
import SwiftUI

/// 샘플 앱에서 바꿔 보는 SDK 설정.
@MainActor
@Observable
final class SampleSettings {
    var themeMode = ThemeMode.dark
    var accent = Color(red: 0.39, green: 0.36, blue: 1)
    var cornerRadius = 14.0
    var localeIdentifier = ""
    var enableHaptics = true
    var showExportProgress = true
    var imageFormat = ImageFormat.jpeg
    var hostWatermark = false
    var chooser = EditorChooser.auto
    var openAnimatedInMotionEditor = true
    var motionFormats: Set<MotionFormat> = Set(MotionFormat.allCases)
    var audioBitrate = 128_000.0

    var ui: EditorUIConfig {
        EditorUIConfig(themeMode: themeMode, primaryARGB: Self.argb(accent), cornerRadius: Int(cornerRadius),
                       showExportProgress: showExportProgress, enableHaptics: enableHaptics,
                       localeIdentifier: localeIdentifier.isEmpty ? nil : localeIdentifier)
    }

    var watermark: WatermarkConfig? {
        hostWatermark ? WatermarkConfig(text: "© FrameKit", style: .tile, opacity: 0.5, applyOnOpen: true, locked: true) : nil
    }

    func image(_ input: EditorInput, pdf: Bool = false) -> ImageEditorRequest {
        ImageEditorRequest(input: input, config: ImageEditorConfig(watermark: watermark),
                           export: ImageExportConfig(format: pdf ? .pdf : imageFormat), ui: ui)
    }

    func video(_ input: EditorInput) -> VideoEditorRequest {
        VideoEditorRequest(input: input, config: VideoEditorConfig(watermark: watermark), ui: ui)
    }

    func motion(_ input: EditorInput) -> MotionEditorRequest {
        let formats = MotionFormat.allCases.filter { motionFormats.contains($0) }
        return MotionEditorRequest(input: input, export: MotionExportConfig(formats: formats.isEmpty ? [.mp4] : formats,
                                                                            format: formats.first ?? .mp4), ui: ui)
    }

    func audio(_ input: EditorInput) -> AudioEditorRequest {
        AudioEditorRequest(input: input, export: AudioExportConfig(bitrate: Int(audioBitrate)), ui: ui)
    }

    func router(_ input: EditorInput, kind: MediaKind = .any, chooser: EditorChooser? = nil) -> FrameKitRequest {
        let motion = self.motion(.pick)
        return FrameKitRequest(input: input, mediaKind: kind, image: ImageEditorConfig(watermark: watermark),
                               imageExport: ImageExportConfig(format: imageFormat), video: VideoEditorConfig(watermark: watermark),
                               motionExport: motion.export, audioExport: AudioExportConfig(bitrate: Int(audioBitrate)), ui: ui,
                               openAnimatedInMotionEditor: openAnimatedInMotionEditor, chooser: chooser ?? self.chooser)
    }

    private static func argb(_ color: Color) -> UInt32 {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a)
        func byte(_ v: CGFloat) -> UInt32 { UInt32(max(0, min(255, (v * 255).rounded()))) }
        return 0xFF00_0000 | byte(r) << 16 | byte(g) << 8 | byte(b)
    }
}

struct SettingsView: View {
    @Environment(SampleSettings.self) private var settings

    var body: some View {
        @Bindable var settings = settings
        NavigationStack {
            Form {
                Section("모양") {
                    Picker("테마", selection: $settings.themeMode) {
                        Text("어둡게").tag(ThemeMode.dark)
                        Text("밝게").tag(ThemeMode.light)
                        Text("시스템").tag(ThemeMode.system)
                    }
                    ColorPicker("강조색", selection: $settings.accent, supportsOpacity: false)
                    LabeledContent("모서리 \(Int(settings.cornerRadius))pt") {
                        Slider(value: $settings.cornerRadius, in: 0...32, step: 1).frame(maxWidth: 180)
                    }
                    Picker("언어", selection: $settings.localeIdentifier) {
                        Text("기기 설정").tag("")
                        Text("한국어").tag("ko")
                        Text("English").tag("en")
                        Text("日本語").tag("ja")
                        Text("Deutsch").tag("de")
                        Text("简体中文").tag("zh-Hans")
                    }
                    Toggle("햅틱", isOn: $settings.enableHaptics)
                    Toggle("저장 단계 보이기", isOn: $settings.showExportProgress)
                    let warnings = EditorPalette().contrastWarnings(themeMode: settings.themeMode, primaryARGB: settings.ui.primaryARGB)
                    if !warnings.isEmpty {
                        Label("명암비가 낮아요: \(warnings.joined(separator: ", "))", systemImage: "exclamationmark.triangle")
                            .font(.footnote).foregroundStyle(.orange)
                    }
                }
                Section("열기") {
                    Picker("고르기 방식", selection: $settings.chooser) {
                        Text("바로 열기").tag(EditorChooser.auto)
                        Text("할 일 묻기").tag(EditorChooser.ask)
                    }
                    Toggle("GIF는 움직이는 이미지로", isOn: $settings.openAnimatedInMotionEditor)
                }
                Section("저장") {
                    Picker("사진 형식", selection: $settings.imageFormat) {
                        ForEach(ImageFormat.allCases, id: \.self) { Text($0.rawValue.uppercased()).tag($0) }
                    }
                    Toggle("움직이는 이미지 MP4", isOn: formatBinding(.mp4))
                    Toggle("움직이는 이미지 GIF", isOn: formatBinding(.gif))
                    LabeledContent("음성 \(Int(settings.audioBitrate / 1000))kbps") {
                        Slider(value: $settings.audioBitrate, in: 32_000...320_000, step: 32_000).frame(maxWidth: 180)
                    }
                    Toggle("고정 워터마크", isOn: $settings.hostWatermark)
                }
                Section {
                    LabeledContent("SDK", value: FrameKitInfo.version)
                    Link("문서 열기", destination: URL(string: "https://github.com/Naury74/Naury-Frame-Kit-SDK/blob/main/docs/ios.md")!)
                }
            }
            .navigationTitle("설정")
        }
    }

    private func formatBinding(_ format: MotionFormat) -> Binding<Bool> {
        Binding(get: { settings.motionFormats.contains(format) }, set: { on in
            if on { settings.motionFormats.insert(format) } else if settings.motionFormats.count > 1 { settings.motionFormats.remove(format) }
        })
    }
}
