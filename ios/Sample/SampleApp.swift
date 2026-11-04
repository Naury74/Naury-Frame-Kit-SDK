import AVFoundation
import FrameKit
import QuickLook
import SwiftUI

@main
struct SampleApp: App {
    @State private var settings = SampleSettings()
    @State private var store = ResultStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(settings)
                .environment(store)
        }
    }
}

/// 띄울 편집기.
enum Launch: Identifiable {
    case router(FrameKitRequest)
    case image(ImageEditorRequest)
    case video(VideoEditorRequest)
    case motion(MotionEditorRequest)
    case audio(AudioEditorRequest)

    var id: String {
        switch self {
        case .router: "router"
        case .image: "image"
        case .video: "video"
        case .motion: "motion"
        case .audio: "audio"
        }
    }

    var title: String {
        switch self {
        case .router: "FrameKit"
        case .image: "사진"
        case .video: "영상"
        case .motion: "움직이는 이미지"
        case .audio: "음성"
        }
    }
}

struct RootView: View {
    @Environment(SampleSettings.self) private var settings
    @Environment(ResultStore.self) private var store
    @State private var launch: Launch?
    @State private var tab = 0
    /// 편집기 닫기 연결점. 왼쪽 가장자리 밀기를 여기에 잇는다.
    @State private var control = FrameKitEditorControl()
    /// 녹음만 하는 시트.
    @State private var recorderSheet = false

    var body: some View {
        TabView(selection: $tab) {
            HomeView(launch: $launch, recorderSheet: $recorderSheet)
                .tabItem { Label("편집", systemImage: "wand.and.stars") }.tag(0)
            ResultsView()
                .tabItem { Label("결과", systemImage: "tray.full") }.tag(1)
                .badge(store.unseen)
            SettingsView()
                .tabItem { Label("설정", systemImage: "slider.horizontal.3") }.tag(2)
        }
        .tint(settings.accent)
        .onChange(of: tab) { _, new in if new == 1 { store.unseen = 0 } }
        .fullScreenCover(item: $launch) { item in
            editor(item)
                .ignoresSafeArea()
                .interactiveDismissDisabled()
                .overlay(alignment: .leading) { EdgeSwipeToClose(control: control) }
        }
        .frameKitAudioEditor(isPresented: $recorderSheet, request: recorderRequest, control: control) { store.add($0, from: "녹음") }
        .onAppear(perform: autoOpen)
    }

    /// UIKit 화면으로 녹음 시트를 띄운다.
    private func presentRecorderSheetUIKit() {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard var top = scene?.windows.first(where: \.isKeyWindow)?.rootViewController ?? scene?.windows.first?.rootViewController else { return }
        while let presented = top.presentedViewController { top = presented }
        let editor = FrameKitAudioEditorViewController(request: recorderRequest, control: control) { store.add($0, from: "녹음(UIKit)") }
        top.present(editor, animated: true)
    }

    private var recorderRequest: AudioEditorRequest {
        var request = settings.audio(.capture)
        request.config.editAfterRecording = false
        request.config.recorderPresentation = .sheet
        return request
    }

    @ViewBuilder
    private func editor(_ item: Launch) -> some View {
        let done: @MainActor (FrameKitResult) -> Void = { result in
            launch = nil
            store.add(result, from: item.title)
        }
        switch item {
        case .router(let request): FrameKitEditor(request: request, control: control, onResult: done)
        case .image(let request): FrameKitImageEditor(request: request, control: control, onResult: done)
        case .video(let request): FrameKitVideoEditor(request: request, control: control, onResult: done)
        case .motion(let request): FrameKitMotionEditor(request: request, control: control, onResult: done)
        case .audio(let request): FrameKitAudioEditor(request: request, control: control, onResult: done)
        }
    }

    /// 자동 확인용 실행 인자.
    private func autoOpen() {
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("-themeLight") { settings.themeMode = .light }
        if arguments.contains("-themeSystem") { settings.themeMode = .system }
        if arguments.contains("-watermark") { settings.hostWatermark = true }
        if arguments.contains("-pdf") { settings.imageFormat = .pdf }
        if arguments.contains("-openSample") { launch = .image(settings.image(.data(SampleImage.make()))) }
        if arguments.contains("-openSamples") { launch = .image(settings.image(.files(SampleImage.files(count: 3)))) }
        if arguments.contains("-openMotion") { launch = .motion(settings.motion(.files(SampleImage.files(count: 3)))) }
        if arguments.contains("-openGif") { launch = .motion(settings.motion(.data(SampleImage.gif()))) }
        if arguments.contains("-openAudio") { launch = .audio(settings.audio(.files(SampleAudio.files()))) }
        if arguments.contains("-openChooser") { launch = .router(settings.router(.files(SampleImage.files(count: 2)), chooser: .ask)) }
        if arguments.contains("-openMixed") {
            Task {
                let photos = SampleImage.files(count: 2), videos = await SampleVideo.files()
                launch = .router(settings.router(.files([photos[0], videos[0], photos[1], videos[1]])))
            }
        }
        if arguments.contains("-openRecorderSheet") { recorderSheet = true }
        if arguments.contains("-openRecorderSheetUIKit") { Task { try? await Task.sleep(for: .seconds(1)); presentRecorderSheetUIKit() } }
        if arguments.contains("-openVideo") { Task { launch = .video(settings.video(.files(await SampleVideo.files()))) } }
        // 편집기가 열리고 몇 초 뒤 호스트 닫기를 요청한다.
        if arguments.contains("-requestClose") {
            Task {
                try? await Task.sleep(for: .seconds(4))
                control.requestClose()
            }
        }
    }
}

