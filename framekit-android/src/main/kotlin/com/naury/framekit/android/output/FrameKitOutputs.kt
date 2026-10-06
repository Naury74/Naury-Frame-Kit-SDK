package com.naury.framekit.android.output

import android.content.Context
import android.net.Uri

/** [OutputTarget.AppFile]로 생성된 파일을 다루는 호스트용 헬퍼. */
public object FrameKitOutputs {

    /**
     * 호스트가 더 이상 필요로 하지 않는 export 파일을 삭제한다.
     *
     * @return 파일이 존재해 삭제했으면 `true`, [uri]가 이 앱에서 FrameKit이 만든 것이 아니거나
     *   이미 삭제되었으면 `false`.
     */
    public fun deleteOutput(context: Context, uri: Uri): Boolean = AppFileOutputStore(context).delete(uri)
}
