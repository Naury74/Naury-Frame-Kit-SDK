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

    /// 새 결과 파일 이름(`FrameKit_yyyyMMdd_HHmmss[_n].ext`)과 그 임시 파일.
    static func reserve(extension ext: String) throws(EditorError) -> (final: URL, partial: URL) {
        let dir = try directory
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        let stem = "FrameKit_" + formatter.string(from: Date())
        var name = "\(stem).\(ext)"
        var n = 1
        while FileManager.default.fileExists(atPath: dir.appendingPathComponent(name).path) {
            name = "\(stem)_\(n).\(ext)"
            n += 1
        }
        return (dir.appendingPathComponent(name), dir.appendingPathComponent(".\(name).partial"))
    }

    static func publish(partial: URL, to final: URL) throws(EditorError) {
        do {
            try FileManager.default.moveItem(at: partial, to: final)
        } catch {
            try? FileManager.default.removeItem(at: partial)
            throw .logged(.outputWriteFailed, "publish: \(error)")
        }
    }

    /// 24시간 지난 `.partial` 파일을 지운다(앱이 저장 중 종료된 경우).
    static func removeStalePartials() {
        guard let dir = try? directory,
              let items = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey])
        else { return }
        let limit = Date().addingTimeInterval(-24 * 60 * 60)
        for item in items where item.lastPathComponent.hasSuffix(".partial") {
            let date = (try? item.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
            if date < limit { try? FileManager.default.removeItem(at: item) }
        }
    }

    /// 남은 공간이 [bytes]보다 작으면 실패한다. 알 수 없으면 통과한다.
    static func checkFreeSpace(bytes: Int64) throws(EditorError) {
        guard let dir = try? directory,
              let values = try? dir.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
              let free = values.volumeAvailableCapacityForImportantUsage else { return }
        if free < bytes { throw .logged(.insufficientStorage, "need \(bytes) free \(free)") }
    }
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
