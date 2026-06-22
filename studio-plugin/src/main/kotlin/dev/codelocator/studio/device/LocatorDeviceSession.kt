package dev.codelocator.studio.device

import dev.codelocator.studio.client.HealthCheckClient
import dev.codelocator.studio.client.FallbackLocatorTransport
import dev.codelocator.studio.client.HttpLocatorTransport
import dev.codelocator.studio.client.LocatorClient

class LocatorDeviceSession(
    private val device: DeviceDescriptor,
    private val screenshotService: DeviceScreenshotService,
    private val forwarder: AdbPortForwarder,
    private val foregroundPackageResolver: ForegroundPackageResolver? = null,
) {
    private var opened = false
    private var mode = ForwardMode.None
    private val endpoint = "http://127.0.0.1:${device.localPort}"

    fun open(): LocatorClient {
        if (!opened) {
            openForward()
            opened = true
        }
        return LocatorClient(
            FallbackLocatorTransport(
                primary = HttpLocatorTransport(endpoint),
                switchToFallback = ::switchToTcpFallback,
                fallback = HttpLocatorTransport(endpoint),
            ),
        )
    }

    fun capture(): ScreenshotCapture {
        return screenshotService.capture(device)
    }

    fun close() {
        if (opened) {
            forwarder.remove(device)
            opened = false
        }
    }

    private fun openForward() {
        val packageName = foregroundPackageResolver?.resolve(device)
        if (!packageName.isNullOrBlank()) {
            runCatching {
                forwarder.forwardLocalAbstract(device, packageName)
                HealthCheckClient(endpoint).health()
            }.onSuccess {
                mode = ForwardMode.LocalAbstract
                return
            }
            forwarder.remove(device)
        }
        forwarder.forwardTcp(device)
        HealthCheckClient(endpoint).health()
        mode = ForwardMode.Tcp
    }

    private fun switchToTcpFallback(): Boolean {
        if (mode == ForwardMode.Tcp) return false
        return runCatching {
            forwarder.forwardTcp(device)
            HealthCheckClient(endpoint).health()
            mode = ForwardMode.Tcp
        }.isSuccess
    }

    private enum class ForwardMode {
        None,
        LocalAbstract,
        Tcp,
    }
}
