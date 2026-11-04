import AVFoundation
import ImageIO
import UIKit
import UniformTypeIdentifiers

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
            while !input.isReadyForMoreMediaData { try await Task.sleep(nanoseconds: 5_000_000) }
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
