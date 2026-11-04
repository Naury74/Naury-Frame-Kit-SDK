import CoreGraphics
import SwiftUI

/// 재생 중 자주 바뀌는 값. 편집기 모델과 따로 두어 이 값을 그리는 뷰만 다시 그린다.
@MainActor
final class PlaybackClock: ObservableObject {
    /// 재생 위치. 단위는 편집기가 정한다(영상·소리 µs, 움직이는 이미지 ms).
    @Published private(set) var position: Int64 = 0
    /// 움직이는 이미지의 지금 프레임.
    @Published private(set) var frame: CGImage?

    /// 같은 값이면 알리지 않는다.
    func set(position: Int64) {
        if position != self.position { self.position = position }
    }

    func set(frame: CGImage?) {
        if frame !== self.frame { self.frame = frame }
    }
}

/// [clock]이 바뀌면 [content]만 다시 그린다.
struct PlaybackReader<Content: View>: View {
    @ObservedObject var clock: PlaybackClock
    @ViewBuilder var content: () -> Content

    var body: some View { content() }
}
