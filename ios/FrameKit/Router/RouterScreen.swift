import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// 진입점 화면 뿌리.
struct RouterRoot: View {
    let model: RouterModel
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let ui = model.request.ui
        let theme = EditorTheme.make(ui, systemDark: colorScheme == .dark)
        Group {
            switch model.phase {
            case .starting, .working:
                ProgressView().controlSize(.large).tint(theme.accent).frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(theme.background.ignoresSafeArea())
            case .picking(let kind, let limit):
                if kind == .audio {
                    AudioFilesPicker(limit: limit) { model.picked($0) }.ignoresSafeArea()
                } else {
                    MediaPicker(kind: kind, limit: limit) { model.picked($0) }.ignoresSafeArea()
                }
            case .chooser(let choice):
                ChooserScreen(model: model, choice: choice)
            case .failed(let error):
                RouterFailureView(model: model, error: error)
            case .editor(let editor):
                switch editor {
                case .image(let editorModel): EditorRoot(model: editorModel)
                case .video(let editorModel): VideoEditorRoot(model: editorModel)
                case .motion(let editorModel): MotionEditorRoot(model: editorModel)
                case .audio(let editorModel): AudioEditorRoot(model: editorModel)
                case .mixed(let editorModel): MixedEditorScreen(model: editorModel)
                }
            }
        }
        .overlay(alignment: .bottom) {
            if let notice = model.notice {
                NoticeToast(text: Strings(localeIdentifier: ui.localeIdentifier)(notice), theme: theme).padding(.bottom, 96)
            }
        }
        .environment(\.editorTheme, theme)
        .environment(\.strings, Strings(localeIdentifier: ui.localeIdentifier))
        .environment(\.haptics, Haptics(enabled: ui.enableHaptics))
        .environment(\.editorFontId, ui.uiFontId)
        .preferredColorScheme(ui.themeMode == .system ? nil : (theme.isDark ? .dark : .light))
        .tint(theme.accent)
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        .onAppear { model.start() }
    }
}

/// 고른 원본을 열 수 없을 때.
private struct RouterFailureView: View {
    let model: RouterModel
    let error: EditorError
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle").font(.system(size: 36)).foregroundStyle(theme.error)
            Text(strings(errorKey(error.code))).scaledFont(16).foregroundStyle(theme.foreground)
                .multilineTextAlignment(.center).padding(.horizontal, 32)
            if model.canChooseAnother && error.suggestedAction == .chooseAnotherSource {
                EditorButton(title: strings("action_choose_another")) { model.chooseAnother() }
            }
            EditorButton(title: strings("action_close"), kind: .tonal) { model.finish(.failure(error)) }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.background.ignoresSafeArea())
    }
}

/// 할 일 고르기.
struct ChooserScreen: View {
    let model: RouterModel
    let choice: RouterModel.Choice
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings

