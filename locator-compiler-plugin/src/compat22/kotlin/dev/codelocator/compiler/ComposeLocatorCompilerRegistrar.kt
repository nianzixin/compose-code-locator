package dev.codelocator.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Kotlin 2.2.x 专用薄壳：对 kotlin-compiler-embeddable 2.2.10 编译，随 `-PkotlinCompilerApi=2.2` 变体发布。
 *
 * 2.2 的 [CompilerPluginRegistrar] 没有 pluginId 抽象属性，这里只能声明普通属性做内部标识，
 * 写成 override 会报 "overrides nothing"；2.4+ 专用壳见 src/compat24 同名类。
 *
 * 注意：IrGenerationExtension.registerExtension 的调用点必须留在各版本薄壳内编译，
 * 因为该糖的擦除签名在 2.2/2.4 之间不兼容（ProjectExtensionDescriptor → ExtensionPointDescriptor）。
 */
@OptIn(ExperimentalCompilerApi::class)
class ComposeLocatorCompilerRegistrar : CompilerPluginRegistrar() {

    /** 插件标识，仅内部使用；2.2 的基类无 pluginId 抽象成员，宿主编译器不会读取它 */
    val pluginId: String = ComposeLocatorCommandLineProcessor.PLUGIN_ID

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
