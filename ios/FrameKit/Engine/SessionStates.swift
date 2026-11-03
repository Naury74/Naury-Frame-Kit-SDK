import Foundation

/// 세션에 남기는 사진 편집. 파일은 세션 media 안 이름이다.
struct ImageSessionState: Codable {
    struct Page: Codable {
        var file: String
        var current: PhotoEdit
        var baseline: PhotoEdit
        var cutout: String?
        var baselineCutout: String?
    }

    var pages: [Page]
    var pageIndex: Int
}

/// 소리 원본 파일과 보이는 이름.
struct SessionAudioFile: Codable {
    var file: String
    var name: String
}

/// 세션에 남기는 영상 편집.
struct VideoSessionState: Codable {
    var sources: [String]
    var audio: [SessionAudioFile]
    var current: VideoEdit
    var baseline: VideoEdit
}

/// 세션에 남기는 움직이는 이미지 편집.
struct MotionSessionState: Codable {
    var sources: [String]
    var audio: [SessionAudioFile]
    var current: MotionProject
    var baseline: MotionProject
}

/// 세션에 남기는 음성 편집.
struct AudioSessionState: Codable {
    var sources: [SessionAudioFile]
    var current: AudioProjectState
    var baseline: AudioProjectState
}

extension EditSession {
    /// 소리 원본들을 media에 남기고 이름 목록을 돌려준다.
    func audioFiles(_ sources: [AudioSource], into files: inout Set<String>) -> [SessionAudioFile] {
        sources.map { source in
            let file = name(for: source.url)
            files.insert(file)
            return SessionAudioFile(file: file, name: source.name)
        }
    }

    /// 남긴 파일들을 임시 폴더에 링크한다. 하나라도 없으면 링크한 것을 지우고 `nil`.
    func restoreFiles(_ names: [String]) -> [URL]? {
        var urls: [URL] = []
        for name in names {
            guard let url = restoreFile(name) else {
                urls.forEach { try? FileManager.default.removeItem(at: $0) }
                return nil
            }
            urls.append(url)
        }
        return urls
    }
}
