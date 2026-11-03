import CryptoKit
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// 마지막으로 적용한 편집을 Application Support/FrameKit/Sessions/<key>/에 남긴다.
///
/// 같은 편집기를 같은 입력으로 다시 열면 이어 연다. `session.json`은 편집 값, `media/`는 원본 하드 링크다.
/// 파일 작업은 한 줄 큐에서 순서대로 한다. 결과를 보내면 지우고, 7일 지난 세션은 다음 실행 때 지운다.
@MainActor
final class EditSession {
    static let lifetime: TimeInterval = 7 * 24 * 60 * 60
    private static let version = 1
    private static var cleaned = false

    private struct Envelope<State: Codable>: Codable {
        var version: Int
        var kind: String
        var fingerprint: String
        var state: State
    }

    private struct Header: Decodable {
        var version: Int
        var kind: String
        var fingerprint: String
    }

    let directory: URL
    /// 저장할 때 함께 적는 편집기 종류. 진입점은 고른 편집기로 바꾼다.
    var kind: String
    private let fingerprint: String
    private var media: URL { directory.appendingPathComponent("media", isDirectory: true) }
    private var file: URL { directory.appendingPathComponent("session.json") }
    /// 임시 파일 경로 → media 안 이름.
    private var linked: [String: String] = [:]
    private var masks: [ObjectIdentifier: (mask: CutoutMask, name: String)] = [:]
    private var lastWritten: Data?
    private let queue = DispatchQueue(label: "FrameKit.EditSession")

    /// - Parameters:
    ///   - slot: 세션 자리를 나누는 이름(편집기 종류).
    ///   - input: 요청 입력. 경로가 같으면 같은 자리, 내용 지문이 다르면 처음부터 연다.
    init?(slot: String, input: EditorInput, root: URL? = nil) {
        guard let base = root ?? Self.root else { return nil }
        Self.removeExpired(in: base)
        let (identity, fingerprint) = Self.describe(input)
        let digest = SHA256.hash(data: Data("\(slot)|\(identity)".utf8)).map { String(format: "%02x", $0) }.joined()
        directory = base.appendingPathComponent(String(digest.prefix(32)), isDirectory: true)
        kind = slot
        self.fingerprint = fingerprint
    }

    static var root: URL? {
        guard let base = try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        else { return nil }
        return base.appendingPathComponent("FrameKit/Sessions", isDirectory: true)
    }

    // MARK: - 읽기

    /// 남은 세션의 편집기 종류. 없거나 원본 지문이 다르면 `nil`이고, 다르면 지운다.
    var storedKind: String? {
        guard let data = try? Data(contentsOf: file) else { return nil }
        guard let header = try? JSONDecoder().decode(Header.self, from: data), header.version == Self.version,
              header.fingerprint == fingerprint else {
            discard()
            return nil
        }
        return header.kind
    }

    /// 남은 편집 값. 읽을 수 없거나 원본이 바뀌었으면 세션을 지우고 `nil`.
    func load<State: Codable>(_ type: State.Type) -> State? {
        guard storedKind != nil, let data = try? Data(contentsOf: file) else { return nil }
        guard let envelope = try? JSONDecoder().decode(Envelope<State>.self, from: data) else {
            discard()
            return nil
        }
        kind = envelope.kind
        lastWritten = data
        return envelope.state
    }

    /// media 안 파일을 임시 폴더에 링크해 연다. 닫으면 지워지는 쪽은 임시 파일이다.
    func restoreFile(_ name: String) -> URL? {
        let source = media.appendingPathComponent(name)
        guard let temp = try? OutputStore.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(source.pathExtension) else { return nil }
        let ok = queue.sync { Self.link(source, to: temp) }
        guard ok else { return nil }
        linked[temp.standardizedFileURL.path] = name
        return temp
    }

    /// 저장해 둔 배경 제거 마스크.
    func restoreMask(_ name: String) -> CutoutMask? {
        let url = media.appendingPathComponent(name)
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
        let mask = CutoutMask(image: image)
        masks[ObjectIdentifier(mask)] = (mask, name)
        return mask
    }

    // MARK: - 쓰기

    /// 파일을 media에 링크하고 그 이름을 돌려준다. 이미 링크한 파일은 같은 이름이다.
    func name(for url: URL) -> String {
        let key = url.standardizedFileURL.path
        if let name = linked[key] { return name }
        let name = UUID().uuidString + (url.pathExtension.isEmpty ? "" : ".\(url.pathExtension)")
        linked[key] = name
        let media = media
        queue.async {
            try? FileManager.default.createDirectory(at: media, withIntermediateDirectories: true)
            _ = Self.link(url, to: media.appendingPathComponent(name))
        }
        return name
    }

