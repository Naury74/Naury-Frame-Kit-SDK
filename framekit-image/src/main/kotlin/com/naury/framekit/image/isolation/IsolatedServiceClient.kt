package com.naury.framekit.image.isolation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** [IsolatedServiceClient.call]의 결과. */
public sealed interface IsolatedResult {

    /** 서비스가 결과를 돌려줬다. */
    public data class Success(val data: Bundle) : IsolatedResult

    /** 서비스의 [IsolatedWorkerService.handle]이 예외를 던졌다. 다시 시도할 수 있다. */
    public data object Failed : IsolatedResult

    /** 결과를 보내기 전에 서비스 프로세스가 죽었다. 같은 기기에서 다시 시도해도 대개 또 죽는다. */
    public data object Crashed : IsolatedResult

    /** 서비스에 연결하지 못했다(manifest 누락 등). */
    public data object Unreachable : IsolatedResult
}

/**
 * 다른 프로세스의 [IsolatedWorkerService]에 붙어 요청 하나를 보내고 결과를 기다린다. 요청마다 연결하고 끝나면 끊는다.
 *
 * @param service 호출할 서비스 클래스. 같은 앱 manifest에 등록되어 있어야 한다.
 */
public class IsolatedServiceClient(context: Context, private val service: Class<out IsolatedWorkerService>) {

    private val appContext = context.applicationContext

    /**
     * @param request 서비스에 넘길 값. Binder 제한(약 1MB) 안이어야 한다.
     * @param onProgress 서비스가 보낸 중간 상태. 메인 스레드에서 불린다.
     * @return 결과. 코루틴이 취소되면 연결을 끊고 서비스 쪽 작업은 버린다.
     */
    public suspend fun call(request: Bundle, onProgress: (Bundle) -> Unit = {}): IsolatedResult = suspendCancellableCoroutine { continuation ->
        val finished = AtomicBoolean(false)
        lateinit var connection: ServiceConnection
        fun finish(result: IsolatedResult?) {
            if (!finished.compareAndSet(false, true)) return
            runCatching { appContext.unbindService(connection) }
            if (result != null) continuation.resume(result)
        }
        val replies = Messenger(
            object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    when (msg.what) {
                        IsolatedWorkerService.MSG_PROGRESS -> if (!finished.get()) onProgress(msg.data)
                        IsolatedWorkerService.MSG_RESULT -> {
                            val data = msg.data
                            finish(if (data.getBoolean(IsolatedWorkerService.KEY_WORKER_ERROR)) IsolatedResult.Failed else IsolatedResult.Success(data))
                        }
                        else -> super.handleMessage(msg)
                    }
                }
            },
        )
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try {
                    Messenger(binder).send(
                        Message.obtain(null, IsolatedWorkerService.MSG_REQUEST).apply {
                            data = request
                            replyTo = replies
                        },
                    )
                } catch (_: RemoteException) {
                    finish(IsolatedResult.Crashed)
                }
            }

            // 결과 전에 연결이 끊기면 서비스 프로세스가 죽은 것이다.
            override fun onServiceDisconnected(name: ComponentName) = finish(IsolatedResult.Crashed)

            override fun onBindingDied(name: ComponentName) = finish(IsolatedResult.Crashed)

            override fun onNullBinding(name: ComponentName) = finish(IsolatedResult.Unreachable)
        }
        val bound = runCatching { appContext.bindService(Intent(appContext, service), connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) finish(IsolatedResult.Unreachable)
        continuation.invokeOnCancellation { finish(null) }
    }
}