/// 왼쪽 가장자리에서 오른쪽으로 밀면 편집기 닫기를 요청한다. 닫을 수 없는 동안에는 손을 받지 않는다.
struct EdgeSwipeToClose: View {
    @ObservedObject var control: FrameKitEditorControl

    var body: some View {
        Color.clear
            .frame(width: 10)
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 20).onEnded { value in
                if value.translation.width > 80 && abs(value.translation.height) < 120 { control.requestClose() }
            })
            .allowsHitTesting(control.canDismissInteractively)
            .ignoresSafeArea()
    }
}

// MARK: - 편집

struct HomeView: View {
    @Binding var launch: Launch?
    @Binding var recorderSheet: Bool
    @Environment(SampleSettings.self) private var settings

    var body: some View {
        NavigationStack {
            List {
                Section {
                    row("사진·영상 고르기", "photo.on.rectangle.angled", "종류에 맞는 편집기를 바로 엽니다.") {
                        launch = .router(settings.router(.pickMultiple(maxItems: 10)))
                    }
                    row("고르고 할 일 정하기", "list.bullet.rectangle", "편집, PDF, 움직이는 이미지, 원본 보내기 중에서 고릅니다.") {
                        launch = .router(settings.router(.pickMultiple(maxItems: 10), chooser: .ask))
                    }
                    row("샘플 사진으로 시트 보기", "rectangle.stack", "샘플 2장으로 고르기 시트를 엽니다.") {
                        launch = .router(settings.router(.files(SampleImage.files(count: 2)), chooser: .ask))
                    }
                    row("샘플 사진·영상 섞어 편집", "square.on.square", "사진 2장과 영상 2개를 오가며 편집하고 한 번에 저장합니다.") {
                        Task {
                            let photos = SampleImage.files(count: 2), videos = await SampleVideo.files()
                            launch = .router(settings.router(.files([photos[0], videos[0], photos[1], videos[1]])))
                        }
                    }
                    row("카메라로 찍기", "camera", "찍은 사진을 바로 편집합니다.") {
                        launch = .router(settings.router(.capture, kind: .image))
                    }
                } header: {
                    Text("한 번에 열기")
                } footer: {
                    Text("FrameKitRequest 하나로 사진·영상·움직이는 이미지·소리를 엽니다.")
                }

                Section("사진") {
                    row("사진 고르기", "photo", nil) { launch = .image(settings.image(.pick)) }
                    row("여러 장 고르기", "square.stack", nil) { launch = .image(settings.image(.pickMultiple(maxItems: 10))) }
                    row("샘플 사진", "sparkles", nil) { launch = .image(settings.image(.data(SampleImage.make()))) }
                    row("샘플 사진 3장으로 PDF", "doc.richtext", "여러 장을 PDF 하나로 묶습니다.") {
                        launch = .image(settings.image(.files(SampleImage.files(count: 3)), pdf: true))
                    }
                }

                Section("영상") {
                    row("영상 고르기", "film", nil) { launch = .video(settings.video(.pickMultiple(maxItems: 5))) }
                    row("샘플 영상 2개", "film.stack", nil) {
                        Task { launch = .video(settings.video(.files(await SampleVideo.files()))) }
                    }
                }

                Section("움직이는 이미지") {
                    row("사진으로 슬라이드쇼", "play.rectangle.on.rectangle", nil) {
                        launch = .motion(settings.motion(.files(SampleImage.files(count: 3))))
                    }
                    row("샘플 GIF 편집", "livephoto", nil) { launch = .motion(settings.motion(.data(SampleImage.gif()))) }
                    row("사진·GIF 고르기", "photo.stack", nil) { launch = .motion(settings.motion(.pickMultiple(maxItems: 20))) }
                }

                Section("음성") {
                    row("녹음하기", "mic", nil) { launch = .audio(settings.audio(.capture)) }
                    row("녹음만 하기(시트)", "mic.badge.plus", "편집 없이 녹음 파일을 바로 받습니다.") { recorderSheet = true }
                    row("샘플 소리 2개", "waveform", nil) { launch = .audio(settings.audio(.files(SampleAudio.files()))) }
                    row("소리 파일 고르기", "folder", nil) { launch = .audio(settings.audio(.pickMultiple(maxItems: 5))) }
                }
            }
            .navigationTitle("FrameKit \(FrameKitInfo.version)")
        }
    }

