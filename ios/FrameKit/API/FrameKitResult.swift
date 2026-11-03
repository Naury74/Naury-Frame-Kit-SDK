import Foundation

/// 편집기가 돌려주는 결과. 편집기마다 정확히 한 번 전달된다. 경우는 늘지 않는다.
@frozen
public enum FrameKitResult: Sendable {
    /// 저장 성공. [outputs]의 첫 항목이 [output]이다. 사진 여러 장을 따로 저장하면 장마다 하나씩 담긴다.
    case success(output: EditedMedia, outputs: [EditedMedia])
    /// 사용자가 편집기나 사진 선택을 닫았다. 저장 중 취소는 편집기로 돌아가므로 여기에 오지 않는다.
    case cancelled
    /// 열기·저장에 실패했다.
    case failure(EditorError)
}

/// 결과 파일 종류.
public enum MediaType: String, Sendable {
    case image
    /// PDF.
    case document
}

/// 저장하며 원본과 달라진 점.
public enum ExportWarning: String, Sendable {
    /// 글자 인식을 할 수 없어 PDF에 글자 레이어를 넣지 않았다.
    case textRecognitionSkipped
    /// 넓은 색 영역(Display P3 등) 원본을 sRGB로 바꿨다.
    case colorSpaceConvertedToSRGB
    /// HDR 게인 맵을 버리고 SDR로 저장했다.
    case hdrGainMapDropped
}

/// 저장된 결과 파일.
public struct EditedMedia: Sendable, Hashable {
    /// 앱 Application Support 안의 파일 URL. 파일은 호스트 앱 소유이며 SDK가 지우지 않는다.
    public let url: URL
    public let mediaType: MediaType
    /// 인코딩된 픽셀 너비. PDF는 첫 쪽의 pt 너비.
    public let width: Int
    /// 인코딩된 픽셀 높이. PDF는 첫 쪽의 pt 높이.
    public let height: Int
    /// 예: `image/jpeg`.
    public let mimeType: String
    /// 바이트 단위 파일 크기.
    public let fileSize: Int64
    public let warnings: [ExportWarning]
    /// PDF 쪽 수. 사진이면 `nil`.
    public let pageCount: Int?
    /// PDF에서 인식한 글자(최대 10만 자). 인식하지 않았으면 `nil`.
    public let recognizedText: String?
    /// 편집하지 않은 원본을 그대로 돌려준 경우 `true`.
    public let isOriginal: Bool
}
