import SwiftUI

/// 문서 보정 조작부.
struct CutoutPanel: View {
    @ObservedObject var model: EditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(strings("cutout_description")).scaledFont(13).foregroundStyle(theme.foregroundMuted)
            if model.cutoutBusy {
                HStack(spacing: 8) {
                    ProgressView().tint(theme.accent)
                    Text(strings("cutout_processing")).scaledFont(14).foregroundStyle(theme.foreground)
                }
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.updatesFrequently)
            } else if model.hasCutout {
                HStack(spacing: 8) {
                    EditorButton(title: strings("cutout_remove_again"), systemImage: "wand.and.stars") { model.removeBackground() }
                    EditorButton(title: strings("cutout_restore"), systemImage: "arrow.uturn.backward", kind: .tonal) { model.restoreBackground() }
                }
            } else {
                EditorButton(title: strings("cutout_remove"), systemImage: "wand.and.stars") { model.removeBackground() }
            }
            if let error = model.cutoutError {
                Text(strings(error.code == .unsupportedOperation ? "cutout_device_unsupported" : "cutout_failed"))
                    .scaledFont(13).foregroundStyle(theme.error)
            }
        }
        .padding(.horizontal, 16)
    }
}

/// '글자를 찾았어요 · 보기' 안내와 글자 보기 시트.
