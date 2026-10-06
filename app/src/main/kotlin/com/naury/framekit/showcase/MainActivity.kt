package com.naury.framekit.showcase

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.naury.framekit.android.output.FrameKitOutputs
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.ui.image.contract.ImageEditorContract

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShowcaseTheme {
                val context = LocalContext.current
                var result by rememberSaveable { mutableStateOf<EditedMedia?>(null) }
                var elapsedMs by rememberSaveable { mutableStateOf(0L) }
                var lastMessage by rememberSaveable { mutableStateOf<String?>(null) }
                var launchedAt by rememberSaveable { mutableStateOf(0L) }

                val editor = rememberLauncherForActivityResult(ImageEditorContract()) { outcome ->
                    when (outcome) {
                        is FrameKitResult.Success -> {
                            elapsedMs = SystemClock.elapsedRealtime() - launchedAt
                            result = outcome.output
                            lastMessage = null
                        }
                        FrameKitResult.Cancelled -> lastMessage = context.getString(R.string.result_cancelled)
                        is FrameKitResult.Failure -> lastMessage = context.getString(
                            R.string.result_failed,
                            outcome.error.code.name,
                            outcome.error.diagnosticId,
                        )
                    }
                }

                val current = result
                if (current == null) {
                    HomeScreen(
                        message = lastMessage,
                        onLaunch = { example ->
                            launchedAt = SystemClock.elapsedRealtime()
                            editor.launch(example.request)
                        },
                    )
                } else {
                    BackHandler { result = null }
                    ResultScreen(
                        media = current,
                        elapsedMs = elapsedMs,
                        onBack = { result = null },
                        onDelete = {
                            FrameKitOutputs.deleteOutput(context, current.uri)
                            result = null
                            lastMessage = context.getString(R.string.result_deleted)
                        },
                    )
                }
            }
        }
    }
}
