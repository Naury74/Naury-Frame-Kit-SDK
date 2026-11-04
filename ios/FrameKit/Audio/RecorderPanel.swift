import AVFoundation
import SwiftUI

/// 마이크 녹음. 끝나면 오디오 세션을 앱 설정으로 돌린다.
@MainActor
final class VoiceRecorder: ObservableObject {
    enum State: Equatable { case idle, recording, paused, holding }

    static let minDuration: TimeInterval = 0.5
    static let levelCount = 240

    @Published private(set) var state = State.idle
    @Published private(set) var elapsed: TimeInterval = 0
    @Published private(set) var levels: [CGFloat] = []
    /// 문자열 키. 권한 없음·마이크 실패.
    @Published private(set) var errorKey: String?
    private let maxDuration: TimeInterval
    @Published private var recorder: AVAudioRecorder?
    @Published private var url: URL?
    private let session = RecordingAudioSession()
    private var meterTask: Task<Void, Never>?
    @Published private(set) var starting = false
    private var cancelStart = false
    var onLimit: (() -> Void)?
    /// 마이크 권한을 거절했다.
    var onPermissionDenied: (() -> Void)?
    private var interruptionObserver: NSObjectProtocol?

    init(maxDuration: TimeInterval) {
        self.maxDuration = maxDuration
    }

    static var micDeclared: Bool { Bundle.main.object(forInfoDictionaryKey: "NSMicrophoneUsageDescription") != nil }

    func start(holding: Bool) async {
        guard state == .idle, !starting else { return }
        starting = true
        cancelStart = false
        defer { starting = false }
        errorKey = nil
        guard Self.micDeclared else { errorKey = "audio_mic_failed"; return }
        guard await MicPermission.request() else {
            errorKey = "audio_permission"
            if !cancelStart { onPermissionDenied?() }
            return
        }
        // 권한을 묻는 사이 손을 뗐거나 편집기가 닫혔으면 시작하지 않는다.
        guard !cancelStart else { return }
        do {
            try session.begin()
            let file = try OutputStore.temporaryDirectory.appendingPathComponent("voice-\(UUID().uuidString).m4a")
            let recorder = try AVAudioRecorder(url: file, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44_100,
                                                                     AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 128_000])
            recorder.isMeteringEnabled = true
            guard recorder.record() else { throw EditorError(code: .unknown) }
            // 권한을 묻는 사이 손을 뗐으면 녹음하지 않는다.
            if cancelStart {
                recorder.stop()
                recorder.deleteRecording()
                session.end()
                return
            }
            self.recorder = recorder
            url = file
            levels = []
            elapsed = 0
            state = holding ? .holding : .recording
            observeInterruption()
            meter()
        } catch {
            session.end()
            errorKey = "audio_mic_failed"
        }
    }

    private func meter() {
        meterTask?.cancel()
        meterTask = Task {
            while !Task.isCancelled, let recorder, state != .idle {
                try? await Task.sleep(nanoseconds: 50_000_000)
                guard state == .recording || state == .holding else { continue }
                recorder.updateMeters()
                elapsed = recorder.currentTime
                levels.append(CGFloat(max(0, min(1, (recorder.averagePower(forChannel: 0) + 50) / 50))))
                if levels.count > Self.levelCount { levels.removeFirst(levels.count - Self.levelCount) }
                if elapsed >= maxDuration { onLimit?() }
            }
        }
    }

    func pause() {
        guard state == .recording else { return }
        recorder?.pause()
        state = .paused
    }

    func resume() {
        guard state == .paused else { return }
        // 끊긴 뒤에는 세션을 다시 켜야 녹음된다.
        try? AVAudioSession.sharedInstance().setActive(true)
        guard recorder?.record() == true else { errorKey = "audio_mic_failed"; return }
        state = .recording
    }

    func lock() { if state == .holding { state = .recording } }

    /// 시작하는 중이면 시작하지 않게 한다.
    func cancelPendingStart() { if starting { cancelStart = true } }

    /// 녹음 파일. 0.5초보다 짧으면 지우고 `nil`.
    func finish() -> URL? {
        guard state != .idle, let recorder else { return nil }
        let duration = recorder.currentTime
        recorder.stop()
        reset()
        guard let url else { return nil }
        self.url = nil
        if duration < Self.minDuration {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return url
    }

    func discard() {
        recorder?.stop()
        recorder?.deleteRecording()
        reset()
        // 녹음기가 지우지 못한 경우에도 임시 파일을 남기지 않는다.
        if let url { try? FileManager.default.removeItem(at: url) }
        url = nil
    }

    #if DEBUG
    /// 시험용. 마이크 없이 [file]에 녹음 중인 상태를 만든다.
    func simulateRecording(into file: URL) throws {
        recorder = try AVAudioRecorder(url: file, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44_100, AVNumberOfChannelsKey: 1])
        url = file
        state = .recording
    }
    #endif

    private func reset() {
        meterTask?.cancel()
        recorder = nil
        state = .idle
        if let interruptionObserver { NotificationCenter.default.removeObserver(interruptionObserver) }
        interruptionObserver = nil
        session.end()
    }

    /// 전화 등으로 녹음이 끊기면 시스템이 녹음을 멈추므로 일시 정지 상태로 맞춘다. 이어서 녹음은 사용자가 고른다.
    private func observeInterruption() {
        guard interruptionObserver == nil else { return }
        interruptionObserver = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil,
                                                                      queue: .main) { [weak self] note in
            let began = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init) == .began
            MainActor.assumeIsolated {
                guard began, let self, self.state == .recording || self.state == .holding else { return }
                self.recorder?.pause()
                self.state = .paused
            }
        }
    }
}

