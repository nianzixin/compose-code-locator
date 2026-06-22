package dev.codelocator.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

@OptIn(ExperimentalCompilerApi::class)
class ComposeLocatorCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption(
            optionName = OPTION_ENABLED,
            valueDescription = "true|false",
            description = "Enable Compose Code Locator IR source identity injection.",
            required = false,
            allowMultipleOccurrences = false,
        ),
        CliOption(
            optionName = OPTION_REPORT_PATH,
            valueDescription = "path",
            description = "Path for the Compose Code Locator compiler plugin report.",
            required = false,
            allowMultipleOccurrences = false,
        ),
        CliOption(
            optionName = OPTION_PROJECT_DIR,
            valueDescription = "path",
            description = "Project directory used to create stable project-relative source IDs.",
            required = false,
            allowMultipleOccurrences = false,
        ),
        CliOption(
            optionName = OPTION_INCLUDE_PACKAGE,
            valueDescription = "package",
            description = "Package prefix to instrument. Can be provided multiple times.",
            required = false,
            allowMultipleOccurrences = true,
        ),
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option.optionName) {
            OPTION_ENABLED -> configuration.put(KEY_ENABLED, value.toBoolean())
            OPTION_REPORT_PATH -> configuration.put(KEY_REPORT_PATH, value)
            OPTION_PROJECT_DIR -> configuration.put(KEY_PROJECT_DIR, value)
            OPTION_INCLUDE_PACKAGE -> configuration.appendList(KEY_INCLUDE_PACKAGES, value)
            else -> error("Unknown Compose Code Locator compiler plugin option: ${option.optionName}")
        }
    }

    companion object {
        const val PLUGIN_ID = "dev.codelocator.compose-locator.compiler"
        const val OPTION_ENABLED = "enabled"
        const val OPTION_REPORT_PATH = "reportPath"
        const val OPTION_PROJECT_DIR = "projectDir"
        const val OPTION_INCLUDE_PACKAGE = "includePackage"

        val KEY_ENABLED = CompilerConfigurationKey<Boolean>("Compose Code Locator enabled")
        val KEY_REPORT_PATH = CompilerConfigurationKey<String>("Compose Code Locator report path")
        val KEY_PROJECT_DIR = CompilerConfigurationKey<String>("Compose Code Locator project dir")
        val KEY_INCLUDE_PACKAGES = CompilerConfigurationKey<List<String>>("Compose Code Locator include packages")
    }
}
