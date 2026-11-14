package dev.codelocator.gradle

open class ComposeLocatorExtension {
    var enabled: Boolean = true
    var debugOnly: Boolean = true
    var sourceMapOutput: String = "build/intermediates/composeLocator/source-map.json"
    var asmSourceMapOutput: String = "build/intermediates/composeLocator/asm-source-map.txt"
    var metadataOutput: String = "build/intermediates/composeLocator/metadata/compose-locator-metadata.json"
    var studioIndexOutput: String = "build/intermediates/composeLocator/studio-index/v1"
    var sourceDirectories: List<String> = listOf(
        "src/main/kotlin",
        "src/main/java",
        "src/debug/kotlin",
        "src/debug/java",
    )
    var bytecodeInspectionOutput: String = "build/intermediates/composeLocator/inspection-report.txt"
    var bytecodeInstrumentationOutput: String = "build/intermediates/composeLocator/instrumentation-report.txt"
    var compilerPluginReportOutput: String = "build/intermediates/composeLocator/compiler-plugin-report.txt"
    /**
     * 编译器插件产物坐标，格式 group:artifact:version。
     * 留空（默认）表示自动模式：按工程宿主 Kotlin 版本映射到 locator-compiler-plugin-kotlin-<line> 变体，
     * 支持的坐标形如 io.github.nianzixin:locator-compiler-plugin-kotlin-2.2:0.1.2
     * 与 io.github.nianzixin:locator-compiler-plugin-kotlin-2.4:0.1.2；
     * 显式设置则完全覆盖自动选择（例如锁定某个变体调试时使用）
     */
    var compilerPluginArtifact: String = ""
    var includePackages: List<String> = emptyList()
    var generateRuntimeMarkers: Boolean = true
    var enableBytecodeInspection: Boolean = false
    var enableBytecodeInstrumentation: Boolean = true
}