/// 녹음 조작부. [onFinish]는 파일(짧으면 `nil`)을 받는다.
struct RecorderPanel: View {
    @ObservedObject var recorder: VoiceRecorder
    let mode: RecordMode
    var compact = false
    let onFinish: (URL?) -> Void
    @State private var holdMode = false
    @State private var drag = CGSize.zero
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings
    @Environment(\.haptics) private var haptics

    private static let red = Color(red: 1, green: 0.30, blue: 0.37)
    private static let lockDistance: CGFloat = 72
    private static let cancelDistance: CGFloat = 96

    var body: some View {
        VStack(spacing: compact ? 10 : 16) {
            Text(clock(recorder.elapsed)).scaledFont(compact ? 28 : 44, weight: .light, monospacedDigit: true)
                .foregroundStyle(theme.foreground)
                .accessibilityAddTraits(.updatesFrequently)
            Text(status).scaledFont(14).foregroundStyle(recorder.errorKey != nil ? theme.error : theme.foregroundMuted)
                .multilineTextAlignment(.center)
            wave.frame(height: compact ? 64 : 120)
            if mode == .both && recorder.state == .idle {
                HStack(spacing: 8) {
                    ChoiceChip(title: strings("audio_mode_tap"), selected: !holdMode) { holdMode = false }
                    ChoiceChip(title: strings("audio_mode_hold"), selected: holdMode) { holdMode = true }
                }
            }
            controls.frame(height: compact ? 128 : 168)
        }
        .onAppear { holdMode = mode == .hold }
        .onDisappear {
            recorder.cancelPendingStart()
            if recorder.state != .idle { recorder.discard() }
        }
    }

    private var status: String {
        if let key = recorder.errorKey { return strings(key) }
        switch recorder.state {
        case .paused: return strings("audio_paused")
        case .recording: return strings("audio_recording")
        case .holding: return strings("audio_slide_cancel")
        case .idle: return strings(holdMode ? "audio_hold_hint" : "audio_tap_hint")
        }
    }

