package dev.codelocator.runtime.model

data class Rect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun contains(x: Int, y: Int): Boolean {
        return x in left..right && y in top..bottom
    }

    fun area(): Int {
        return (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
    }
}
