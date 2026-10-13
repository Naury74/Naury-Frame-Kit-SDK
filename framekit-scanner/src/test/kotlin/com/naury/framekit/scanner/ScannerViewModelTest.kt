package com.naury.framekit.scanner

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.core.document.DocumentQuad
import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScannerViewModelTest {

    private val quad = DocumentQuad(PointN(0.2, 0.2), PointN(0.8, 0.2), PointN(0.8, 0.8), PointN(0.2, 0.8))
    private val made = mutableListOf<DocumentQuad?>()
    private val discarded = mutableListOf<EditedMedia>()

    private fun page(n: Int) = EditedMedia(Uri.parse("content://test/page$n"), MediaType.IMAGE, 100, 140, null, "image/jpeg", 10)

    private fun viewModel(maxPages: Int = 20) = ScannerViewModel(
        DocumentScanRequest(maxPages = maxPages),
        { _, q -> made += q; page(made.size) },
        { discarded += it },
        Dispatchers.Unconfined,
    )

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a steady page triggers one auto capture and becomes a page`() = runBlocking {
        val vm = viewModel()
        vm.onFrame(quad, 0.75f, 0)
        vm.onFrame(quad, 0.75f, 1300)
        assertThat(withTimeoutOrNull(500) { vm.captureRequests.first() }).isNotNull()
        vm.onPhoto(File.createTempFile("scan", ".jpg"))
        assertThat(vm.state.value.pages).hasSize(1)
        assertThat(made.single()).isEqualTo(quad)
        // 같은 쪽을 계속 비추면 다시 찍지 않는다.
        vm.onFrame(quad, 0.75f, 3000)
        assertThat(withTimeoutOrNull(200) { vm.captureRequests.first() }).isNull()

        vm.finish()
        assertThat((vm.result.value as FrameKitResult.Success).outputs).hasSize(1)
    }

    @Test
    fun `turning auto off waits for the shutter and the page limit is enforced`() = runBlocking {
        val vm = viewModel(maxPages = 1)
        vm.toggleAuto()
        vm.onFrame(quad, 0.75f, 0)
        vm.onFrame(quad, 0.75f, 2000)
        assertThat(withTimeoutOrNull(200) { vm.captureRequests.first() }).isNull()
        vm.shutter()
        vm.onPhoto(File.createTempFile("scan", ".jpg"))
        vm.shutter()
        assertThat(vm.state.value.message).isEqualTo(ScannerMessage.LIMIT)
    }

    @Test
    fun `undo and cancel delete the scanned pages`() = runBlocking {
        val vm = viewModel()
        vm.shutter(); vm.onPhoto(File.createTempFile("scan", ".jpg"))
        vm.shutter(); vm.onPhoto(File.createTempFile("scan", ".jpg"))
        vm.removeLast()
        assertThat(vm.state.value.pages).hasSize(1)
        vm.cancel()
        assertThat(discarded).hasSize(2)
        assertThat(vm.result.value).isEqualTo(FrameKitResult.Cancelled)
    }
}
