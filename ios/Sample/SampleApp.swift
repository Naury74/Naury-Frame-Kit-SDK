import AVFoundation
import FrameKit
import ImageIO
import SwiftUI
import UniformTypeIdentifiers

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
    @State private var videoRequest: VideoEditorRequest?
    @State private var showVideo = false
    @State private var motionRequest: MotionEditorRequest?
    @State private var showMotion = false
    @State private var audioRequest: AudioEditorRequest?
    @State private var showAudio = false

    var body: some View {
        NavigationStack {
            Form {
                Section("열기") {
                    Button("사진 고르기") { open(.pick) }
                    Button("카메라로 찍기") { open(.capture) }
                    Button("샘플 이미지") { open(.data(SampleImage.make())) }
                    Button("여러 장 고르기") { open(.pickMultiple(maxItems: 10)) }
                    Button("여러 장 샘플(3장)") { open(.files(SampleImage.files(count: 3))) }
                    Button("문서 샘플") { open(.data(SampleImage.document())) }
                }
                Section("영상") {
                    Button("영상 고르기") { openVideo(.pickMultiple(maxItems: 5)) }
                    Button("샘플 영상(2개)") { Task { openVideo(.files(await SampleVideo.files())) } }
                }
                Section("움직이는 이미지") {
                    Button("사진 고르기(슬라이드쇼·GIF)") { openMotion(.pickMultiple(maxItems: 20)) }
                    Button("샘플 사진(3장)") { openMotion(.files(SampleImage.files(count: 3))) }
                    Button("샘플 GIF") { openMotion(.data(SampleImage.gif())) }
                }
                Section("음성") {
                    Button("소리 파일 고르기") { openAudio(.pickMultiple(maxItems: 5)) }
                    Button("녹음하기") { openAudio(.capture) }
                    Button("샘플 소리(2개)") { openAudio(.files(SampleAudio.files())) }
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
            if arguments.contains("-openDocument") { open(.data(SampleImage.document())) }
            if arguments.contains("-openVideo") { Task { openVideo(.files(await SampleVideo.files())) } }
            if arguments.contains("-openMotion") { openMotion(.files(SampleImage.files(count: 3))) }
            if arguments.contains("-openGif") { openMotion(.data(SampleImage.gif())) }
            if arguments.contains("-openAudio") { openAudio(.files(SampleAudio.files())) }
        }
        .frameKitAudioEditor(isPresented: $showAudio, request: audioRequest ?? AudioEditorRequest(input: .pick)) { result in
            handle(result)
        }
        .frameKitMotionEditor(isPresented: $showMotion, request: motionRequest ?? MotionEditorRequest(input: .pick)) { result in
            handle(result)
        }
        .frameKitVideoEditor(isPresented: $showVideo, request: videoRequest ?? VideoEditorRequest(input: .pick)) { result in
            handle(result)
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

    private func openVideo(_ input: EditorInput) {
        videoRequest = VideoEditorRequest(input: input, ui: EditorUIConfig(themeMode: theme, localeIdentifier: locale.isEmpty ? nil : locale))
        showVideo = true
    }

    private func openMotion(_ input: EditorInput) {
        motionRequest = MotionEditorRequest(input: input, ui: EditorUIConfig(themeMode: theme, localeIdentifier: locale.isEmpty ? nil : locale))
        showMotion = true
    }

    private func openAudio(_ input: EditorInput) {
        audioRequest = AudioEditorRequest(input: input, ui: EditorUIConfig(themeMode: theme, localeIdentifier: locale.isEmpty ? nil : locale))
        showAudio = true
    }

    private func handle(_ result: FrameKitResult) {
        switch result {
        case .success(let output, let outputs):
            lastResult = outputs.map { "\($0.width)×\($0.height) \($0.mimeType) \($0.fileSize / 1024)KB \($0.pageCount.map { "\($0)쪽" } ?? "")\n\($0.url.lastPathComponent)" }
                .joined(separator: "\n")
            if let text = output.recognizedText, !text.isEmpty { lastResult += "\n인식한 글자: \(text.prefix(80))" }
            resultImage = UIImage(contentsOfFile: output.url.path)
            if let duration = output.durationMs { lastResult += "\n길이 \(duration)ms" }
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

    /// 원이 좌우로 오가는 12프레임 GIF.
    static func gif() -> Data {
        let data = NSMutableData()
        let count = 12
        guard let destination = CGImageDestinationCreateWithData(data, UTType.gif.identifier as CFString, count, nil) else { return Data() }
        CGImageDestinationSetProperties(destination, [kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFLoopCount: 0]] as CFDictionary)
        for i in 0..<count {
            let renderer = UIGraphicsImageRenderer(size: CGSize(width: 320, height: 240), format: { let f = UIGraphicsImageRendererFormat(); f.scale = 1; return f }())
            let image = renderer.image { context in
                UIColor(hue: CGFloat(i) / CGFloat(count), saturation: 0.35, brightness: 0.95, alpha: 1).setFill()
                context.fill(CGRect(x: 0, y: 0, width: 320, height: 240))
                UIColor.systemIndigo.setFill()
                let x = 40 + 240 * (0.5 - 0.5 * cos(Double(i) / Double(count) * 2 * .pi))
                context.cgContext.fillEllipse(in: CGRect(x: x - 30, y: 90, width: 60, height: 60))
            }
            CGImageDestinationAddImage(destination, image.cgImage!, [kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFDelayTime: 0.08]] as CFDictionary)
        }
        CGImageDestinationFinalize(destination)
        return data as Data
    }

    /// 책상 위에 기울어진 종이 사진.
    static func document() -> Data {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: 2400, height: 1800), format: format).image { context in
            UIColor(red: 0.35, green: 0.22, blue: 0.12, alpha: 1).setFill()
            UIRectFill(CGRect(x: 0, y: 0, width: 2400, height: 1800))
            let paper = UIBezierPath()
            paper.move(to: CGPoint(x: 640, y: 220)); paper.addLine(to: CGPoint(x: 1860, y: 320))
            paper.addLine(to: CGPoint(x: 1760, y: 1620)); paper.addLine(to: CGPoint(x: 540, y: 1520)); paper.close()
            UIColor(white: 0.92, alpha: 1).setFill()
            paper.fill()
            context.cgContext.saveGState()
            paper.addClip()
            let text = "FrameKit 문서 스캔\n\n반듯하게 펴고\n그림자를 지웁니다." as NSString
            text.draw(in: CGRect(x: 760, y: 500, width: 1000, height: 900),
                      withAttributes: [.font: UIFont.boldSystemFont(ofSize: 90), .foregroundColor: UIColor(white: 0.12, alpha: 1)])
            context.cgContext.restoreGState()
        }
        return image.jpegData(compressionQuality: 0.92)!
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

import AVFoundation

/// 사진 없이 시험할 수 있는 3초 영상(움직이는 막대와 소리).
enum SampleVideo {
    static func files() async -> [URL] {
        var urls: [URL] = []
        for (index, hue) in [0.55, 0.05].enumerated() {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("sample-video-\(index).mp4")
            if !FileManager.default.fileExists(atPath: url.path) { try? await make(url: url, hue: hue, label: "Clip \(index + 1)") }
            urls.append(url)
        }
        return urls
    }

    private static func make(url: URL, hue: CGFloat, label: String) async throws {
        let width = 1280, height = 720, fps: Int32 = 30, frames = 90
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: width, AVVideoHeightKey: height])
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA, kCVPixelBufferWidthKey as String: width, kCVPixelBufferHeightKey as String: height])
        writer.add(input)
        writer.startWriting()
        writer.startSession(atSourceTime: .zero)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format)
        for i in 0..<frames {
            while !input.isReadyForMoreMediaData { try await Task.sleep(for: .milliseconds(5)) }
            let image = renderer.image { _ in
                UIColor(hue: hue, saturation: 0.7, brightness: 0.8, alpha: 1).setFill()
                UIRectFill(CGRect(x: 0, y: 0, width: width, height: height))
                UIColor.white.setFill()
                UIRectFill(CGRect(x: CGFloat(i) / CGFloat(frames) * CGFloat(width - 120), y: 520, width: 120, height: 120))
                (label as NSString).draw(at: CGPoint(x: 80, y: 200), withAttributes: [.font: UIFont.boldSystemFont(ofSize: 140), .foregroundColor: UIColor.white])
            }
            var buffer: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &buffer)
            guard let pb = buffer, let cg = image.cgImage else { continue }
            CVPixelBufferLockBaseAddress(pb, [])
            let context = CGContext(data: CVPixelBufferGetBaseAddress(pb), width: width, height: height, bitsPerComponent: 8,
                                    bytesPerRow: CVPixelBufferGetBytesPerRow(pb), space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                    bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
            context?.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
            CVPixelBufferUnlockBaseAddress(pb, [])
            adaptor.append(pb, withPresentationTime: CMTime(value: CMTimeValue(i), timescale: fps))
        }
        input.markAsFinished()
        await writer.finishWriting()
    }
}

/// 높이가 오르내리는 시험용 소리.
enum SampleAudio {
    static func files() -> [URL] {
        [(330.0, 3.0), (523.0, 2.0)].enumerated().compactMap { index, item in
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("sample-tone-\(index + 1).m4a")
            try? FileManager.default.removeItem(at: url)
            guard let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1),
                  let file = try? AVAudioFile(forWriting: url, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44_100,
                                                                          AVNumberOfChannelsKey: 1]) else { return nil }
            let frames = AVAudioFrameCount(item.1 * 44_100)
            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames) else { return nil }
            buffer.frameLength = frames
            for i in 0..<Int(frames) {
                let t = Double(i) / 44_100
                let envelope = 0.25 + 0.2 * sin(t * 2 * .pi * 1.5)
                buffer.floatChannelData![0][i] = Float(sin(t * 2 * .pi * item.0) * envelope)
            }
            try? file.write(from: buffer)
            return url
        }
    }
}
