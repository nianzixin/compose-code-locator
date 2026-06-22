package dev.codelocator.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
class ComposeLocatorCompilerRegistrar : CompilerPluginRegistrar() {
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
