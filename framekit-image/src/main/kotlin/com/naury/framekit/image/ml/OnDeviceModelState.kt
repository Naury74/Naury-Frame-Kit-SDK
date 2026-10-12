package com.naury.framekit.image.ml

/**
 * 기기 안에서 도는 AI 모델(배경 제거 등)의 준비 상태. 모델은 Google Play 서비스가 내려받아 둔다.
 */
public sealed interface OnDeviceModelState {

    /** 바로 쓸 수 있다. */
    public data object Ready : OnDeviceModelState

    /** 쓰려면 먼저 내려받아야 한다. 데이터를 쓰므로 사용자에게 묻고 받는다. */
    public data object NeedsDownload : OnDeviceModelState

    /**
     * 내려받는 중이다.
     *
     * @property bytesDownloaded 지금까지 받은 바이트. 아직 모르면 `0`.
     * @property totalBytes 전체 바이트. 아직 모르면 `0`.
     * @property installing 다 받고 기기에 설치하는 중이면 `true`.
     */
    public data class Downloading(val bytesDownloaded: Long = 0, val totalBytes: Long = 0, val installing: Boolean = false) : OnDeviceModelState {
        /** `0..1` 진행률. 전체 크기를 아직 모르면 `null`. */
        public val fraction: Float? get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }

    /** 내려받지 못했다. 다시 시도할 수 있다. */
    public data class DownloadFailed(val reason: Failure) : OnDeviceModelState

    /** 이 기기에서는 쓸 수 없다. 다시 시도해도 소용없다. */
    public data class Unavailable(val reason: Unsupported) : OnDeviceModelState

    /** 내려받기 실패 이유. */
    public enum class Failure {
        /** 인터넷에 연결되어 있지 않다. */
        NO_NETWORK,

        /** 그 밖의 이유(Play 서비스 오류, 취소 등). */
        OTHER,
    }

    /** 쓸 수 없는 이유. */
    public enum class Unsupported {
        /** Google Play 서비스가 없거나 너무 오래됐다. */
        NO_PLAY_SERVICES,

        /** 모델이 이 기기에서 동작하지 못했다(프로세스 강제 종료 등). */
        DEVICE,
    }
}
