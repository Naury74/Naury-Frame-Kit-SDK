package com.naury.framekit.android.session

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.effect.Adjustments
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
class EditorSessionStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private var now = System.currentTimeMillis()
    private val store = EditorSessionStore(context, clock = { now }, background = Executor { it.run() })
    private val source = SourceReference.LocalFile("/data/photo.jpg")
    private val fingerprint = SourceFingerprint("image/jpeg", 4000, 3000, 6)
    private val project = ImageProject(
        id = ProjectId("p"),
        source = SourceId("old"),
        geometry = GeometryEdit(quarterTurns = 1, straightenDegrees = 7.5, flipX = true, crop = RectN(0.1, 0.2, 0.8, 0.9)),
        adjustments = Adjustments(exposure = 0.5, vignette = 0.3),
        filter = FilterSelection("film02", 0.7),
        grainSeed = 42L,
        revision = 3,
    )

    @Test
    fun `committed snapshot round trips with a new source key`() {
        val id = checkNotNull(store.create(source, fingerprint))
        store.saveSnapshot(id, ImageProjectSnapshot.of(project))

        val record = checkNotNull(EditorSessionStore(context).load(id))
        val restored = checkNotNull(record.snapshot).toProject(SourceId("new"))

        assertThat(record.source).isEqualTo(source)
        assertThat(record.fingerprint).isEqualTo(fingerprint)
        assertThat(restored).isEqualTo(project.copy(source = SourceId("new")))
        assertThat(record.exportWasInterrupted).isFalse()
    }

    @Test
    fun `snapshot written before effects existed still loads with neutral effects`() {
        val id = checkNotNull(store.create(source, fingerprint))
        val legacy = """{"schemaVersion":1,"updatedAt":1,"exportInProgress":false,"image":{"projectId":"p","revision":2,""" +
            """"quarterTurns":0,"straightenDegrees":0.0,"flipX":false,"flipY":false,"cropLeft":0.0,"cropTop":0.0,"cropRight":1.0,"cropBottom":1.0}}"""
        File(File(store.directory, id), "project.snapshot").writeText(legacy)

        val restored = checkNotNull(store.load(id)?.snapshot).toProject(SourceId("s"))

        assertThat(restored.adjustments.isIdentity).isTrue()
        assertThat(restored.filter.isIdentity).isTrue()
    }

    @Test
    fun `new session has no snapshot and leaves no temporary files`() {
        val id = checkNotNull(store.create(source, fingerprint))

        assertThat(store.load(id)?.snapshot).isNull()
        assertThat(File(store.directory, id).list()!!.toList()).containsExactly("descriptor.json", "project.snapshot")
    }

    @Test
    fun `export flag survives so an interrupted export can be reported`() {
        val id = checkNotNull(store.create(source, fingerprint))
        store.saveSnapshot(id, ImageProjectSnapshot.of(project), exportInProgress = true)

        assertThat(store.load(id)?.exportWasInterrupted).isTrue()
    }

    @Test
    fun `damaged snapshot is discarded instead of crashing`() {
        val id = checkNotNull(store.create(source, fingerprint))
        File(File(store.directory, id), "project.snapshot").writeText("{ not json")

        assertThat(store.load(id)).isNull()
        assertThat(File(store.directory, id).exists()).isFalse()
    }

    @Test
    fun `unknown schema version is discarded`() {
        val id = checkNotNull(store.create(source, fingerprint))
        val descriptor = File(File(store.directory, id), "descriptor.json")
        descriptor.writeText(descriptor.readText().replace("\"schemaVersion\":1", "\"schemaVersion\":99"))

        assertThat(store.load(id)).isNull()
    }

    @Test
    fun `stale interrupted sessions are removed while active and recent ones stay`() {
        val stale = checkNotNull(store.create(source, fingerprint))
        val active = checkNotNull(store.create(source, fingerprint))
        val recent = checkNotNull(store.create(source, fingerprint))
        val eightDaysAgo = now - 8L * 24 * 60 * 60 * 1000
        listOf(stale, active).forEach { id -> File(store.directory, id).walk().forEach { it.setLastModified(eightDaysAgo) } }
        store.release(stale)
        store.release(recent)

        store.deleteStale()

        assertThat(File(store.directory, stale).exists()).isFalse()
        assertThat(File(store.directory, active).exists()).isTrue()
        assertThat(File(store.directory, recent).exists()).isTrue()
    }

    @Test
    fun `delete removes the session directory`() {
        val id = checkNotNull(store.create(source, fingerprint))

        store.deleteAsync(id)

        assertThat(File(store.directory, id).exists()).isFalse()
        assertThat(store.load(id)).isNull()
    }

    @Test
    fun `grant persisted by the host is not released when the session ends`() {
        val uri = Uri.parse("content://com.example.host/images/1")
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val id = checkNotNull(store.create(SourceReference.Content(uri.toString()), fingerprint))

        store.delete(id)

        assertThat(context.contentResolver.persistedUriPermissions.map { it.uri }).contains(uri)
    }

    @Test
    fun `ids that are not session ids are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { store.saveSnapshot("../exports", null) }
    }
}
