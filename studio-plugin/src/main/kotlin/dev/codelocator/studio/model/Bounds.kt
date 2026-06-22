package dev.codelocator.studio.model

data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun area(): Int = (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
}
