import AVFoundation
import UIKit

/// 저장 중 화면 꺼짐을 막고, 앱이 잠시 뒤로 가도 저장을 이어 가도록 시간을 받는다. 모두 풀리면 호스트 앱이 정해 둔 값으로 돌린다.
@MainActor
enum IdleTimerHold {
    private static var holds = 0
    private static var original = false
    private static var background = UIBackgroundTaskIdentifier.invalid

    static func acquire() {
        if holds == 0 {
            original = UIApplication.shared.isIdleTimerDisabled
            UIApplication.shared.isIdleTimerDisabled = true
            beginBackground()
        }
        holds += 1
    }

    static func release() {
        guard holds > 0 else { return }
        holds -= 1
        if holds == 0 {
            UIApplication.shared.isIdleTimerDisabled = original
            endBackground()
        }
    }

    private static func beginBackground() {
        guard background == .invalid else { return }
        // 시간이 다 되면 바로 돌려줘야 앱이 종료되지 않는다. 멈춘 저장은 다시 시도할 수 있는 오류가 된다.
        background = UIApplication.shared.beginBackgroundTask(withName: "FrameKit export") {
            MainActor.assumeIsolated { endBackground() }
        }
    }

    private static func endBackground() {
        guard background != .invalid else { return }
        UIApplication.shared.endBackgroundTask(background)
        background = .invalid
    }
}

/// 녹음용 오디오 세션. 끝나면 호스트 앱의 카테고리·모드·옵션으로 돌린다.
@MainActor
final class RecordingAudioSession {
    private struct Saved {
        let category: AVAudioSession.Category
        let mode: AVAudioSession.Mode
        let options: AVAudioSession.CategoryOptions
        /// 호스트가 세션을 직접 쓰고 있다고 본다. iOS는 활성 여부를 알려 주지 않으므로 기본값에서 바뀐 카테고리·모드로 판단한다.
        let hostManaged: Bool
    }

    private var saved: Saved?

    func begin() throws {
        let session = AVAudioSession.sharedInstance()
        if saved == nil {
            saved = Saved(category: session.category, mode: session.mode, options: session.categoryOptions,
                          hostManaged: session.category != .soloAmbient || session.mode != .default)
        }
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try session.setActive(true)
    }

    func end() {
        guard let saved else { return }
        self.saved = nil
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(saved.category, mode: saved.mode, options: saved.options)
        // 호스트가 쓰던 세션은 끄지 않는다. 끄면 호스트의 재생이 멈춘다.
        if !saved.hostManaged { try? session.setActive(false, options: .notifyOthersOnDeactivation) }
    }
}

/// 마이크 권한.
enum MicPermission {
    enum State { case undetermined, denied, granted }

    static var state: State {
        if #available(iOS 17, *) {
            switch AVAudioApplication.shared.recordPermission {
            case .granted: return .granted
            case .denied: return .denied
            default: return .undetermined
            }
        }
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted: return .granted
        case .denied: return .denied
        default: return .undetermined
        }
    }

    /// 처음이면 묻는다. 허용되면 `true`.
    static func request() async -> Bool {
        if #available(iOS 17, *) { return await AVAudioApplication.requestRecordPermission() }
        return await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
        }
    }
}

/// 카메라 권한.
enum CameraPermission {
    /// 처음이면 묻는다. 허용되면 `true`.
    static func request() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return true
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: .video)
        default: return false
        }
    }
}
