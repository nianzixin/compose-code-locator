package dev.codelocator.studio.intellij

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import dev.codelocator.studio.navigation.NavigationPlanner
import dev.codelocator.studio.navigation.NavigationRequest
import dev.codelocator.studio.navigation.Navigator
import java.io.File

class IdeNavigator(
    private val project: Project,
) : Navigator {
    override fun open(request: NavigationRequest) {
        if (!request.location.isNavigable) return
        val projectRoot = request.projectRoot ?: File(project.basePath ?: ".")
        val resolved = NavigationPlanner(projectRoot).plan(request.location)
        val virtualFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(resolved.file)
            ?: return
        OpenFileDescriptor(
            project,
            virtualFile,
            (resolved.line - 1).coerceAtLeast(0),
            (resolved.column - 1).coerceAtLeast(0),
        ).navigate(true)
    }
}
