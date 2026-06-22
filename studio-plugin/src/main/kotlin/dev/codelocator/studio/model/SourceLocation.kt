package dev.codelocator.studio.model

data class SourceLocation(
    val relativePath: String,
    val line: Int,
    val column: Int,
    val symbol: String? = null,
) {
    val isNavigable: Boolean
        get() = relativePath.isNotBlank() && line > 0 && column > 0
}
