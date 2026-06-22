package dev.codelocator.studio.service

import dev.codelocator.studio.client.LocatorClient
import dev.codelocator.studio.device.DeviceDescriptor
import dev.codelocator.studio.device.LocatorDeviceSession
import dev.codelocator.studio.device.ScreenshotCapture
import dev.codelocator.studio.model.HitCandidate
import dev.codelocator.studio.model.HitCandidateOrdering
import dev.codelocator.studio.source.StudioSourceIndex
import dev.codelocator.studio.ui.ScreenshotSession

class LocatorStudioService(
    private val deviceSession: LocatorDeviceSession,
    private val sourceIndex: StudioSourceIndex? = null,
) {
    private var client: LocatorClient? = null

    fun connect() {
        client = deviceSession.open()
    }

    fun captureScreenshot(): Pair<ScreenshotCapture, ScreenshotSession> {
        val capture = deviceSession.capture()
        val session = ScreenshotSession(
            imageWidth = capture.deviceWidth,
            imageHeight = capture.deviceHeight,
            deviceWidth = capture.deviceWidth,
            deviceHeight = capture.deviceHeight,
        )
        return capture to session
    }

    fun locate(imageX: Int, imageY: Int, session: ScreenshotSession): List<HitCandidate> {
        val activeClient = requireNotNull(client) { "LocatorStudioService.connect() must be called first." }
        val deviceX = session.toDeviceX(imageX)
        val deviceY = session.toDeviceY(imageY)
        return activeClient.hitTest(deviceX, deviceY)
            .map { candidate -> sourceIndex?.resolve(candidate) ?: candidate }
            .sortedForLocation()
    }

    fun disconnect() {
        deviceSession.close()
        client = null
    }

    fun isConnected(): Boolean = client != null

    private fun List<HitCandidate>.sortedForLocation(): List<HitCandidate> {
        return sortedWith(HitCandidateOrdering.byLocationPriority)
    }
}
