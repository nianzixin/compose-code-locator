package dev.codelocator.studio.navigation

import dev.codelocator.studio.model.SourceLocation
import java.io.File

data class NavigationRequest(
    val location: SourceLocation,
    val projectRoot: File? = null,
)
