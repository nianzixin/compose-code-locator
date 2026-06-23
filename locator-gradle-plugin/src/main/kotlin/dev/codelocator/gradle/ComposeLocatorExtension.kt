package dev.codelocator.gradle

open class ComposeLocatorExtension {
    var enabled: Boolean = true
    var debugOnly: Boolean = true
    var sourceMapOutput: String = "build/intermediates/composeLocator/source-map.json"
    var asmSourceMapOutput: String = "build/intermediates/composeLocator/asm-source-map.txt"
    var metadataOutput: String = "build/intermediates/composeLocator/metadata/compose-locator-metadata.json"
    var studioIndexOutput: String = "build/intermediates/composeLocator/studio-index/v1"
    var sourceDirectories: List<String> = listOf("src/main/kotlin", "src/debug/kotlin")
    var bytecodeInspectionOutput: String = "build/intermediates/composeLocator/inspection-report.txt"
    var bytecodeInstrumentationOutput: String = "build/intermediates/composeLocator/instrumentation-report.txt"
    var compilerPluginReportOutput: String = "build/intermediates/composeLocator/compiler-plugin-report.txt"
    var compilerPluginArtifact: String = "io.github.nianzixin:locator-compiler-plugin:0.1.1"
    var includePackages: List<String> = emptyList()
    var generateRuntimeMarkers: Boolean = true
    var enableBytecodeInspection: Boolean = false
    var enableBytecodeInstrumentation: Boolean = true
}