    /// 마스크를 PNG로 남기고 그 이름을 돌려준다.
    func name(for mask: CutoutMask) -> String {
        if let entry = masks[ObjectIdentifier(mask)] { return entry.name }
        let name = UUID().uuidString + ".png"
        masks[ObjectIdentifier(mask)] = (mask, name)
        let media = media
        let image = mask.image
        queue.async {
            try? FileManager.default.createDirectory(at: media, withIntermediateDirectories: true)
            let target = media.appendingPathComponent(name)
            guard let destination = CGImageDestinationCreateWithURL(target as CFURL, UTType.png.identifier as CFString, 1, nil) else { return }
            CGImageDestinationAddImage(destination, image, nil)
            CGImageDestinationFinalize(destination)
        }
        return name
    }

    /// 편집 값을 쓴다. 앞서 쓴 것과 같으면 건너뛴다. [files]에 없는 media 파일은 지운다.
    func save<State: Codable>(_ state: State, files: Set<String>) {
        let envelope = Envelope(version: Self.version, kind: kind, fingerprint: fingerprint, state: state)
        guard let data = try? JSONEncoder().encode(envelope), data != lastWritten else { return }
        lastWritten = data
        masks = masks.filter { files.contains($0.value.name) }
        let directory = directory, file = file, media = media
        queue.async {
            do {
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                try data.write(to: file, options: .atomic)
            } catch {
                frameKitLog.error("session write failed: \(String(describing: error), privacy: .public)")
                return
            }
            let names = (try? FileManager.default.contentsOfDirectory(atPath: media.path)) ?? []
            for name in names where !files.contains(name) {
                try? FileManager.default.removeItem(at: media.appendingPathComponent(name))
            }
        }
    }

    /// 세션을 지운다. 뒤에 다시 저장하면 새로 만든다.
    func discard() {
        lastWritten = nil
        linked = [:]
        masks = [:]
        let directory = directory
        queue.async { try? FileManager.default.removeItem(at: directory) }
    }

    /// 앞서 맡긴 파일 작업이 끝날 때까지 기다린다.
    func flush() { queue.sync {} }

    // MARK: - 내부

    nonisolated private static func link(_ source: URL, to target: URL) -> Bool {
        let fm = FileManager.default
        if (try? fm.linkItem(at: source, to: target)) != nil { return true }
        return (try? fm.copyItem(at: source, to: target)) != nil
    }

    /// 입력의 자리 이름과 내용 지문. 앱 폴더 안 경로는 앱 폴더 기준으로 적는다(업데이트 뒤 바뀔 수 있다).
    private static func describe(_ input: EditorInput) -> (identity: String, fingerprint: String) {
        func path(_ url: URL) -> String {
            let path = url.standardizedFileURL.path
            let home = URL(fileURLWithPath: NSHomeDirectory()).standardizedFileURL.path
            return path.hasPrefix(home + "/") ? "~" + path.dropFirst(home.count) : path
        }
        func stamp(_ url: URL) -> String {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
            let size = (attributes?[.size] as? NSNumber)?.int64Value ?? -1
            let date = (attributes?[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
            return "\(size):\(date)"
        }
        switch input {
        case .file(let url): return ("file:" + path(url), stamp(url))
        case .files(let urls): return ("files:" + urls.map(path).joined(separator: "\n"), urls.map(stamp).joined(separator: ","))
        case .data(let data): return ("data", SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined())
        case .pick, .pickMultiple: return ("pick", "")
        case .capture: return ("capture", "")
        }
    }

    private static func removeExpired(in root: URL) {
        guard !cleaned else { return }
        cleaned = true
        let limit = Date().addingTimeInterval(-lifetime)
        DispatchQueue.global(qos: .utility).async { removeSessions(in: root, olderThan: limit) }
    }

    /// [limit]보다 오래 저장하지 않은 세션을 지운다.
    nonisolated static func removeSessions(in root: URL, olderThan limit: Date) {
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) else { return }
        for item in items {
            let json = item.appendingPathComponent("session.json")
            let date = (try? (fm.fileExists(atPath: json.path) ? json : item).resourceValues(forKeys: [.contentModificationDateKey]))?
                .contentModificationDate ?? .distantPast
            if date < limit { try? fm.removeItem(at: item) }
        }
    }
}
