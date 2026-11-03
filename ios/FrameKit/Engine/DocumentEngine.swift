import CoreGraphics
import CoreImage
import Foundation
import ImageIO
import UniformTypeIdentifiers
internal import FrameKitCore

/// 문서 네 모서리(업라이트 원본 기준 0...1). 왼쪽 위부터 시계 방향.
struct DocQuad: Hashable, Sendable {
    var corners: [NPoint]

    var kotlin: DocumentQuad {
        let p = corners.map { PointN(x: $0.x, y: $0.y) }
        return DocumentQuad(topLeft: p[0], topRight: p[1], bottomRight: p[2], bottomLeft: p[3])
    }

    init(corners: [NPoint]) { self.corners = corners }

    init(_ quad: DocumentQuad) {
        corners = quad.corners.map { NPoint(x: $0.x, y: $0.y) }
    }

    /// 가장자리에서 [inset]만큼 들어온 사각형.
    static func inset(_ inset: Double) -> DocQuad {
        DocQuad(corners: [NPoint(x: inset, y: inset), NPoint(x: 1 - inset, y: inset), NPoint(x: 1 - inset, y: 1 - inset), NPoint(x: inset, y: 1 - inset)])
    }

    static let whole = DocQuad.inset(0)

    var isUsable: Bool { kotlin.isUsable }
}

/// 감지 결과. 휜 테두리는 core 객체 그대로 둔다.
struct DocDetection: @unchecked Sendable {
    let quad: DocQuad
    fileprivate let outline: DocumentOutline?
}

/// 문서 스캔 방식(Android ScanMode와 같다).
enum ScanStyle: String, CaseIterable, Sendable {
    case color, grayscale, blackWhite, original

    var labelKey: String {
        switch self {
        case .color: "scan_color"
        case .grayscale: "scan_grayscale"
        case .blackWhite: "scan_black_white"
        case .original: "scan_original"
        }
    }
}

enum DocumentEngine {
    static let maxLongEdge = 4096
    static let meshCells = 12

    /// [image]에서 문서를 찾는다. 없으면 `nil`.
    static func detect(_ image: CGImage, insideOnly: Bool = false) -> DocDetection? {
        let long = max(image.width, image.height)
        let factor = min(1, Double(DocumentDetector.shared.WORKING_LONG_EDGE) / Double(long))
        let w = max(8, Int((Double(image.width) * factor).rounded())), h = max(8, Int((Double(image.height) * factor).rounded()))
        guard let context = BitmapContext.make(width: w, height: h), let raw = context.data else { return nil }
        context.interpolationQuality = .medium
        context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        let row = context.bytesPerRow
        let bytes = raw.bindMemory(to: UInt8.self, capacity: row * h)
        let luma = KotlinIntArray(size: Int32(w * h))
        let chroma = KotlinIntArray(size: Int32(w * h))
        for y in 0..<h {
            for x in 0..<w {
                let o = y * row + x * 4
                let r = Int32(bytes[o]), g = Int32(bytes[o + 1]), b = Int32(bytes[o + 2])
                let i = Int32(y * w + x)
                luma.set(index: i, value: (r * 299 + g * 587 + b * 114) / 1000)
                chroma.set(index: i, value: max(r, g, b) - min(r, g, b))
            }
        }
        guard let analysis = try? DocumentDetector.shared.analyze(luma: luma, width: Int32(w), height: Int32(h), chroma: chroma, insideOnly: insideOnly),
              let quad = analysis.quad else { return nil }
        return DocDetection(quad: DocQuad(quad), outline: analysis.outline)
    }

