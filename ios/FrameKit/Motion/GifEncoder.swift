import CoreGraphics
import Foundation

/// 프레임을 하나씩 받아 GIF 파일로 쓴다. 프레임마다 256색 팔레트(median cut)와 위치 고정 순서 디더링을 쓴다.
/// 알파 128 미만은 투명, 나머지는 불투명으로 쓴다. Android GifEncoder와 같은 방식이다.
final class GifEncoder {
    static let maxSize = 0xFFFF
    private static let minDelayCs = 2
    private static let alphaThreshold: UInt8 = 128

    private let handle: FileHandle
    private let width: Int
    private let height: Int
    private var buffer = Data()
    private var carryCs = 0.0
    private var closed = false

    /// - Parameter loopCount: 반복 횟수, 0은 무한.
    init(url: URL, width: Int, height: Int, loopCount: Int) throws(EditorError) {
        guard (1...Self.maxSize).contains(width), (1...Self.maxSize).contains(height) else { throw .logged(.invalidProject, "gif size") }
        guard FileManager.default.createFile(atPath: url.path, contents: nil), let handle = try? FileHandle(forWritingTo: url) else {
            throw .logged(.outputWriteFailed, "gif open")
        }
        self.handle = handle
        self.width = width
        self.height = height
        buffer.append(contentsOf: Array("GIF89a".utf8))
        writeShort(width)
        writeShort(height)
        buffer.append(contentsOf: [0x70, 0, 0])
        buffer.append(contentsOf: [0x21, 0xFF, 0x0B])
        buffer.append(contentsOf: Array("NETSCAPE2.0".utf8))
        buffer.append(contentsOf: [3, 1])
        writeShort(min(max(loopCount, 0), 0xFFFF))
        buffer.append(0)
    }

    deinit { try? handle.close() }

    /// - Parameter durationMs: 보여 줄 시간. 20ms보다 짧으면 20ms로 쓴다.
    func addFrame(_ image: CGImage, durationMs: Int64) throws(EditorError) {
        guard !closed else { throw .logged(.encodeFailed, "gif closed") }
        guard let context = BitmapContext.make(width: width, height: height), let data = context.data else {
            throw .logged(.insufficientMemory, "gif frame")
        }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        let rowBytes = context.bytesPerRow
        let bytes = data.bindMemory(to: UInt8.self, capacity: rowBytes * height)
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        var transparent = false
        for y in 0..<height {
            for x in 0..<width {
                let o = y * rowBytes + x * 4, d = (y * width + x) * 4
                let a = bytes[o + 3]
                if a < Self.alphaThreshold { transparent = true }
                // premultiplied → 원래 색.
                func un(_ v: UInt8) -> UInt8 { a == 0 || a == 255 ? v : UInt8(min(255, (Int(v) * 255 + Int(a) / 2) / Int(a))) }
                rgba[d] = un(bytes[o]); rgba[d + 1] = un(bytes[o + 1]); rgba[d + 2] = un(bytes[o + 2]); rgba[d + 3] = a
            }
        }
        let palette = Quantizer.palette(rgba, maxColors: transparent ? 255 : 256, threshold: Self.alphaThreshold)
        let indices = Quantizer.map(rgba, width: width, palette: palette, transparentIndex: transparent ? palette.count : -1,
                                    threshold: Self.alphaThreshold)

        // 1/100초 단위라 남는 시간을 다음 프레임으로 넘겨 전체 길이를 맞춘다.
        let exact = Double(durationMs) / 10 + carryCs
        let centis = min(max(Self.minDelayCs, Int(exact.rounded())), 0xFFFF)
        carryCs = exact - Double(centis)

        buffer.append(contentsOf: [0x21, 0xF9, 4, transparent ? UInt8((2 << 2) | 1) : UInt8(1 << 2)])
        writeShort(centis)
        buffer.append(contentsOf: [transparent ? UInt8(palette.count) : 0, 0])
        buffer.append(0x2C)
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
        buffer.append(0x80 | 7)
        var table = [UInt8](repeating: 0, count: 256 * 3)
        for (i, color) in palette.enumerated() {
            table[i * 3] = UInt8((color >> 16) & 0xFF)
            table[i * 3 + 1] = UInt8((color >> 8) & 0xFF)
            table[i * 3 + 2] = UInt8(color & 0xFF)
        }
        buffer.append(contentsOf: table)
        Lzw.encode(indices, minCodeSize: 8, into: &buffer)
        try flush()
    }

