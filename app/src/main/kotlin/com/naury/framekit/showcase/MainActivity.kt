package com.naury.framekit.showcase

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.naury.framekit.FrameKitContract
import com.naury.framekit.android.output.FrameKitOutputs
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.FrameKitResult

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShowcaseTheme {
                val context = LocalContext.current
                val resources = LocalResources.current
                var result by rememberSaveable { mutableStateOf<EditedMedia?>(null) }
                var elapsedMs by rememberSaveable { mutableLongStateOf(0L) }
                var lastMessage by rememberSaveable { mutableStateOf<String?>(null) }
                var launchedAt by rememberSaveable { mutableLongStateOf(0L) }
                var playground by rememberSaveable { mutableStateOf(false) }

                val editor = rememberLauncherForActivityResult(FrameKitContract()) { outcome ->
                    when (outcome) {
                        is FrameKitResult.Success -> {
                            elapsedMs = SystemClock.elapsedRealtime() - launchedAt
                            result = outcome.output
                            lastMessage = null
                        }
                        FrameKitResult.Cancelled -> lastMessage = resources.getString(R.string.result_cancelled)
                        is FrameKitResult.Failure -> lastMessage = resources.getString(
                            R.string.result_failed,
                            outcome.error.code.name,
                            outcome.error.diagnosticId,
                        )
                    }
                }

                val current = result
                if (current == null && playground) {
                    BackHandler { playground = false }
                    PlaygroundScreen(
                        onBack = { playground = false },
                        onLaunch = { request ->
                            launchedAt = SystemClock.elapsedRealtime()
                            editor.launch(request)
                        },
                    )
                } else if (current == null) {
                    HomeScreen(
                        message = lastMessage,
                        onLaunch = { example ->
                            launchedAt = SystemClock.elapsedRealtime()
                            editor.launch(example.request)
                        },
                        onPlayground = { playground = true },
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
                            lastMessage = resources.getString(R.string.result_deleted)
                        },
                    )
                }
            }
        }
    }
}
