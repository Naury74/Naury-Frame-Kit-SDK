import AVFoundation
import UIKit

/// 저장 중 화면 꺼짐을 막는다. 모두 풀리면 호스트 앱이 정해 둔 값으로 돌린다.
@MainActor
enum IdleTimerHold {
    private static var holds = 0
    private static var original = false

    static func acquire() {
        if holds == 0 {
            original = UIApplication.shared.isIdleTimerDisabled
            UIApplication.shared.isIdleTimerDisabled = true
        }
        holds += 1
    }

    static func release() {
        guard holds > 0 else { return }
        holds -= 1
        if holds == 0 { UIApplication.shared.isIdleTimerDisabled = original }
    }
}

/// 녹음용 오디오 세션. 끝나면 호스트 앱의 카테고리·모드·옵션으로 돌린다.
@MainActor
final class RecordingAudioSession {
    private var saved: (category: AVAudioSession.Category, mode: AVAudioSession.Mode, options: AVAudioSession.CategoryOptions)?

    func begin() throws {
        let session = AVAudioSession.sharedInstance()
        if saved == nil { saved = (session.category, session.mode, session.categoryOptions) }
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try session.setActive(true)
    }

    func end() {
        guard let saved else { return }
        self.saved = nil
        let session = AVAudioSession.sharedInstance()
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
        try? session.setCategory(saved.category, mode: saved.mode, options: saved.options)
    }
}
