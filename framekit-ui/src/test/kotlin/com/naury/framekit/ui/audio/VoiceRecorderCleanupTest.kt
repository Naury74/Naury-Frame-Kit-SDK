package com.naury.framekit.ui.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceRecorderCleanupTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `old recordings no session uses are deleted`() {
        val recordings = temp.newFolder("recordings")
        val sessions = temp.newFolder("sessions")
        val now = 100L * DAY
        val old = File(recordings, "voice_old.m4a").apply { writeBytes(ByteArray(1)); setLastModified(now - 8 * DAY) }
        val used = File(recordings, "voice_used.m4a").apply { writeBytes(ByteArray(1)); setLastModified(now - 8 * DAY) }
        val fresh = File(recordings, "voice_new.m4a").apply { writeBytes(ByteArray(1)); setLastModified(now - DAY) }
        val other = File(recordings, "note.txt").apply { writeBytes(ByteArray(1)); setLastModified(now - 8 * DAY) }
        File(sessions, "abc").apply { mkdirs() }.resolve("descriptor.json").writeText("{\"path\":\"${used.absolutePath}\"}")

        VoiceRecorder.deleteStale(recordings, sessions, VoiceRecorder.STALE_RECORDING_AGE_MILLIS, now)

        assertThat(old.exists()).isFalse()
        assertThat(used.exists()).isTrue()
        assertThat(fresh.exists()).isTrue()
        assertThat(other.exists()).isTrue()
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
