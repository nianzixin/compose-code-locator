package dev.codelocator.studio.navigation

import dev.codelocator.studio.model.SourceLocation
import java.io.File

interface PathResolver {
    fun resolve(projectRoot: File, location: SourceLocation): File
}
