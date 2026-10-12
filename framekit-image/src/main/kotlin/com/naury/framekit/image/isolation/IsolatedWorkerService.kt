package com.naury.framekit.image.isolation

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 기기 안 AI 모델처럼 네이티브 코드가 프로세스를 강제 종료시킬 수 있는 작업을 앱과 다른 프로세스에서 돌리는 서비스의 바탕.
 *
 * FrameKit 선택 모듈(`framekit-segmentation`, `framekit-ocr`)이 manifest에 `android:process`를 지정해 등록한다. 호스트가 직접
 * 상속할 일은 없다. 요청은 [IsolatedServiceClient]가 보내며, 한 번에 하나씩 작업 스레드에서 [handle]로 처리한다.
 * 결과를 보내기 전에 이 프로세스가 죽으면(SIGILL 등) 앱 쪽은 [IsolatedResult.Crashed]를 받는다.
 */
public abstract class IsolatedWorkerService : Service() {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val messenger by lazy {
        Messenger(
            object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    if (msg.what != MSG_REQUEST) return super.handleMessage(msg)
                    val replyTo = msg.replyTo ?: return
                    val request = Bundle(msg.data)
                    worker.execute {
                        val result = try {
                            handle(request) { progress -> replyTo.send(MSG_PROGRESS, progress) }
                        } catch (_: Throwable) {
                            // 잡을 수 있는 오류는 실패로 돌려준다. 잡을 수 없는 네이티브 종료는 앱 쪽이 연결 끊김으로 안다.
                            Bundle().apply { putBoolean(KEY_WORKER_ERROR, true) }
                        }
                        replyTo.send(MSG_RESULT, result)
                    }
                }
            },
        )
    }

    /**
     * 작업 스레드에서 요청 하나를 처리한다.
     *
     * @param request [IsolatedServiceClient.call]에 넘긴 값.
     * @param progress 중간 상태를 앱 쪽에 보낸다. 몇 번이든 부를 수 있다.
     * @return 앱 쪽 [IsolatedResult.Success.data]가 된다. Binder 제한(약 1MB)을 넘는 데이터는 파일로 주고받는다.
     */
    protected abstract fun handle(request: Bundle, progress: (Bundle) -> Unit): Bundle

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun Messenger.send(what: Int, data: Bundle) {
        try {
            send(Message.obtain(null, what).apply { this.data = data })
        } catch (_: RemoteException) {
            // 앱 쪽이 이미 기다리기를 그만뒀다.
        }
    }

    internal companion object {
        const val MSG_REQUEST = 1
        const val MSG_PROGRESS = 2
        const val MSG_RESULT = 3

        /** [handle]이 예외를 던졌을 때 결과에 담기는 키. */
        const val KEY_WORKER_ERROR = "framekit.isolated.error"
    }
}