    private func row(_ title: String, _ symbol: String, _ detail: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: symbol).font(.title3).foregroundStyle(settings.accent).frame(width: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(.primary)
                    if let detail { Text(detail).font(.footnote).foregroundStyle(.secondary) }
                }
            }
            .padding(.vertical, 2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 결과

/// 편집 결과 기록.
@MainActor
@Observable
final class ResultStore {
    struct Entry: Identifiable {
        let id = UUID()
        let date = Date()
        let source: String
        let outputs: [EditedMedia]
        let message: String?
    }

    private(set) var entries: [Entry] = []
    var unseen = 0

    func add(_ result: FrameKitResult, from source: String) {
        switch result {
        case .success(_, let outputs): entries.insert(Entry(source: source, outputs: outputs, message: nil), at: 0)
        case .cancelled: entries.insert(Entry(source: source, outputs: [], message: "취소됨"), at: 0)
        case .failure(let error): entries.insert(Entry(source: source, outputs: [], message: "실패: \(error.code.rawValue) (\(error.diagnosticId))"), at: 0)
        }
        unseen += 1
    }

    func remove(_ entry: Entry) {
        entry.outputs.forEach { FrameKitOutputs.delete($0.url) }
        entries.removeAll { $0.id == entry.id }
    }
}

struct ResultsView: View {
    @Environment(ResultStore.self) private var store
    @State private var preview: URL?

    var body: some View {
        NavigationStack {
            Group {
                if store.entries.isEmpty {
                    ContentUnavailableView("아직 결과가 없어요", systemImage: "tray", description: Text("편집을 저장하면 여기에 보여요."))
                } else {
                    List {
                        ForEach(store.entries) { entry in
                            Section {
                                if let message = entry.message {
                                    Text(message).foregroundStyle(.secondary)
                                }
                                ForEach(entry.outputs, id: \.url) { media in
                                    Button { preview = media.url } label: { OutputRow(media: media) }
                                        .buttonStyle(.plain)
                                        .contextMenu {
                                            ShareLink(item: media.url)
                                            Button("지우기", systemImage: "trash", role: .destructive) { store.remove(entry) }
                                        }
                                }
                            } header: {
                                Text("\(entry.source) · \(entry.date.formatted(date: .omitted, time: .shortened))")
                            }
                        }
                    }
                }
            }
            .navigationTitle("결과")
            .quickLookPreview($preview)
        }
    }
}

struct OutputRow: View {
    let media: EditedMedia
    @State private var thumbnail: UIImage?

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Color(.secondarySystemBackground)
                if let thumbnail {
                    Image(uiImage: thumbnail).resizable().scaledToFill()
                } else {
                    Image(systemName: symbol).foregroundStyle(.secondary)
                }
            }
            .frame(width: 56, height: 56)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 3) {
                Text(media.url.lastPathComponent).font(.subheadline.weight(.medium)).lineLimit(1).foregroundStyle(.primary)
                Text(detail).font(.caption).foregroundStyle(.secondary)
                if let text = media.recognizedText, !text.isEmpty {
                    Text("인식한 글자: \(text)").font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
        .task { thumbnail = await Self.thumbnail(media) }
    }

    private var symbol: String {
        switch media.mediaType {
        case .audio: "waveform"
        case .document: "doc.richtext"
        case .video: "film"
        default: "photo"
        }
    }

    private var detail: String {
        var parts = [media.mimeType, ByteCountFormatter.string(fromByteCount: media.fileSize, countStyle: .file)]
        if media.width > 0 { parts.append("\(media.width)×\(media.height)") }
        if let ms = media.durationMs { parts.append(String(format: "%.1f초", Double(ms) / 1000)) }
        if let pages = media.pageCount { parts.append("\(pages)쪽") }
        if media.isOriginal { parts.append("원본") }
        if !media.warnings.isEmpty { parts.append(media.warnings.map(\.rawValue).joined(separator: ",")) }
        return parts.joined(separator: " · ")
    }

    private static func thumbnail(_ media: EditedMedia) async -> UIImage? {
        switch media.mediaType {
        case .image, .animatedImage: return UIImage(contentsOfFile: media.url.path)?.preparingThumbnail(of: CGSize(width: 160, height: 160))
        case .video:
            let generator = AVAssetImageGenerator(asset: AVURLAsset(url: media.url))
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: 160, height: 160)
            return (try? await generator.image(at: .zero).image).map(UIImage.init(cgImage:))
        default: return nil
        }
    }
}
