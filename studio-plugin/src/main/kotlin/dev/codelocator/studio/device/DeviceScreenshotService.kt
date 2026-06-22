package dev.codelocator.studio.device

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.imageio.ImageIO

class DeviceScreenshotService(
    private val adbPath: String = AdbPath.resolve(),
) {
    fun capture(device: DeviceDescriptor): ScreenshotCapture {
        val process = ProcessBuilder(
            adbPath,
            "-s",
            device.serial,
            "exec-out",
            "screencap",
            "-p",
        ).redirectErrorStream(true).start()

        val bytes = process.inputStream.readFully()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "adb screencap failed ($exitCode) for ${device.serial}"
        }

        val image = decodeImage(bytes)

        return ScreenshotCapture(
            pngBytes = bytes,
            deviceWidth = image.width,
            deviceHeight = image.height,
        )
    }

    private fun InputStream.readFully(): ByteArray {
        val output = ByteArrayOutputStream()
        copyTo(output)
        return output.toByteArray()
    }

    private fun decodeImage(bytes: ByteArray): BufferedImage {
        return ByteArrayInputStream(bytes).use { input ->
            checkNotNull(ImageIO.read(input)) {
                "Unable to decode screenshot PNG bytes."
            }
        }
    }
}
