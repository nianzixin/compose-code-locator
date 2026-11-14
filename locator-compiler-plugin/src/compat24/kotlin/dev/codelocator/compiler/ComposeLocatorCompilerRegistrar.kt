package dev.codelocator.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Kotlin 2.4.x 专用薄壳：对 kotlin-compiler-embeddable 2.4.21 编译，随 `-PkotlinCompilerApi=2.4` 变体发布。
 *
 * 2.4 的 [CompilerPluginRegistrar] 新增了 abstract pluginId 属性，宿主编译器通过 ServiceLoader
 * 装载后会调用它的 getter，缺失会 AbstractMethodError，因此这里必须 override；
 * 2.2 专用壳见 src/compat22 同名类。
 *
 * 注意：IrGenerationExtension.registerExtension 的调用点必须留在各版本薄壳内编译，
 * 因为该糖的擦除签名在 2.2/2.4 之间不兼容（ProjectExtensionDescriptor → ExtensionPointDescriptor）。
 */
@OptIn(ExperimentalCompilerApi::class)
class ComposeLocatorCompilerRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = ComposeLocatorCommandLineProcessor.PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        // 总开关关闭时跳过 IR 注入，保持零开销
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