    func finish() throws(EditorError) {
        guard !closed else { return }
        closed = true
        buffer.append(0x3B)
        try flush()
        do { try handle.close() } catch { throw .logged(.outputWriteFailed, "gif close: \(error)") }
    }

    private func flush() throws(EditorError) {
        guard !buffer.isEmpty else { return }
        do { try handle.write(contentsOf: buffer) } catch {
            let code: EditorErrorCode = (error as? CocoaError)?.code == .fileWriteOutOfSpace ? .insufficientStorage : .outputWriteFailed
            throw .logged(code, "gif write: \(error)")
        }
        buffer.removeAll(keepingCapacity: true)
    }

    private func writeShort(_ value: Int) {
        buffer.append(UInt8(value & 0xFF))
        buffer.append(UInt8((value >> 8) & 0xFF))
    }

    enum Quantizer {
        private static let sampleTarget = 40_000
        private static let bayer = [-8, 0, -6, 2, 4, -4, 6, -2, -5, 3, -7, 1, 7, -1, 5, -3]

        /// 투명 픽셀을 빼고 고른 [maxColors]개 이하의 대표 색(0xRRGGBB).
        static func palette(_ rgba: [UInt8], maxColors: Int, threshold: UInt8) -> [Int] {
            let pixels = rgba.count / 4
            let step = max(1, pixels / sampleTarget)
            var samples: [Int] = []
            samples.reserveCapacity(pixels / step + 1)
            var i = 0
            while i < pixels {
                let o = i * 4
                if rgba[o + 3] >= threshold { samples.append(Int(rgba[o]) << 16 | Int(rgba[o + 1]) << 8 | Int(rgba[o + 2])) }
                i += step
            }
            guard !samples.isEmpty else { return [0] }
            // 색 범위가 가장 넓은 상자를 가장 넓은 축의 중간값에서 나누기를 반복한다.
            var boxes = [Box(colors: samples, from: 0, to: samples.count)]
            while boxes.count < maxColors {
                guard let index = boxes.indices.filter({ boxes[$0].count > 1 }).max(by: { boxes[$0].range < boxes[$1].range }),
                      boxes[index].range > 0 else { break }
                let box = boxes.remove(at: index)
                boxes += box.split(&samples)
            }
            return boxes.map { $0.average(samples) }
        }

        /// 픽셀을 팔레트 번호로 바꾼다. 4×4 순서 디더링, 5비트 RGB 표로 가까운 색 찾기를 줄인다.
        static func map(_ rgba: [UInt8], width: Int, palette: [Int], transparentIndex: Int, threshold: UInt8) -> [UInt8] {
            var cache = [Int](repeating: -1, count: 32 * 32 * 32)
            let pixels = rgba.count / 4
            var out = [UInt8](repeating: 0, count: pixels)
            rgba.withUnsafeBufferPointer { src in
                for index in 0..<pixels {
                    let o = index * 4
                    if transparentIndex >= 0 && src[o + 3] < threshold {
                        out[index] = UInt8(transparentIndex)
                        continue
                    }
                    let d = bayer[((index / width) & 3) * 4 + ((index % width) & 3)]
                    let r = min(max(Int(src[o]) + d, 0), 255)
                    let g = min(max(Int(src[o + 1]) + d, 0), 255)
                    let b = min(max(Int(src[o + 2]) + d, 0), 255)
                    let key = (r >> 3) << 10 | (g >> 3) << 5 | (b >> 3)
                    var found = cache[key]
                    if found < 0 {
                        found = nearest(palette, (r & 0xF8) | 4, (g & 0xF8) | 4, (b & 0xF8) | 4)
                        cache[key] = found
                    }
                    out[index] = UInt8(found)
                }
            }
            return out
        }

        private static func nearest(_ palette: [Int], _ r: Int, _ g: Int, _ b: Int) -> Int {
            var best = 0, bestDistance = Int.max
            for (i, c) in palette.enumerated() {
                let dr = (c >> 16) & 0xFF - r, dg = (c >> 8) & 0xFF - g, db = c & 0xFF - b
                let distance = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                if distance < bestDistance {
                    bestDistance = distance
                    best = i
                }
            }
            return best
        }

        struct Box {
            let from: Int
            let to: Int
            private(set) var axis = 0
            private(set) var range = 0
            var count: Int { to - from }

