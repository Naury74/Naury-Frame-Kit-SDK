package com.naury.framekit.android.output

import android.app.Application
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppFileOutputStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private var now = 1_800_000_000_000L
    private val store = AppFileOutputStore(context, clock = { now })

    @Before
    fun clearFileProviderCache() {
        // FileProvider는 authority별 root 경로를 static으로 캐시한다. Robolectric은 테스트마다 data 폴더를
        // 새로 만들기 때문에 이전 테스트의 경로가 남아 있으면 root를 찾지 못한다.
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let { field ->
            (field.get(null) as MutableMap<*, *>).clear()
        }
    }

    @Test
    fun `published file replaces the partial and is exposed as content uri`() {
        val partial = store.createPartial("jpg").apply { writeBytes(byteArrayOf(9)) }

        val published = store.publish(partial, "jpg")
        val uri = store.uriFor(published)

        assertThat(partial.exists()).isFalse()
        assertThat(published.readBytes()).isEqualTo(byteArrayOf(9))
        assertThat(published.name).endsWith(".jpg")
        assertThat(uri.scheme).isEqualTo("content")
        assertThat(uri.authority).isEqualTo(AppFileOutputStore.authorityFor(context))
    }

    @Test
    fun `two exports in the same second get different names`() {
        val first = store.publish(store.createPartial("png"), "png")
        val second = store.publish(store.createPartial("png"), "png")

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `stale partials are removed and fresh ones are kept`() {
        val stale = store.createPartial("jpg").apply { setLastModified(now - 25L * 60 * 60 * 1000) }
        val fresh = store.createPartial("jpg").apply { setLastModified(now - 60_000L) }
        val result = store.publish(store.createPartial("jpg"), "jpg").apply { setLastModified(now - 30L * 24 * 60 * 60 * 1000) }

        store.deleteStalePartials()

        assertThat(stale.exists()).isFalse()
        assertThat(fresh.exists()).isTrue()
        assertThat(result.exists()).isTrue()
    }

    @Test
    fun `delete removes only files owned by the store`() {
        val published = store.publish(store.createPartial("jpg"), "jpg")
        val uri = store.uriFor(published)

        assertThat(FrameKitOutputs.deleteOutput(context, android.net.Uri.parse("content://other.authority/x.jpg"))).isFalse()
        assertThat(FrameKitOutputs.deleteOutput(context, uri)).isTrue()
        assertThat(published.exists()).isFalse()
    }
}