    /// 원본을 [quad]대로 펴고 [style]로 보정한 JPEG 파일을 만든다.
    /// - Parameter detection: 같은 모서리로 찾은 휜 테두리가 있으면 그 곡선을 따라 편다.
    static func rectify(source: SourceImage, quad: DocQuad, detection: DocDetection?, style: ScanStyle) throws(EditorError) -> URL {
        guard quad.isUsable else { throw .logged(.invalidProject, "corners do not form a document") }
        let upright = source.uprightSize
        let curved = detection.flatMap { $0.quad == quad ? $0.outline : nil }.flatMap { $0.isCurved ? $0 : nil }
        let size = curved?.rectifiedSize(imageWidth: Int32(upright.width), imageHeight: Int32(upright.height))
            ?? quad.kotlin.rectifiedSize(imageWidth: Int32(upright.width), imageHeight: Int32(upright.height))
        let fullW = Double(truncating: size.first ?? 1), fullH = Double(truncating: size.second ?? 1)
        let scale = min(1, Double(maxLongEdge) / max(fullW, fullH))
        let w = max(1, Int((fullW * scale).rounded())), h = max(1, Int((fullH * scale).rounded()))
        try MemoryBudget.check(bytes: w * h * 4 * 3)
        let decoded = try source.decode(maxLongEdge: Int(Double(max(upright.width, upright.height)) * min(1, scale * 1.2)) + 1).cgImage
        let input = CIImage(cgImage: decoded)
        let sw = Double(decoded.width), sh = Double(decoded.height)
        // CI 좌표는 아래가 원점이다.
        func ci(_ p: PointN) -> CIVector { CIVector(x: p.x * sw, y: (1 - p.y) * sh) }
        var output: CIImage
        if let curved {
            output = CIImage(color: .white).cropped(to: CGRect(x: 0, y: 0, width: w, height: h))
            let n = meshCells
            for j in 0..<n {
                for i in 0..<n {
                    let p = [curved.point(u: Double(i) / Double(n), v: Double(j) / Double(n)),
                             curved.point(u: Double(i + 1) / Double(n), v: Double(j) / Double(n)),
                             curved.point(u: Double(i + 1) / Double(n), v: Double(j + 1) / Double(n)),
                             curved.point(u: Double(i) / Double(n), v: Double(j + 1) / Double(n))]
                    let left = Double(w) * Double(i) / Double(n), right = Double(w) * Double(i + 1) / Double(n)
                    let top = Double(h) * Double(j) / Double(n), bottom = Double(h) * Double(j + 1) / Double(n)
                    guard let cell = perspective(input, p.map(ci), width: right - left + 1, height: bottom - top + 1) else { continue }
                    // 칸 사이 틈이 보이지 않게 반 픽셀씩 겹친다.
                    let placed = cell.transformed(by: CGAffineTransform(translationX: left - 0.5, y: Double(h) - bottom - 0.5))
                    output = placed.composited(over: output)
                }
            }
        } else {
            guard let warped = perspective(input, quad.kotlin.corners.map(ci), width: Double(w), height: Double(h)) else {
                throw .logged(.invalidProject, "corners cannot be mapped")
            }
            output = warped.composited(over: CIImage(color: .white))
        }
        let context = CIContext(options: [.cacheIntermediates: false])
        guard var image = context.createCGImage(output, from: CGRect(x: 0, y: 0, width: w, height: h), format: .RGBA8,
                                                colorSpace: BitmapContext.sRGB) else {
            throw .logged(.insufficientMemory, "rectify render")
        }
        if style != .original { image = try ScanEnhancer.enhance(image, style: style) }
        let url = try OutputStore.temporaryDirectory.appendingPathComponent("doc-\(UUID().uuidString).jpg")
        guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
            throw .logged(.outputWriteFailed, "rectify destination")
        }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.92] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw .logged(.encodeFailed, "rectify encode") }
        return url
    }

    /// 네 점(왼쪽 위부터 시계 방향, CI 좌표)을 [width]×[height] 사각형으로 편다.
    private static func perspective(_ input: CIImage, _ p: [CIVector], width: Double, height: Double) -> CIImage? {
        guard let filter = CIFilter(name: "CIPerspectiveCorrection") else { return nil }
        filter.setValue(input, forKey: kCIInputImageKey)
        filter.setValue(p[0], forKey: "inputTopLeft")
        filter.setValue(p[1], forKey: "inputTopRight")
        filter.setValue(p[2], forKey: "inputBottomRight")
        filter.setValue(p[3], forKey: "inputBottomLeft")
        guard let out = filter.outputImage, out.extent.width > 0, out.extent.height > 0, out.extent.width.isFinite else { return nil }
        let moved = out.transformed(by: CGAffineTransform(translationX: -out.extent.minX, y: -out.extent.minY))
        return moved.transformed(by: CGAffineTransform(scaleX: width / out.extent.width, y: height / out.extent.height))
    }
}

/// 문서 스캔 보정(Android ScanEnhancer와 같은 식). 배경 밝기 지도로 그림자를 지우고 글자를 진하게 한다.
enum ScanEnhancer {
    private static let minBackground: Float = 40
    private static let black: Float = 0.18
    private static let white: Float = 0.9
    private static let bwLow: Float = 0.55
    private static let bwHigh: Float = 0.78
    private static let cell = 24