    var body: some View {
        ZStack {
            theme.background.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(strings("chooser_title")).scaledFont(20, weight: .bold).foregroundStyle(theme.foreground)
                        .accessibilityAddTraits(.isHeader)
                    Text(String.localizedStringWithFormat(strings("chooser_count"), choice.items.count)).scaledFont(14)
                        .foregroundStyle(theme.foregroundMuted)
                    thumbnails
                    if choice.mixed {
                        Label(strings("chooser_mixed"), systemImage: "info.circle").scaledFont(13).foregroundStyle(theme.foregroundMuted)
                            .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                            .background(theme.surface, in: RoundedRectangle(cornerRadius: 12))
                    }
                    ForEach(choice.actions.filter { $0 != .sendOriginal }, id: \.self) { action in card(action) }
                    if choice.actions.contains(.sendOriginal) { sendRow }
                    HStack {
                        Spacer()
                        EditorButton(title: strings("action_cancel"), kind: .tonal) { model.cancelChooser() }
                    }
                }
                .padding(20)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
        }
    }

    private var thumbnails: some View {
        let shown = min(choice.items.count, RouterModel.maxThumbnails)
        return HStack(spacing: 8) {
            ForEach(0..<shown, id: \.self) { i in
                ZStack {
                    theme.raised
                    if let image = model.thumbnails[i] {
                        Image(decorative: image, scale: 1).resizable().scaledToFill()
                    } else if choice.kinds[i] == .audio {
                        Image(systemName: "waveform").font(.system(size: 22)).foregroundStyle(theme.foregroundMuted)
                    }
                    if i == shown - 1 && choice.items.count > shown {
                        Color.black.opacity(0.55)
                        Text("+\(choice.items.count - shown + 1)").scaledFont(15, weight: .semibold).foregroundStyle(.white)
                    }
                }
                .frame(width: 64, height: 64)
                .clipShape(RoundedRectangle(cornerRadius: 12))
            }
        }
        .accessibilityHidden(true)
    }

    private func card(_ action: EditorAction) -> some View {
        let (title, body, icon, color) = info(action)
        return Button { model.choose(action) } label: {
            HStack(spacing: 14) {
                icon.resizable().scaledToFit().frame(width: 22, height: 22).foregroundStyle(color)
                    .frame(width: 44, height: 44).background(color.opacity(0.16), in: Circle())
                VStack(alignment: .leading, spacing: 2) {
                    Text(strings(title)).scaledFont(15, weight: .semibold).foregroundStyle(theme.foreground)
                    Text(strings(body)).scaledFont(13).foregroundStyle(theme.foregroundMuted).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold)).foregroundStyle(theme.foregroundMuted)
            }
            .padding(14).frame(minHeight: 72)
            .background(theme.background, in: RoundedRectangle(cornerRadius: 18))
            .overlay { RoundedRectangle(cornerRadius: 18).strokeBorder(theme.raised, lineWidth: 1) }
            .contentShape(Rectangle())
        }
        .buttonStyle(PressFadeStyle())
    }

    private var sendRow: some View {
        Button { model.choose(.sendOriginal) } label: {
            HStack(spacing: 12) {
                Image(systemName: "paperplane").font(.system(size: 18)).foregroundStyle(theme.foreground)
                VStack(alignment: .leading, spacing: 2) {
                    Text(strings("chooser_send")).scaledFont(15, weight: .semibold).foregroundStyle(theme.foreground)
                    Text(strings("chooser_send_body")).scaledFont(13).foregroundStyle(theme.foregroundMuted)
                }
                Spacer()
            }
            .padding(14).frame(minHeight: 56)
            .background(theme.raised.opacity(0.6), in: RoundedRectangle(cornerRadius: 14))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressFadeStyle())
    }

    private func info(_ action: EditorAction) -> (String, String, Image, Color) {
        let single = choice.items.count == 1
        switch action {
        case .editPhoto:
            let key = single && choice.kinds == [.animated] ? "chooser_photo_frame" : (single ? "chooser_photo" : "chooser_photo_each")
            return (key, key + "_body", Icons.image("adjust"), theme.accent)
        case .makePDF: return ("chooser_pdf", "chooser_pdf_body", Icons.image("document"), Color(argb: 0xFF3B_9CFF))
        case .motion:
            let key = single && choice.kinds == [.animated] ? "chooser_motion_edit" : "chooser_motion"
            return (key, key + "_body", Icons.image("motion"), Color(argb: 0xFFFF_6B9A))
        case .editVideo: return ("chooser_video", "chooser_video_body", Icons.image("trim"), Color(argb: 0xFF2E_B67D))
        case .editAudio: return ("chooser_audio", "chooser_audio_body", Icons.image("mic"), Color(argb: 0xFFF5_9F00))
        case .editEach: return ("chooser_each", "chooser_each_body", Icons.image("adjust"), theme.accent)
        case .sendOriginal: return ("chooser_send", "chooser_send_body", Image(systemName: "paperplane"), theme.foreground)
        }
    }
}

/// 사진·영상 선택기. 영상은 영상으로, 사진은 움직이는 형식을 먼저 골라 임시 폴더에 복사한다. 닫으면 빈 결과.
struct MediaPicker: UIViewControllerRepresentable {
    let kind: MediaKind
    let limit: Int
    let onPick: (PickedFiles) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = switch kind {
        case .image: .images
        case .video: .videos
        default: .any(of: [.images, .videos])
        }
        config.selectionLimit = max(1, limit)
        config.selection = .ordered
        config.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_: PHPickerViewController, context _: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onPick: (PickedFiles) -> Void
        private var done = false
        init(onPick: @escaping (PickedFiles) -> Void) { self.onPick = onPick }

        func picker(_: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard !done else { return }
            done = true
            let providers = results.map(\.itemProvider)
            guard !providers.isEmpty else { onPick(PickedFiles()); return }
            let onPick = onPick
            Task { @MainActor in
                var files = PickedFiles()
                for provider in providers {
                    if provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                        files.add(await VideoPicker.Coordinator.copy(VideoEditorModel.ItemBox(provider: provider)))
                    } else {
                        files.add(await MotionEditorModel.copy(MotionEditorModel.ItemProviderBox(provider: provider)))
                    }
                }
                onPick(files)
            }
        }
    }
}
