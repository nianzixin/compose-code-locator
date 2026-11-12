package dev.codelocator.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
class ComposeLocatorCompilerRegistrar : CompilerPluginRegistrar() {
    // pluginId 属性在 Kotlin 2.4 才引入，2.2.10 的 CompilerPluginRegistrar 无该成员；
    // 这里保留普通属性仅用于内部标识，避免 2.2.10 编译时报 "overrides nothing"
    val pluginId: String = ComposeLocatorCommandLineProcessor.PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        if (configuration.get(ComposeLocatorCommandLineProcessor.KEY_ENABLED) != true) return
        IrGenerationExtension.registerExtension(
            ComposeLocatorIrGenerationExtension(
                includePackages = configuration.get(ComposeLocatorCommandLineProcessor.KEY_INCLUDE_PACKAGES).orEmpty(),
                reportPath = configuration.get(ComposeLocatorCommandLineProcessor.KEY_REPORT_PATH),
                projectDir = configuration.get(ComposeLocatorCommandLineProcessor.KEY_PROJECT_DIR),
            ),
        )
    }
}
