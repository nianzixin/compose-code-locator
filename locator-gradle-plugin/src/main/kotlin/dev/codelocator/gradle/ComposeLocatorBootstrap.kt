package dev.codelocator.gradle

import java.io.File
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware

/**
 * Studio 插件 bootstrap 模式的共享常量。
 * init script 把释放目录写入 Gradle 全局 extra，Gradle 插件经 composeLocatorBootstrapDir() 读取（缺失返回 null，即非 bootstrap 模式）
 */
object ComposeLocatorBootstrap {
    /** init script 写入 Gradle 全局 extra 的工程内释放目录属性名 */
    const val BOOTSTRAP_DIR_PROPERTY = "codelocator.bootstrap.dir"

    /** 释放到工程内的 compiler 插件 jar 文件名 */
    const val COMPILER_ARTIFACT_FILE_NAME = "locator-compiler-plugin.jar"

    /** 释放到工程内的 runtime AAR 文件名（不含 -debug 后缀） */
    const val RUNTIME_ARTIFACT_FILE_NAME = "locator-runtime-android.aar"

    /** runtime AAR 文件依赖判重时使用的基础文件名 */
    const val RUNTIME_ARTIFACT_FILE_BASE_NAME = "locator-runtime-android"
}

/** 读取 bootstrap 释放目录；extraProperties 走 Groovy 惯例，直接 get 缺失属性会抛 MissingPropertyException，必须先 has */
fun Project.composeLocatorBootstrapDir(): File? {
    val extra = (gradle as? ExtensionAware)?.extensions?.extraProperties ?: return null
    if (!extra.has(ComposeLocatorBootstrap.BOOTSTRAP_DIR_PROPERTY)) return null
    return extra.get(ComposeLocatorBootstrap.BOOTSTRAP_DIR_PROPERTY) as? File
}