    private var wave: some View {
        Canvas { context, size in
            let step: CGFloat = 5.5
            let count = Int(size.width / step)
            let values = recorder.levels.suffix(count)
            for (i, level) in values.enumerated() {
                let x = size.width - CGFloat(values.count - i) * step
                let h = max(3, level * size.height)
                context.fill(Path(roundedRect: CGRect(x: x, y: (size.height - h) / 2, width: 3, height: h), cornerRadius: 1.5),
                             with: .color(recorder.state == .paused ? theme.foregroundMuted : Self.red))
            }
        }
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private var controls: some View {
        switch recorder.state {
        case .recording, .paused:
            HStack(spacing: 28) {
                circleButton("xmark", label: strings("audio_discard"), size: 52, fill: theme.raised, tint: theme.foreground) { recorder.discard() }
                circleButton(recorder.state == .paused ? "mic.fill" : "pause.fill",
                             label: strings(recorder.state == .paused ? "audio_resume" : "audio_pause"), size: 76, fill: Self.red, tint: .white) {
                    recorder.state == .paused ? recorder.resume() : recorder.pause()
                }
                circleButton("checkmark", label: strings("audio_done"), size: 52, fill: theme.accent, tint: theme.onAccent) { onFinish(recorder.finish()) }
            }
        case .holding:
            holdButton
        case .idle:
            if holdMode {
                holdButton
            } else {
                circleButton("mic.fill", label: strings("audio_record"), size: 76, fill: Self.red, tint: .white) {
                    haptics.mark()
                    Task { await recorder.start(holding: false) }
                }
            }
        }
    }

    private var holdButton: some View {
        let holding = recorder.state == .holding
        return ZStack {
            if holding {
                VStack(spacing: 2) {
                    Image(systemName: "lock.fill")
                    Image(systemName: "chevron.up")
                }
                .scaledFont(14, weight: .semibold).foregroundStyle(theme.foregroundMuted)
                .offset(y: -88)
            }
            Circle().fill(Self.red).frame(width: 80, height: 80)
                .overlay { Image(systemName: "mic.fill").font(.system(size: 30)).foregroundStyle(.white) }
                .scaleEffect(holding ? 1.18 : 1)
                .offset(x: min(0, drag.width), y: min(0, drag.height))
                .frame(width: 120, height: 120)
                .contentShape(Circle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        if recorder.state == .idle && holdMode && !recorder.starting {
                            haptics.mark()
                            Task { await recorder.start(holding: true) }
                        }
                        drag = g.translation
                        guard recorder.state == .holding else { return }
                        if g.translation.height < -Self.lockDistance {
                            haptics.mark()
                            recorder.lock()
                            drag = .zero
                        } else if g.translation.width < -Self.cancelDistance {
                            haptics.mark()
                            recorder.discard()
                            drag = .zero
                        }
                    }
                    .onEnded { _ in
                        drag = .zero
                        recorder.cancelPendingStart()
                        if recorder.state == .holding { onFinish(recorder.finish()) }
                    })
                .accessibilityLabel(strings("audio_mode_hold"))
                .accessibilityHint(strings("audio_hold_hint"))
                .accessibilityAddTraits(.isButton)
                .accessibilityAction {
                    if recorder.state == .idle { Task { await recorder.start(holding: false) } } else { onFinish(recorder.finish()) }
                }
        }
    }

    private func circleButton(_ symbol: String, label: String, size: CGFloat, fill: Color, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: size * 0.36, weight: .semibold)).foregroundStyle(tint)
                .frame(width: size, height: size).background(fill, in: Circle())
                .frame(minWidth: 48, minHeight: 48).contentShape(Circle())
        }
        .buttonStyle(PressFadeStyle())
        .accessibilityLabel(label)
    }
}

/// `m:ss.d`.
func clock(_ seconds: TimeInterval) -> String {
    let tenths = Int((max(0, seconds) * 10).rounded(.down))
    return String(format: "%d:%02d.%d", tenths / 600, tenths / 10 % 60, tenths % 10)
}