    static func enhance(_ image: CGImage, style: ScanStyle) throws(EditorError) -> CGImage {
        let width = image.width, height = image.height
        guard let context = BitmapContext.make(width: width, height: height), let raw = context.data else {
            throw .logged(.insufficientMemory, "scan context")
        }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        let map = BackgroundMap(image)
        let row = context.bytesPerRow
        let bytes = raw.bindMemory(to: UInt8.self, capacity: row * height)
        for y in 0..<height {
            for x in 0..<width {
                let o = y * row + x * 4
                let bg = max(map.at(x, y), minBackground)
                let r = Float(bytes[o]) / bg, g = Float(bytes[o + 1]) / bg, b = Float(bytes[o + 2]) / bg
                switch style {
                case .color:
                    bytes[o] = level(r); bytes[o + 1] = level(g); bytes[o + 2] = level(b)
                case .grayscale:
                    let v = level(0.299 * r + 0.587 * g + 0.114 * b)
                    bytes[o] = v; bytes[o + 1] = v; bytes[o + 2] = v
                case .blackWhite:
                    let v = threshold(0.299 * r + 0.587 * g + 0.114 * b)
                    bytes[o] = v; bytes[o + 1] = v; bytes[o + 2] = v
                case .original: break
                }
                bytes[o + 3] = 255
            }
        }
        guard let result = context.makeImage() else { throw .logged(.insufficientMemory, "scan image") }
        return result
    }

    private static func level(_ v: Float) -> UInt8 {
        let t = min(1, max(0, (v - black) / (white - black)))
        return UInt8(min(255, max(0, ((t * t * (3 - 2 * t) * 0.35 + t * 0.65) * 255).rounded())))
    }

    private static func threshold(_ v: Float) -> UInt8 {
        let t = min(1, max(0, (v - bwLow) / (bwHigh - bwLow)))
        return UInt8((t * t * (3 - 2 * t) * 255).rounded())
    }

    /// 칸(24px)마다 밝은 쪽 값을 이웃 최댓값으로 넓히고 평균으로 부드럽게 한 배경 밝기.
    private struct BackgroundMap {
        let values: [Float]
        let columns: Int
        let rows: Int
        let cellWidth: Float
        let cellHeight: Float

        init(_ image: CGImage) {
            columns = max(1, image.width / ScanEnhancer.cell)
            rows = max(1, image.height / ScanEnhancer.cell)
            cellWidth = Float(image.width) / Float(columns)
            cellHeight = Float(image.height) / Float(rows)
            let fw = columns * 4, fh = rows * 4
            var cells = [Float](repeating: 255, count: columns * rows)
            if let context = BitmapContext.make(width: fw, height: fh), let raw = context.data {
                context.interpolationQuality = .medium
                context.draw(image, in: CGRect(x: 0, y: 0, width: fw, height: fh))
                let row = context.bytesPerRow
                let bytes = raw.bindMemory(to: UInt8.self, capacity: row * fh)
                for cy in 0..<rows {
                    for cx in 0..<columns {
                        var best: Float = 0
                        for dy in 0..<4 {
                            for dx in 0..<4 {
                                let o = (cy * 4 + dy) * row + (cx * 4 + dx) * 4
                                best = max(best, 0.299 * Float(bytes[o]) + 0.587 * Float(bytes[o + 1]) + 0.114 * Float(bytes[o + 2]))
                            }
                        }
                        cells[cy * columns + cx] = best
                    }
                }
            }
            let dilated = Self.filter(cells, columns, rows) { $0.max() ?? 0 }
            values = Self.filter(dilated, columns, rows) { $0.reduce(0, +) / Float($0.count) }
        }

        func at(_ x: Int, _ y: Int) -> Float {
            let fx = min(Float(columns - 1), max(0, (Float(x) + 0.5) / cellWidth - 0.5))
            let fy = min(Float(rows - 1), max(0, (Float(y) + 0.5) / cellHeight - 0.5))
            let x0 = Int(fx), y0 = Int(fy)
            let x1 = min(x0 + 1, columns - 1), y1 = min(y0 + 1, rows - 1)
            let tx = fx - Float(x0), ty = fy - Float(y0)
            let top = values[y0 * columns + x0] * (1 - tx) + values[y0 * columns + x1] * tx
            let bottom = values[y1 * columns + x0] * (1 - tx) + values[y1 * columns + x1] * tx
            return top * (1 - ty) + bottom * ty
        }

        private static func filter(_ source: [Float], _ columns: Int, _ rows: Int, combine: ([Float]) -> Float) -> [Float] {
            var out = [Float](repeating: 0, count: source.count)
            for y in 0..<rows {
                for x in 0..<columns {
                    var window: [Float] = []
                    for dy in -2...2 {
                        for dx in -2...2 {
                            let nx = x + dx, ny = y + dy
                            if nx >= 0 && nx < columns && ny >= 0 && ny < rows { window.append(source[ny * columns + nx]) }
                        }
                    }
                    out[y * columns + x] = combine(window)
                }
            }
            return out
        }
    }
}
