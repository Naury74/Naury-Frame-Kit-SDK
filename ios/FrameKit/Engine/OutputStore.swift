import AVFoundation
import Foundation

/// 결과 파일을 Application Support/FrameKit/Exports에 쓴다. 숨긴 `.partial` 파일에 쓰고 다 쓰면 이름을 바꾼다.
enum OutputStore {
    static var directory: URL {
        get throws(EditorError) {
            do {
                let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
                let dir = base.appendingPathComponent("FrameKit/Exports", isDirectory: true)
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                return dir
            } catch {
                throw .logged(.outputWriteFailed, "exports dir: \(error)")
            }
        }
    }

    /// SDK가 임시로 쓰는 폴더. 앱 캐시 아래라 시스템이 지울 수 있다.
    static var temporaryDirectory: URL {
        get throws(EditorError) {
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("FrameKit", isDirectory: true)
            do {
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            } catch {
                throw .logged(.outputWriteFailed, "temp dir: \(error)")
            }
            return dir
        }
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var swept = false

    /// 새 결과 파일 자리. 임시 파일은 고유 이름이고, 최종 이름은 `publish`에서 정한다.
    static func reserve(extension ext: String) throws(EditorError) -> (final: OutputSlot, partial: URL) {
        let dir = try directory
        return (OutputSlot(directory: dir, ext: ext), dir.appendingPathComponent(".\(UUID().uuidString).\(ext).partial"))
    }

    /// 임시 파일을 `FrameKit_yyyyMMdd_HHmmss[_n].ext`로 옮기고 그 URL을 돌려준다. 같은 이름이 있으면 번호를 붙인다.
    @discardableResult
    static func publish(partial: URL, to slot: OutputSlot) throws(EditorError) -> URL {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        let stem = "FrameKit_" + formatter.string(from: Date())
        lock.lock()
        defer { lock.unlock() }
        var n = 0
        while true {
            let name = n == 0 ? "\(stem).\(slot.ext)" : "\(stem)_\(n).\(slot.ext)"
            let target = slot.directory.appendingPathComponent(name)
            if !FileManager.default.fileExists(atPath: target.path) {
                do {
                    try FileManager.default.moveItem(at: partial, to: target)
                    return target
                } catch {
                    try? FileManager.default.removeItem(at: partial)
                    throw .logged(.outputWriteFailed, "publish: \(error)")
                }
            }
            n += 1
        }
    }

    /// 24시간 지난 남은 파일을 지운다(앱이 저장·편집 중 종료된 경우). 결과 폴더의 숨긴 파일과 임시 폴더가 대상이다.
    static func removeStalePartials() {
        // 실행마다 한 번만 한다. 열린 편집기가 쓰는 임시 파일(세션에서 링크한 오래된 파일 포함)을 건드리지 않게 첫 편집기가 열 때 한다.
        lock.lock()
        let first = !swept
        swept = true
        lock.unlock()
        guard first else { return }
        let limit = Date().addingTimeInterval(-24 * 60 * 60)
        func sweep(_ dir: URL?, _ include: (URL) -> Bool) {
            guard let dir, let items = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey])
            else { return }
            for item in items where include(item) {
                let date = (try? item.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
                if date < limit { try? FileManager.default.removeItem(at: item) }
            }
        }
        sweep(try? directory) { $0.lastPathComponent.hasPrefix(".") }
        sweep(try? temporaryDirectory) { _ in true }
    }

    /// 원본을 임시 폴더에 복사한다. 보안 범위 URL은 복사하는 동안만 연다.
    /// - Throws: 없는 파일 `sourceUnavailable`, 읽기 권한 없음 `permissionDenied`, 빈 파일 `decodeFailed`, 공간 부족 `insufficientStorage`.
    static func copyToTemporary(_ url: URL, fallbackExtension: String) throws(EditorError) -> URL {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        try checkReadable(url)
        let target = try temporaryDirectory.appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(url.pathExtension.isEmpty ? fallbackExtension : url.pathExtension)
        do {
            try FileManager.default.copyItem(at: url, to: target)
            return target
        } catch {
            try? FileManager.default.removeItem(at: target)
            throw .logged(readErrorCode(error), "copy: \(error)")
        }
    }

    /// 복사를 주 스레드 밖에서 한다.
    static func copyToTemporaryInBackground(_ url: URL, fallbackExtension: String) async throws(EditorError) -> URL {
        let result = await Task.detached(priority: .userInitiated) { () -> Result<URL, EditorError> in
            Result { () throws(EditorError) -> URL in try copyToTemporary(url, fallbackExtension: fallbackExtension) }
        }.value
        return try result.get()
    }