            init(colors: [Int], from: Int, to: Int) {
                self.from = from
                self.to = to
                var minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0
                for i in from..<to {
                    let c = colors[i]
                    let r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF
                    minR = min(minR, r); maxR = max(maxR, r)
                    minG = min(minG, g); maxG = max(maxG, g)
                    minB = min(minB, b); maxB = max(maxB, b)
                }
                let ranges = [maxR - minR, maxG - minG, maxB - minB]
                axis = ranges.indices.max { ranges[$0] < ranges[$1] } ?? 0
                range = ranges[axis] * max(1, Int(Double(min(to - from, 1 << 16)).squareRoot()))
            }

            func split(_ colors: inout [Int]) -> [Box] {
                let shift = axis == 0 ? 16 : axis == 1 ? 8 : 0
                let sorted = colors[from..<to].sorted { ($0 >> shift) & 0xFF < ($1 >> shift) & 0xFF }
                colors.replaceSubrange(from..<to, with: sorted)
                let middle = from + count / 2
                return [Box(colors: colors, from: from, to: middle), Box(colors: colors, from: middle, to: to)]
            }

            func average(_ colors: [Int]) -> Int {
                var r = 0, g = 0, b = 0
                for i in from..<to {
                    let c = colors[i]
                    r += (c >> 16) & 0xFF
                    g += (c >> 8) & 0xFF
                    b += c & 0xFF
                }
                let n = max(1, count)
                return (r / n) << 16 | (g / n) << 8 | (b / n)
            }
        }
    }

    /// GIF LZW 압축.
    enum Lzw {
        private static let maxCodes = 4096
        private static let hashSize = 8192

        static func encode(_ indices: [UInt8], minCodeSize: Int, into out: inout Data) {
            out.append(UInt8(minCodeSize))
            let clear = 1 << minCodeSize
            let end = clear + 1
            var blocks = BlockWriter()
            var codeSize = minCodeSize + 1
            var next = clear + 2
            var keys = [Int](repeating: -1, count: hashSize)
            var values = [Int](repeating: 0, count: hashSize)
            blocks.write(clear, codeSize, &out)
            guard let first = indices.first else {
                blocks.write(end, codeSize, &out)
                blocks.finish(&out)
                return
            }
            var prefix = Int(first)
            for i in 1..<indices.count {
                let c = Int(indices[i])
                let key = prefix << 8 | c
                var slot = Int((UInt32(truncatingIfNeeded: key) &* 0x9E37_79B1) >> 19) & (hashSize - 1)
                var found = -1
                while keys[slot] != -1 {
                    if keys[slot] == key {
                        found = values[slot]
                        break
                    }
                    slot = (slot + 1) & (hashSize - 1)
                }
                if found >= 0 {
                    prefix = found
                    continue
                }
                blocks.write(prefix, codeSize, &out)
                if next < maxCodes {
                    keys[slot] = key
                    values[slot] = next
                    if next == 1 << codeSize && codeSize < 12 { codeSize += 1 }
                    next += 1
                } else {
                    // 표가 가득 차면 처음부터 다시 쌓는다.
                    blocks.write(clear, codeSize, &out)
                    for k in keys.indices { keys[k] = -1 }
                    codeSize = minCodeSize + 1
                    next = clear + 2
                }
                prefix = c
            }
            blocks.write(prefix, codeSize, &out)
            // 디코더가 마지막 코드를 받으며 표에 하나를 더 넣을 때 코드 길이가 늘어나는 경우를 맞춘다.
            if next == 1 << codeSize && codeSize < 12 { codeSize += 1 }
            blocks.write(end, codeSize, &out)
            blocks.finish(&out)
        }

        private struct BlockWriter {
            private var block = [UInt8](repeating: 0, count: 255)
            private var length = 0
            private var bits = 0
            private var pending = 0

            mutating func write(_ code: Int, _ size: Int, _ out: inout Data) {
                pending |= code << bits
                bits += size
                while bits >= 8 {
                    push(UInt8(pending & 0xFF), &out)
                    pending >>= 8
                    bits -= 8
                }
            }

            private mutating func push(_ byte: UInt8, _ out: inout Data) {
                block[length] = byte
                length += 1
                if length == 255 { flush(&out) }
            }

            private mutating func flush(_ out: inout Data) {
                guard length > 0 else { return }
                out.append(UInt8(length))
                out.append(contentsOf: block[0..<length])
                length = 0
            }

            mutating func finish(_ out: inout Data) {
                if bits > 0 { push(UInt8(pending & 0xFF), &out) }
                pending = 0
                bits = 0
                flush(&out)
                out.append(0)
            }
        }
    }
}
