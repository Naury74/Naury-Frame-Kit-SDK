# Supported Formats

## 이미지 입력

| 형식 | 상태 | 비고 |
| --- | --- | --- |
| JPEG | 지원 | EXIF orientation 1..8 |
| PNG | 지원 | 투명도 유지 |
| WEBP (정지) | 지원 | |
| WEBP (애니메이션) | 미지원 | `UNSUPPORTED_FORMAT` |
| GIF | 미지원 | `UNSUPPORTED_FORMAT` |
| HEIF/HEIC | 미지원 | 기기가 디코딩할 수 있어도 v1.0 필수 목록이 아님 |
| RAW | 미지원 | |

- 모든 입력은 sRGB로 변환해 편집합니다. 다른 색공간(Display P3 등)이면 결과에 `COLOR_SPACE_CONVERTED_TO_SRGB` 경고가 붙습니다.
- Ultra HDR gain map은 보존하지 않으며 `HDR_GAIN_MAP_DROPPED` 경고를 반환합니다(API 34+에서 감지).
- 크기가 0이거나 header가 손상된 파일은 `DECODE_FAILED`입니다.
- 형식은 파일 확장자가 아니라 내용으로 판단합니다.

## 이미지 출력

| 형식 | 상태 | 비고 |
| --- | --- | --- |
| JPEG | 지원 | 품질 0..100, 기본 92, 투명 영역은 `jpegBackgroundArgb` |
| PNG | 지원 | 무손실, 투명도 유지 |
| WEBP 손실 | 지원 | 품질 0..100, 투명도 유지 |
| WEBP 무손실 | 지원(API 30+) | 그 미만은 `UNSUPPORTED_FORMAT` |

출력 최대 16MP(기본), 원본보다 확대하지 않음.

## 영상

| 항목 | 지원 |
| --- | --- |
| 입력 | 기기가 디코딩할 수 있는 영상(MP4·3GP·WebM·MKV 등, H.264·HEVC·VP9·AV1). 컨테이너 회전 메타데이터 적용 |
| 출력 | MP4, H.264 영상 + AAC 음성 |
| 해상도 | 짧은 변 기본 1080px 상한(144..2160), 짝수로 맞춤, 확대 없음 |
| 프레임 | 기본 30fps 상한(1..60) |
| HDR | SDR로 톤매핑해 저장(경고 포함) |
| 길이 | 기본 1초 ~ 5분(설정 가능) |
| 소리 | 원본 소리 유지·음소거·볼륨, 배속 시 높이 유지. 소리 없는 영상도 지원 |

디코딩·인코딩 가능 여부는 기기마다 다르므로, 기기가 처리하지 못하면 `UNSUPPORTED_FORMAT`·`DECODE_FAILED`·`ENCODE_FAILED`를 돌려줍니다.

## 검증 환경

| 환경 | 결과 |
| --- | --- |
| Robolectric API 36 / API 27 | EXIF 1..8 디코딩, render, export 자동 테스트 통과 |
| 에뮬레이터 API 36 (arm64 폴더블) | 실제 Picker → 편집 → 저장 흐름 확인 |
| Galaxy Z Fold7 (Android 16) | GL 색 보정 계측 테스트, 편집·저장·복원·108MP 수동 확인 |
| API 26/27 실기기 | 미검증 |
