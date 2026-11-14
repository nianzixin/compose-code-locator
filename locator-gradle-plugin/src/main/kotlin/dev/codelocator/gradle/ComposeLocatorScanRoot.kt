package dev.codelocator.gradle

import java.io.File
import java.io.Serializable

/** 配置期解析好的源码扫描根，避免任务执行期访问 Project */
data class ComposeLocatorScanRoot(
    val projectDir: File,
    val sourceDir: String,
) : Serializable

/** 按工程目录归并扫描根，供源码扫描任务复用 */
internal fun Iterable<ComposeLocatorScanRoot>.resolvedScanRoots(): Map<File, List<String>> {
    val roots = LinkedHashMap<File, MutableList<String>>()
    forEach { root ->
        roots.getOrPut(root.projectDir) { mutableListOf() }.add(root.sourceDir)
    }
    return roots
}
