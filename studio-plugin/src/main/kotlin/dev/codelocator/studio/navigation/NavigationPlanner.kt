package dev.codelocator.studio.navigation

import dev.codelocator.studio.model.SourceLocation
import java.io.File

class NavigationPlanner(
    private val projectRoot: File,
    private val pathResolver: PathResolver = RelativePathResolver(),
) {
    fun plan(location: SourceLocation): ResolvedLocation {
        val file = pathResolver.resolve(projectRoot, location)
        return ResolvedLocation(
            file = file,
            line = location.line,
            column = location.column,
            symbol = location.symbol,
        )
    }
}
