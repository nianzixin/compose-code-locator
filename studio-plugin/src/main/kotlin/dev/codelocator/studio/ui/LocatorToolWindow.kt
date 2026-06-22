package dev.codelocator.studio.ui

import dev.codelocator.studio.device.AdbPortForwarder
import dev.codelocator.studio.device.DeviceDescriptor
import dev.codelocator.studio.device.DeviceDiscovery
import dev.codelocator.studio.device.DeviceScreenshotService
import dev.codelocator.studio.device.ForegroundPackageResolver
import dev.codelocator.studio.device.LocatorDeviceSession
import dev.codelocator.studio.device.SystemAdbCommandRunner
import dev.codelocator.studio.navigation.Navigator
import dev.codelocator.studio.service.LocatorStudioService
import dev.codelocator.studio.source.StudioSourceIndex
import java.io.File

class LocatorToolWindow(
    private val projectRoot: File = File("."),
    private val navigator: Navigator? = null,
) {
    private val adbRunner = SystemAdbCommandRunner()
    private val deviceDiscovery = DeviceDiscovery(adbRunner)
    private var selectedDevice: DeviceDescriptor? = null
    private var studioService: LocatorStudioService? = null
    private val sourceIndex by lazy { StudioSourceIndex(projectRoot) }
    private var session = ScreenshotSession(
        imageWidth = 540,
        imageHeight = 1200,
        deviceWidth = 1080,
        deviceHeight = 2400,
    )

    fun connect() {
        val service = serviceForSelectedDevice()
        service.connect()
    }

    fun captureScreenshot(): Pair<dev.codelocator.studio.device.ScreenshotCapture, ScreenshotSession> {
        val capture = requireStudioService().captureScreenshot()
        session = capture.second
        return capture
    }

    fun captureAndLocate(x: Int, y: Int) {
        val result = actionWithService().onImageClick(x, y, session)
        result.candidates.firstOrNull()?.let(actionWithService()::open)
    }

    fun locateCandidates(x: Int, y: Int): LocateResult = actionWithService().onImageClick(x, y, session)

    fun openCandidate(candidate: dev.codelocator.studio.model.HitCandidate) {
        actionWithService().open(candidate)
    }

    fun sourceResolutionHint(candidate: dev.codelocator.studio.model.HitCandidate): String {
        val location = candidate.location
        if (location != null && !location.isNavigable) {
            return "invalid source location (${location.line}:${location.column}); rebuild the Studio index."
        }
        if (location?.isNavigable == true) {
            return "resolved by Studio index"
        }
        if (candidate.sourceId == null) {
            return "runtime did not provide sourceId; rebuild/install a debug app with Compose Locator enabled."
        }
        return sourceIndex.resolutionHint(candidate.sourceId)
    }

    fun disconnect() {
        studioService?.disconnect()
    }

    fun isConnected(): Boolean = studioService?.isConnected() ?: false

    fun listDevices(): List<DeviceDescriptor> = deviceDiscovery.listDevices()

    fun selectDevice(device: DeviceDescriptor) {
        if (selectedDevice == device) return
        if (studioService?.isConnected() == true) {
            studioService?.disconnect()
        }
        selectedDevice = device
        studioService = createStudioService(device)
    }

    private fun serviceForSelectedDevice(): LocatorStudioService {
        studioService?.let { return it }
        val device = selectedDevice
            ?: deviceDiscovery.listDevices().firstOrNull()
            ?: error("No connected adb device/emulator. Connect a device or start an emulator, then refresh devices.")
        selectedDevice = device
        return createStudioService(device).also { studioService = it }
    }

    private fun requireStudioService(): LocatorStudioService {
        return studioService ?: error("Not connected. Select a device and click Connect first.")
    }

    private fun createStudioService(device: DeviceDescriptor): LocatorStudioService {
        return LocatorStudioService(
            LocatorDeviceSession(
                device = device,
                screenshotService = DeviceScreenshotService(),
                forwarder = AdbPortForwarder(adbRunner),
                foregroundPackageResolver = ForegroundPackageResolver(adbRunner),
            ),
            sourceIndex = sourceIndex,
        )
    }

    private fun actionWithService(): CaptureAndLocateAction {
        return CaptureAndLocateAction(
            studioService = requireStudioService(),
            projectRoot = projectRoot,
            navigator = navigator ?: dev.codelocator.studio.navigation.SourceNavigator(),
        )
    }
}
