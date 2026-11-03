import AVFoundation
import SwiftUI
import UniformTypeIdentifiers

/// 시간 범위 줄: 표시 구간, 여기서 시작·끝, 삭제.
struct TimedRangeRow: View {
    let model: VideoEditorModel
    let help: String
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        Group {
            if let range = model.selectedRange {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(strings("mask_range", formatTime(range.start), formatTime(range.end)))
                            .font(.system(size: 14, weight: .medium)).foregroundStyle(theme.foreground)
                        Spacer()
                        IconButton(icon: Image(systemName: "trash"), label: strings("item_delete")) { model.deleteSelected() }
                    }
                    HStack(spacing: 8) {
                        EditorButton(title: strings("mask_start_here"), systemImage: "arrow.right.to.line", kind: .tonal) { model.setSelectedEdge(start: true) }
                        EditorButton(title: strings("mask_end_here"), systemImage: "arrow.left.to.line", kind: .tonal) { model.setSelectedEdge(start: false) }
                    }
                }
            } else {
                Text(help).font(.system(size: 13)).foregroundStyle(theme.foregroundMuted)
            }
        }
        .padding(.horizontal, 16)
    }
}

/// 소리 도구: 원본 소리와 음악·음성 추가.
struct VideoAudioPanel: View {
    let model: VideoEditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            AudioPanel(model: model)
            Text(strings("sound_timeline_help")).font(.system(size: 13)).foregroundStyle(theme.foregroundMuted).padding(.horizontal, 16)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    EditorButton(title: strings("track_music_add"), systemImage: "music.note", kind: .tonal) { model.showMusicPicker = true }
                    EditorButton(title: strings("track_voice_add"), systemImage: "waveform", kind: .tonal) {
                        model.musicPickerRole = .voice
                        model.showMusicPicker = true
                    }
                    if model.micDeclared {
                        EditorButton(title: strings("voice_record"), systemImage: "mic.fill", kind: .tonal) { model.showRecorder = true }
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }
}

/// 타임라인에서 고른 것에 따라 도구 줄 대신 보이는 조작부.
struct TimelineSelectionPanel: View {
    @Bindable var model: VideoEditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if model.muteSelection != nil {
                Text(strings("mute_range_help")).font(.system(size: 13)).foregroundStyle(theme.foregroundMuted)
                HStack(spacing: 8) {
                    EditorButton(title: strings("mask_start_here"), kind: .tonal) { model.setMuteEdge(start: true) }
                    EditorButton(title: strings("mask_end_here"), kind: .tonal) { model.setMuteEdge(start: false) }
                }
                HStack {
                    EditorButton(title: strings("action_cancel"), kind: .tonal) { model.muteSelection = nil }
                    Spacer()
                    EditorButton(title: strings("original_mute_apply"), systemImage: "speaker.slash.fill") { model.applyMuteSelection() }
                }
            } else if let audio = model.selectedAudio {
                header(strings(audio.role == .music ? "track_music" : "track_voice"))
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        EditorButton(title: strings("clip_split"), systemImage: "scissors", kind: .tonal) { model.splitSelectedAudio() }
                        ToggleChip(title: strings("track_loop"), isOn: audio.loop) {
                            model.changeSelectedAudio { $0.loop.toggle(); if $0.loop { $0.timelineEnd = nil } }
                        }
                        EditorButton(title: strings("item_delete"), systemImage: "trash", kind: .tonal) { model.deleteSelectedAudio() }
                    }
                }
                ValueSlider(label: strings("track_volume"), value: audio.volume * 100, range: 0...200, format: { "\(Int($0.rounded()))%" },
                            onChange: { v in model.changeSelectedAudio(commit: false) { $0.volume = v / 100 } }, onFinish: { model.commitGesture() })
                    .padding(.horizontal, -16)
                let limit = Double(min(audio.outputRange(videoDuration: model.duration).duration / 2, 5_000_000)) / 1_000_000
                if limit > 0.05 {
                    ValueSlider(label: strings("audio_fade_in"), value: Double(audio.fadeIn) / 1_000_000, range: 0...limit, format: { String(format: "%.1fs", $0) },
                                onChange: { v in model.changeSelectedAudio(commit: false) { $0.fadeIn = Micros(v * 1_000_000) } }, onFinish: { model.commitGesture() })
                        .padding(.horizontal, -16)
                    ValueSlider(label: strings("audio_fade_out"), value: Double(audio.fadeOut) / 1_000_000, range: 0...limit, format: { String(format: "%.1fs", $0) },
                                onChange: { v in model.changeSelectedAudio(commit: false) { $0.fadeOut = Micros(v * 1_000_000) } }, onFinish: { model.commitGesture() })
                        .padding(.horizontal, -16)
                }
            } else if model.originalSelected, let clip = model.selectedClip {
                header(strings("original_sound"))
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        if model.playheadMuted {
                            EditorButton(title: strings("original_restore"), systemImage: "speaker.wave.2.fill", kind: .tonal) { model.restoreMutedAtPlayhead() }
                        } else {
                            EditorButton(title: strings("original_mute_range"), systemImage: "speaker.slash", kind: .tonal) { model.beginMuteSelection() }
                        }
                        ToggleChip(title: strings("audio_mute"), isOn: clip.muted) { model.toggleMute() }
                    }
                }
                ValueSlider(label: strings("audio_volume"), value: clip.volume * 100, range: 0...200, format: { "\(Int($0.rounded()))%" },
                            onChange: { v in model.setVolume(abs(v - 100) < 4 ? 1 : v / 100, commit: false) }, onFinish: { model.commitGesture() })
                    .padding(.horizontal, -16)
            } else {
                TimedRangeRow(model: model, help: "").padding(.horizontal, -16)
            }
            if model.muteSelection == nil {
                HStack {
                    Spacer()
                    EditorButton(title: strings("selection_done"), systemImage: "checkmark") { model.clearSelection() }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private func header(_ title: String) -> some View {
        Text(title).font(.system(size: 15, weight: .semibold)).foregroundStyle(theme.foreground)
    }
}

/// 소리 파일 선택(파일 앱). 사본을 넘긴다.
struct AudioFilePicker: UIViewControllerRepresentable {
    let onPick: (URL?) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.audio, .movie, .mpeg4Movie], asCopy: true)
        picker.delegate = context.coordinator
        picker.allowsMultipleSelection = false
        return picker
    }

    func updateUIViewController(_: UIDocumentPickerViewController, context _: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL?) -> Void
        init(onPick: @escaping (URL?) -> Void) { self.onPick = onPick }
        func documentPicker(_: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { onPick(urls.first) }
        func documentPickerWasCancelled(_: UIDocumentPickerViewController) { onPick(nil) }
    }
}

