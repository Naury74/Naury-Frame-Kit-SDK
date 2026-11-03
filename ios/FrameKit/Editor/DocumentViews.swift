import SwiftUI

/// 문서 보정 조작부.
struct DocumentPanel: View {
    @Bindable var model: EditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(strings("document_hint")).font(.system(size: 13)).foregroundStyle(theme.foregroundMuted).padding(.horizontal, 16)
            chips(ScanStyle.allCases.map(\.rawValue), selected: model.scanStyle.rawValue, title: { strings(ScanStyle(rawValue: $0)!.labelKey) }) {
                model.scanStyle = ScanStyle(rawValue: $0) ?? .color
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    EditorButton(title: strings("document_detect"), systemImage: "viewfinder", kind: .tonal) { model.redetectDocument() }
                    EditorButton(title: strings("document_whole"), systemImage: "rectangle", kind: .tonal) { model.wholeDocument() }
                    if model.pages.indices.contains(model.pageIndex), model.pages[model.pageIndex].original != nil {
                        EditorButton(title: strings("document_restore"), systemImage: "arrow.uturn.backward", kind: .tonal) { model.restoreDocument() }
                    }
                }
                .padding(.horizontal, 16)
            }
            if model.pages.count > 1 {
                EditorButton(title: String.localizedStringWithFormat(strings("document_scan_all"), model.pages.count), systemImage: "doc.on.doc",
                             kind: .tonal) { model.scanAllPages() }
                    .padding(.horizontal, 16)
            }
            if model.documentBusy {
                HStack(spacing: 8) {
                    ProgressView().tint(theme.accent)
                    Text(strings("document_working")).font(.system(size: 14)).foregroundStyle(theme.foreground)
                }
                .padding(.horizontal, 16)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.updatesFrequently)
            }
        }
        .disabled(model.documentBusy)
    }
}

/// 문서 모서리를 끌어 맞추는 캔버스. 원본(펴기 전) 위에 그린다.
struct DocumentCanvas: View {
    let model: EditorModel
    let area: CGRect
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings
    @State private var dragging: Int?

    var body: some View {
        if let image = model.documentSource?.cgImage, let quad = model.documentQuad {
            let rect = fit(CGSize(width: image.width, height: image.height), in: area)
            let points = quad.corners.map { CGPoint(x: rect.minX + $0.x * rect.width, y: rect.minY + $0.y * rect.height) }
            ZStack {
                Image(decorative: image, scale: 1).resizable().interpolation(.high)
                    .frame(width: rect.width, height: rect.height)
                    .position(x: rect.midX, y: rect.midY)
                    .accessibilityHidden(true)
                Path { path in
                    path.addRect(rect)
                    path.addLines(points)
                    path.closeSubpath()
                }
                .fill(Color.black.opacity(0.45), style: FillStyle(eoFill: true))
                .allowsHitTesting(false)
                Path { path in
                    path.addLines(points)
                    path.closeSubpath()
                }
                .stroke(theme.accent, lineWidth: 2)
                .allowsHitTesting(false)
                ForEach(0..<4, id: \.self) { index in
                    Circle()
                        .fill(dragging == index ? theme.accent : Color.white)
                        .overlay(Circle().stroke(theme.accent, lineWidth: 3))
                        .frame(width: 22, height: 22)
                        .frame(width: 48, height: 48)
                        .contentShape(Rectangle())
                        .position(points[index])
                        .gesture(
                            DragGesture(minimumDistance: 0)
                                .onChanged { g in
                                    dragging = index
                                    model.moveDocumentCorner(index, to: NPoint(x: (g.location.x - rect.minX) / rect.width,
                                                                             y: (g.location.y - rect.minY) / rect.height))
                                }
                                .onEnded { _ in dragging = nil }
                        )
                        .accessibilityLabel(strings("crop_area"))
                        .accessibilityValue("\(index + 1)")
                }
            }
        }
    }
}

/// '문서 감지됨 · 스캔' 알림.
struct DocumentPill: View {
    let model: EditorModel
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings

    var body: some View {
        HStack(spacing: 10) {
            Icons.image("document").resizable().frame(width: 18, height: 18).foregroundStyle(theme.foreground)
            Text(strings("document_detected")).font(.system(size: 14, weight: .medium)).foregroundStyle(theme.foreground)
            Button(strings("document_scan")) { model.selectTool(.document) }
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(theme.accent)
                .frame(minHeight: 44)
            Button { model.documentSuggestionDismissed = true } label: {
                Image(systemName: "xmark").font(.system(size: 12, weight: .bold)).foregroundStyle(theme.foregroundMuted)
                    .frame(width: 32, height: 44)
            }
            .accessibilityLabel(strings("action_close"))
        }
        .padding(.leading, 14)
        .padding(.trailing, 4)
        .background(theme.raised.opacity(0.94), in: Capsule())
        .shadow(color: .black.opacity(0.25), radius: 6, y: 2)
        .padding(.bottom, 12)
    }
}
