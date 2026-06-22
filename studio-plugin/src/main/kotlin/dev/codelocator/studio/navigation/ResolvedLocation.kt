package dev.codelocator.studio.navigation

import java.io.File

data class ResolvedLocation(
    val file: File,
    val line: Int,
    val column: Int,
    val symbol: String? = null,
)
