package dev.codelocator.studio.navigation

import dev.codelocator.studio.model.SourceLocation
import java.io.File

class RelativePathResolver : PathResolver {
    override fun resolve(projectRoot: File, location: SourceLocation): File {
        val direct = File(projectRoot, location.relativePath)
        if (direct.isFile) return direct

        return projectRoot.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory }
            .map { File(it, location.relativePath) }
            .firstOrNull { it.isFile }
            ?: direct
    }
}
