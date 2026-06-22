package dev.codelocator.studio.ui

data class ScreenshotSession(
    val imageWidth: Int,
    val imageHeight: Int,
    val deviceWidth: Int,
    val deviceHeight: Int,
) {
    fun toDeviceX(imageX: Int): Int {
        return (imageX.toFloat() / imageWidth * deviceWidth).toInt()
    }

    fun toDeviceY(imageY: Int): Int {
        return (imageY.toFloat() / imageHeight * deviceHeight).toInt()
    }
}
