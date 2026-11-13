package dev.codelocator.studio.intellij

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import java.io.File

/**
 * 项目打开监听器：在 Android 工程打开时释放 Compose Locator 的 Gradle 侧 bootstrap 环境
 * （marker、依赖 jar/AAR、全局 init script），实现"装插件即用、build.gradle 零配置"。
 * 安装涉及磁盘 I/O（产物哈希与复制），放到后台线程执行，避免拖慢项目打开
 */
class LocatorProjectOpenListener : ProjectManagerListener {
    override fun projectOpened(project: Project) {
        val basePath = project.basePath ?: return
        // 不用 Application.executeOnPooledThread：新平台已移除该方法，直接起后台线程，避免 SDK 签名漂移
        Thread {
            runCatching {
                ComposeLocatorBootstrapInstaller.install(File(basePath))
            }.onFailure { error ->
                LOG.warn(
                    "Compose Locator bootstrap install failed, Gradle-side auto injection disabled for this project",
                    error,
                )
            }
        }.apply { isDaemon = true; start() }
    }

    private companion object {
        val LOG = Logger.getInstance(LocatorProjectOpenListener::class.java)
    }
}
