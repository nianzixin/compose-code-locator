package dev.codelocator.studio.device

data class ScreenshotCapture(
    val pngBytes: ByteArray,
    val deviceWidth: Int,
    val deviceHeight: Int,
)
