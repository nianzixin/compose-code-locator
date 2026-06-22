package dev.codelocator.studio.intellij

import dev.codelocator.studio.ui.ScreenshotSession
import java.awt.image.BufferedImage

data class ScreenshotViewModel(
    val image: BufferedImage,
    val session: ScreenshotSession,
    val capturedAtMillis: Long,
    val fingerprint: Int,
)