    /// 보안 범위가 열린 상태에서 부른다. 읽을 수 있는 파일이 아니면 알맞은 코드로 실패한다.
    static func checkReadable(_ url: URL) throws(EditorError) {
        let manager = FileManager.default
        var isDirectory: ObjCBool = false
        guard url.isFileURL, manager.fileExists(atPath: url.path, isDirectory: &isDirectory), !isDirectory.boolValue else {
            throw .logged(.sourceUnavailable, "source missing")
        }
        guard manager.isReadableFile(atPath: url.path) else { throw .logged(.permissionDenied, "source not readable") }
        if let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize, size == 0 {
            throw .logged(.decodeFailed, "source empty")
        }
    }

    /// 파일 읽기·복사 오류를 오류 코드로 바꾼다.
    static func readErrorCode(_ error: Error) -> EditorErrorCode {
        guard let cocoa = error as? CocoaError else { return .sourceUnavailable }
        switch cocoa.code {
        case .fileReadNoPermission, .fileWriteNoPermission: return .permissionDenied
        case .fileWriteOutOfSpace: return .insufficientStorage
        default: return .sourceUnavailable
        }
    }

    /// SDK 임시 폴더 안 파일이면 지운다.
    static func discardTemporary(_ url: URL) {
        guard let dir = try? temporaryDirectory,
              url.standardizedFileURL.resolvingSymlinksInPath().deletingLastPathComponent() == dir.standardizedFileURL.resolvingSymlinksInPath()
        else { return }
        try? FileManager.default.removeItem(at: url)
    }

    /// 남은 공간이 [bytes]보다 작으면 실패한다. 알 수 없으면 통과한다.
    static func checkFreeSpace(bytes: Int64) throws(EditorError) {
        guard let dir = try? directory,
              let values = try? dir.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
              let free = values.volumeAvailableCapacityForImportantUsage else { return }
        if free < bytes { throw .logged(.insufficientStorage, "need \(bytes) free \(free)") }
    }
}

/// 선택기가 임시 폴더에 복사한 파일과 복사하지 못한 항목의 오류.
struct PickedFiles {
    var urls: [URL] = []
    var errors: [EditorError] = []
    /// 한도를 넘어 일부를 버렸다.
    var truncated = false

    /// 아무것도 고르지 않고 닫았다.
    var isEmpty: Bool { urls.isEmpty && errors.isEmpty }

    init(urls: [URL] = [], errors: [EditorError] = []) {
        self.urls = urls
        self.errors = errors
    }

    mutating func add(_ result: Result<URL, EditorError>) {
        switch result {
        case .success(let url): urls.append(url)
        case .failure(let error): errors.append(error)
        }
    }
}

/// 결과 파일이 놓일 폴더와 확장자.
struct OutputSlot {
    let directory: URL
    let ext: String
}

/// 결과 파일 관리.
public enum FrameKitOutputs {
    /// SDK가 만든 결과 파일을 지운다. SDK 결과 폴더 밖의 파일은 지우지 않고 `false`를 돌려준다.
    @discardableResult
    public static func delete(_ url: URL) -> Bool {
        guard url.isFileURL, let dir = try? OutputStore.directory else { return false }
        let target = url.standardizedFileURL.resolvingSymlinksInPath()
        let root = dir.standardizedFileURL.resolvingSymlinksInPath()
        guard target.deletingLastPathComponent() == root else { return false }
        return (try? FileManager.default.removeItem(at: target)) != nil
    }
}

/// AVFoundation 저장 오류를 오류 코드로 바꾼다.
enum ExportErrors {
    static func map(_ error: Error?, context: String) -> EditorError {
        guard let error else { return .logged(.encodeFailed, "\(context): no error") }
        return .logged(code(error as NSError), "\(context): \(error)")
    }

    static func code(_ error: NSError) -> EditorErrorCode {
        if error.domain == AVFoundationErrorDomain {
            switch AVError.Code(rawValue: error.code) {
            case .diskFull: return .insufficientStorage
            case .outOfMemory: return .insufficientMemory
            // 앱이 뒤로 가거나 미디어 서비스가 끊겨 멈췄다. 다시 하면 된다.
            case .operationInterrupted, .mediaServicesWereReset, .sessionWasInterrupted: return .outputWriteFailed
            default: break
            }
        }
        if error.domain == NSPOSIXErrorDomain && error.code == Int(ENOSPC) { return .insufficientStorage }
        if error.domain == NSCocoaErrorDomain && error.code == CocoaError.fileWriteOutOfSpace.rawValue { return .insufficientStorage }
        if let underlying = error.userInfo[NSUnderlyingErrorKey] as? NSError {
            let inner = code(underlying)
            if inner != .encodeFailed { return inner }
        }
        return .encodeFailed
    }
}