/// 녹음 시트. 권한을 묻고, 녹음 중에는 시간과 소리 크기를 보인다. 0.5초보다 짧으면 쓰지 않는다. 10분이면 저절로 멈춘다.
struct VoiceRecorderSheet: View {
    let onDone: (URL?) -> Void
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme
    @State private var recorder: AVAudioRecorder?
    @State private var state = RecordState.waiting
    @State private var levels: [CGFloat] = Array(repeating: 0, count: 40)
    @State private var elapsed: TimeInterval = 0
    @State private var url: URL?
    @State private var audioSession = RecordingAudioSession()

    enum RecordState: Equatable { case waiting, recording, error(String) }

    var body: some View {
        VStack(spacing: 18) {
            switch state {
            case .waiting:
                ProgressView().tint(theme.accent)
                Text(strings("voice_waiting")).foregroundStyle(theme.foreground)
            case .recording:
                Image(systemName: "mic.fill").font(.system(size: 28)).foregroundStyle(.red)
                Text(String(format: "%d:%02d", Int(elapsed) / 60, Int(elapsed) % 60)).font(.system(size: 28, weight: .semibold).monospacedDigit())
                    .foregroundStyle(theme.foreground)
                HStack(alignment: .center, spacing: 2) {
                    ForEach(levels.indices, id: \.self) { i in
                        Capsule().fill(theme.accent).frame(width: 4, height: 4 + levels[i] * 32)
                    }
                }
                .frame(height: 40)
                .accessibilityHidden(true)
                Text(strings("voice_recording")).foregroundStyle(theme.foregroundMuted).accessibilityAddTraits(.updatesFrequently)
            case .error(let key):
                Image(systemName: "exclamationmark.triangle").font(.system(size: 28)).foregroundStyle(theme.error)
                Text(strings(key)).multilineTextAlignment(.center).foregroundStyle(theme.foreground)
            }
            HStack {
                EditorButton(title: strings("action_cancel"), kind: .tonal) { finish(save: false) }
                Spacer()
                if state == .recording { EditorButton(title: strings("voice_stop"), systemImage: "stop.fill") { finish(save: true) } }
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity)
        .presentationBackground(theme.surface)
        .presentationDetents([.height(320)])
        .interactiveDismissDisabled()
        .task { await start() }
        .onDisappear {
            if recorder?.isRecording == true { recorder?.stop(); recorder?.deleteRecording() }
            audioSession.end()
        }
    }

    private func start() async {
        guard await AVAudioApplication.requestRecordPermission() else { state = .error("voice_permission_denied"); return }
        do {
            try audioSession.begin()
            let file = try OutputStore.temporaryDirectory.appendingPathComponent("voice-\(UUID().uuidString).m4a")
            let recorder = try AVAudioRecorder(url: file, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44_100,
                                                                     AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 128_000])
            recorder.isMeteringEnabled = true
            guard recorder.record(forDuration: 600) else { throw EditorError(code: .unknown) }
            self.recorder = recorder
            url = file
            state = .recording
            while !Task.isCancelled, recorder.isRecording {
                try? await Task.sleep(for: .milliseconds(100))
                recorder.updateMeters()
                elapsed = recorder.currentTime
                let level = CGFloat(max(0, min(1, (recorder.averagePower(forChannel: 0) + 50) / 50)))
                levels = Array(levels.dropFirst()) + [level]
            }
            if state == .recording, !recorder.isRecording { finish(save: true) }
        } catch {
            audioSession.end()
            state = .error("voice_failed")
        }
    }

    private func finish(save: Bool) {
        let duration = recorder?.currentTime ?? elapsed
        recorder?.stop()
        audioSession.end()
        if save, let url {
            if duration < 0.5 { state = .error("voice_too_short"); return }
            onDone(url)
        } else {
            if let url { try? FileManager.default.removeItem(at: url) }
            onDone(nil)
        }
    }
}
