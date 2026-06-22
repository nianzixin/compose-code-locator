package dev.codelocator.studio.intellij

import dev.codelocator.studio.device.ScreenshotCapture
import dev.codelocator.studio.ui.ScreenshotSession
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

object ScreenshotDecoder {
    fun decode(capture: ScreenshotCapture, session: ScreenshotSession): ScreenshotViewModel {
        val image = ByteArrayInputStream(capture.pngBytes).use { input ->
            checkNotNull(ImageIO.read(input)) {
                "Unable to decode screenshot PNG."
            }
        }
        return ScreenshotViewModel(
            image = image,
            session = session,
            capturedAtMillis = System.currentTimeMillis(),
            fingerprint = capture.pngBytes.contentHashCode(),
        )
    }
}
