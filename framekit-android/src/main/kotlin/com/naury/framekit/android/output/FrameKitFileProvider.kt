package com.naury.framekit.android.output

import androidx.core.content.FileProvider

// 호스트 앱이 자체 FileProvider를 이미 선언해도 manifest merge에서 충돌하지 않도록 별도 클래스를 둔다.
internal class FrameKitFileProvider : FileProvider()
