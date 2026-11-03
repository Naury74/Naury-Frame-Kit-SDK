import Foundation

/// 편집할 사진을 어디서 가져올지.
public enum EditorInput: Sendable, Hashable {
    /// 앱이 읽을 수 있는 파일 URL. 보안 범위 URL이면 SDK가 접근을 열고 닫는다.
    case file(URL)
    /// 시스템 사진 선택기로 한 장 고른다. 닫으면 `cancelled`. 사진 접근 권한은 필요 없다.
    case pick
    /// 시스템 사진 선택기로 여러 장(1...`maxItems`) 고른다. 고른 순서가 편집·저장 순서다.
    case pickMultiple(maxItems: Int)
    /// 여러 파일. 순서가 편집·저장 순서다. 1...`maxImageCount`장.
    case files([URL])
    /// 카메라로 찍는다. 호스트 앱 Info.plist에 `NSCameraUsageDescription`이 있어야 한다.
    /// 카메라가 없으면 `cameraUnavailable`, 권한이 거부됐으면 `permissionDenied`, 닫으면 `cancelled`.
    case capture
    /// 이미지 데이터(JPEG·PNG·HEIC 등). SDK가 임시 파일로 복사하고 편집 뒤 지운다.
    case data(Data)
}
