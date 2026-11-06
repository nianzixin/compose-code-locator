package dev.codelocator.gradle

import java.io.File
import java.io.Serializable

/** Source scan root resolved at configuration time, so task execution never touches Project */
data class ComposeLocatorScanRoot(
    val projectDir: File,
    val sourceDir: String,
) : Serializable

/** Scan roots grouped by project directory for reuse across scan tasks */
internal fun Iterable<ComposeLocatorScanRoot>.resolvedScanRoots(): Map<File, List<String>> {
    val roots = LinkedHashMap<File, MutableList<String>>()
    forEach { root ->
        roots.getOrPut(root.projectDir) { mutableListOf() }.add(root.sourceDir)
    }
    return roots
}
