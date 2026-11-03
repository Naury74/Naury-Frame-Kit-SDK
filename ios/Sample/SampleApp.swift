import FrameKit
import SwiftUI

@main
struct SampleApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct ContentView: View {
    @State private var request: ImageEditorRequest?
    @State private var format: ImageFormat = .jpeg
    @State private var theme: ThemeMode = .dark
    @State private var locale = ""
    @State private var hostWatermark = ProcessInfo.processInfo.arguments.contains("-watermark")
    @State private var lastResult = ""
    @State private var resultImage: UIImage?
    @State private var showEditor = false

    var body: some View {
        NavigationStack {
            Form {
                Section("열기") {
                    Button("사진 고르기") { open(.pick) }
                    Button("샘플 이미지") { open(.data(SampleImage.make())) }
                    Button("여러 장 고르기") { open(.pickMultiple(maxItems: 10)) }
                    Button("여러 장 샘플(3장)") { open(.files(SampleImage.files(count: 3))) }
                }
                Section("설정") {
                    Picker("저장 형식", selection: $format) {
                        ForEach(ImageFormat.allCases, id: \.self) { Text($0.rawValue.uppercased()).tag($0) }
                    }
                    Picker("테마", selection: $theme) {
                        Text("어둡게").tag(ThemeMode.dark)
                        Text("밝게").tag(ThemeMode.light)
                        Text("시스템").tag(ThemeMode.system)
                    }
                    Toggle("호스트 워터마크(고정)", isOn: $hostWatermark)
                    Picker("언어", selection: $locale) {
                        Text("기기 설정").tag("")
                        Text("한국어").tag("ko")
                        Text("English").tag("en")
                        Text("日本語").tag("ja")
                    }
                }
                if !lastResult.isEmpty {
                    Section("결과") {
                        Text(lastResult).font(.footnote.monospaced())
                        if let resultImage {
                            Image(uiImage: resultImage).resizable().scaledToFit().frame(maxHeight: 320)
                        }
                    }
                }
            }
            .navigationTitle("FrameKit \(FrameKitInfo.version)")
        }
        .onAppear {
            // 자동 확인용: -openSample 인자로 실행하면 샘플 이미지를 바로 연다.
            let arguments = ProcessInfo.processInfo.arguments
            if arguments.contains("-pdf") { format = .pdf }
            if arguments.contains("-openSample") { open(.data(SampleImage.make())) }
            if arguments.contains("-openSamples") { open(.files(SampleImage.files(count: 3))) }
        }
        .frameKitImageEditor(isPresented: $showEditor, request: request ?? ImageEditorRequest(input: .pick)) { result in
            handle(result)
        }
    }

    private func open(_ input: EditorInput) {
        let watermark = hostWatermark
            ? WatermarkConfig(text: "© FrameKit", style: .tile, opacity: 0.5, applyOnOpen: true, locked: true)
            : nil
        request = ImageEditorRequest(
            input: input,
            config: ImageEditorConfig(watermark: watermark),
            export: ImageExportConfig(format: format),
            ui: EditorUIConfig(themeMode: theme, localeIdentifier: locale.isEmpty ? nil : locale)
        )
        showEditor = true
    }

    private func handle(_ result: FrameKitResult) {
        switch result {
        case .success(let output, let outputs):
            lastResult = outputs.map { "\($0.width)×\($0.height) \($0.mimeType) \($0.fileSize / 1024)KB \($0.pageCount.map { "\($0)쪽" } ?? "")\n\($0.url.lastPathComponent)" }
                .joined(separator: "\n")
            if let text = output.recognizedText, !text.isEmpty { lastResult += "\n인식한 글자: \(text.prefix(80))" }
            resultImage = UIImage(contentsOfFile: output.url.path)
        case .cancelled:
            lastResult = "취소됨"
            resultImage = nil
        case .failure(let error):
            lastResult = "실패: \(error.code.rawValue) (\(error.diagnosticId))"
            resultImage = nil
        }
    }
}

/// 사진 없이 시험할 수 있는 3000×2000 JPEG.
enum SampleImage {
    /// 번호를 적은 샘플 파일들.
    static func files(count: Int) -> [URL] {
        (1...count).map { index in
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("sample-\(index).jpg")
            try? make(label: "Page \(index)").write(to: url)
            return url
        }
    }

    static func make(label: String = "FrameKit") -> Data {
        let size = CGSize(width: 3000, height: 2000)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
            let colors = [UIColor.systemTeal.cgColor, UIColor.systemIndigo.cgColor, UIColor.systemOrange.cgColor] as CFArray
            let gradient = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB), colors: colors, locations: [0, 0.6, 1])!
            context.cgContext.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: size.width, y: size.height), options: [])
            UIColor.white.withAlphaComponent(0.85).setFill()
            UIBezierPath(ovalIn: CGRect(x: 1900, y: 300, width: 700, height: 700)).fill()
            UIColor.black.withAlphaComponent(0.35).setFill()
            UIBezierPath(rect: CGRect(x: 0, y: 1500, width: 3000, height: 500)).fill()
            let text = label as NSString
            text.draw(at: CGPoint(x: 220, y: 760), withAttributes: [.font: UIFont.systemFont(ofSize: 360, weight: .heavy), .foregroundColor: UIColor.white])
        }
        return image.jpegData(compressionQuality: 0.92)!
    }
}
