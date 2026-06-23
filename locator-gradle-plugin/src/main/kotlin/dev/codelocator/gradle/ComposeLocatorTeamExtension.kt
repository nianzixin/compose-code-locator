package dev.codelocator.gradle

open class ComposeLocatorTeamExtension {
    var runtimeArtifact: String = "io.github.nianzixin:locator-runtime-android:0.1.1"
    var autoAddDebugRuntime: Boolean = true
    var preferIncludedBuildRuntimeProject: Boolean = true
}
