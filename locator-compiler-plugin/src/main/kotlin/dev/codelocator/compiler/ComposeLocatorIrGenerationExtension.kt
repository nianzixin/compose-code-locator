@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.codelocator.compiler

import java.io.File
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.util.IrElementConstructorIndicator
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class ComposeLocatorIrGenerationExtension(
    private val includePackages: List<String>,
    private val reportPath: String?,
    projectDir: String?,
) : IrGenerationExtension {
    private val projectRoot = projectDir?.let(::File)?.absoluteFile?.normalize()

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val records = mutableListOf<CallSiteRecord>()
        val locatorFunctions = pluginContext.referenceFunctions(
            CallableId(
                FqName("dev.codelocator.runtime.android"),
                Name.identifier("locatorSource"),
            ),
        )
        // Kotlin 2.2+：receiver 与普通参数统一进 parameters，用 kind 区分
        val locatorSourceExtensionFunction = locatorFunctions
            .firstOrNull { symbol ->
                val owner = symbol.owner
                owner.extensionReceiverParameter()?.type?.isModifierType() == true &&
                    owner.regularParameters().singleOrNull()?.type == pluginContext.irBuiltIns.longType
            }
        val locatorSourceFactoryFunction = locatorFunctions
            .firstOrNull { symbol ->
                val owner = symbol.owner
                owner.extensionReceiverParameter() == null &&
                    owner.regularParameters().singleOrNull()?.type == pluginContext.irBuiltIns.longType &&
                    owner.returnType.isModifierType()
            }

        moduleFragment.files.forEach { file ->
            if (!shouldProcess(file)) return@forEach
            file.transformChildrenVoid(
                object : IrElementTransformerVoid() {
                    override fun visitCall(expression: IrCall): IrExpression {
                        expression.transformChildrenVoid(this)
                        val owner = expression.symbol.owner
                        if (!owner.isComposableCallTarget()) return expression

                        val line = file.fileEntry.getLineNumber(expression.startOffset) + 1
                        val column = file.fileEntry.getColumnNumber(expression.startOffset) + 1
                        val relativePath = file.fileEntry.name.stableRelativePath()
                        val sourceId = stableSourceId("$relativePath:$line:$column:${owner.name.asString()}")
                        val modifierParameter = owner.regularParameters()
                            .firstOrNull { it.type.isModifierType() }
                        val currentModifier = modifierParameter
                            ?.let { expression.arguments.getOrNull(it.indexInParameters) }

                        var injected = false
                        var injectionKind = "none"
                        if (
                            locatorSourceExtensionFunction != null &&
                            modifierParameter != null &&
                            currentModifier != null &&
                            currentModifier.type.isModifierType()
                        ) {
                            val extensionReceiver = locatorSourceExtensionFunction.owner.extensionReceiverParameter()
                                ?: return expression
                            val longParameter = locatorSourceExtensionFunction.owner.regularParameters().single()
                            // Kotlin 2.2+：impl 包顶层工厂函数替代 fromSymbolOwner
                            // Kotlin 2.4：构造器与指示器均为 internal，用官方迁移期压制手段访问
                            @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
                            val sourceCall = IrCallImpl(
                                IrElementConstructorIndicator,
                                expression.startOffset,
                                expression.endOffset,
                                currentModifier.type,
                                expression.origin,
                                locatorSourceExtensionFunction,
                                null,
                            ).apply {
                                // internal 构造器不预分配 arguments，先补齐到 parameters 个数
                                val paramCount = locatorSourceExtensionFunction.owner.parameters.size
                                while (arguments.size < paramCount) arguments.add(null)
                                arguments[extensionReceiver.indexInParameters] = currentModifier
                                arguments[longParameter.indexInParameters] = IrConstImpl.long(
                                    startOffset = expression.startOffset,
                                    endOffset = expression.endOffset,
                                    type = pluginContext.irBuiltIns.longType,
                                    value = sourceId,
                                )
                            }
                            expression.arguments[modifierParameter.indexInParameters] = sourceCall
                            injected = true
                            injectionKind = "modifier"
                        } else if (modifierParameter != null && locatorSourceFactoryFunction != null) {
                            val longParameter = locatorSourceFactoryFunction.owner.regularParameters().single()
                            // Kotlin 2.4：构造器与指示器均为 internal，用官方迁移期压制手段访问
                            @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
                            val sourceCall = IrCallImpl(
                                IrElementConstructorIndicator,
                                expression.startOffset,
                                expression.endOffset,
                                modifierParameter.type,
                                expression.origin,
                                locatorSourceFactoryFunction,
                                null,
                            ).apply {
                                // internal 构造器不预分配 arguments，先补齐到 parameters 个数
                                val paramCount = locatorSourceFactoryFunction.owner.parameters.size
                                while (arguments.size < paramCount) arguments.add(null)
                                arguments[longParameter.indexInParameters] = IrConstImpl.long(
                                    startOffset = expression.startOffset,
                                    endOffset = expression.endOffset,
                                    type = pluginContext.irBuiltIns.longType,
                                    value = sourceId,
                                )
                            }
                            expression.arguments[modifierParameter.indexInParameters] = sourceCall
                            expression.clearDefaultMaskBit(
                                parameter = modifierParameter,
                                pluginContext = pluginContext,
                            )
                            injected = true
                            injectionKind = "modifier-default"
                        }

                        records += CallSiteRecord(
                            sourceId = sourceId,
                            path = relativePath,
                            line = line,
                            column = column,
                            symbol = owner.name.asString(),
                            owner = owner.fqNameForLocator(),
                            injected = injected,
                            injectionKind = injectionKind,
                        )
                        return expression
                    }
                },
            )
        }
        writeReport(records)
    }

    private fun shouldProcess(file: IrFile): Boolean {
        if (includePackages.isEmpty()) return true
        val packageName = file.packageFqName.asString()
        return includePackages.any { packageName.startsWith(it) }
    }

    private fun writeReport(records: List<CallSiteRecord>) {
        val path = reportPath ?: return
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(
            buildString {
                appendLine("Compose Code Locator compiler plugin report")
                appendLine("Instrumentable Compose call sites: ${records.size}")
                appendLine("Injected source identity call sites: ${records.count { it.injected }}")
                appendLine("Modifier marker call sites: ${records.count { it.injectionKind.startsWith("modifier") }}")
                appendLine("No-modifier fallback call sites: ${records.count { it.injectionKind == "none" }}")
                records.forEach { record ->
                    appendLine(
                        "  sourceId=${record.sourceId} injected=${record.injected} kind=${record.injectionKind} " +
                            "${record.path}:${record.line}:${record.column} ${record.owner}.${record.symbol}",
                    )
                }
            },
        )
    }

    private data class CallSiteRecord(
        val sourceId: Long,
        val path: String,
        val line: Int,
        val column: Int,
        val symbol: String,
        val owner: String,
        val injected: Boolean,
        val injectionKind: String,
    )

    private fun stableSourceId(value: String): Long {
        var hash = -0x340d631b8c46753bL
        value.forEach { char ->
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash and Long.MAX_VALUE
    }

    private fun String.stableRelativePath(): String {
        val file = File(this).absoluteFile.normalize()
        val root = projectRoot
        val path = if (root != null) {
            runCatching { root.toPath().relativize(file.toPath()).toString() }
                .getOrDefault(this)
        } else {
            this
        }
        return path.replace(File.separatorChar, '/')
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun IrCall.clearDefaultMaskBit(
        parameter: IrValueParameter,
        pluginContext: IrPluginContext,
    ): Boolean {
        val owner = symbol.owner
        val maskParameter = defaultMaskParameter(
            parameter = parameter,
            pluginContext = pluginContext,
        ) ?: return false
        val current = arguments.getOrNull(maskParameter.indexInParameters) as? IrConst ?: return false
        val currentValue = (current as? IrConstImpl)?.value as? Int ?: return false
        // mask 位序按旧 valueParameters 下标计算（2.4 已移除 indexInOldValueParameters，自行统计）
        val nextValue = currentValue and (1 shl (owner.indexInOldValueParameters(parameter) % 32)).inv()
        if (nextValue == currentValue) return false
        arguments[maskParameter.indexInParameters] = IrConstImpl.int(
            startOffset = current.startOffset,
            endOffset = current.endOffset,
            type = pluginContext.irBuiltIns.intType,
            value = nextValue,
        )
        return true
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun IrCall.defaultMaskParameter(
        parameter: IrValueParameter,
        pluginContext: IrPluginContext,
    ): IrValueParameter? {
        val owner = symbol.owner
        val maskSlot = owner.indexInOldValueParameters(parameter) / 32
        val namedDefaultMasks = owner.parameters
            .filter { candidate ->
                candidate.type == pluginContext.irBuiltIns.intType &&
                    candidate.name.asString().contains("default", ignoreCase = true)
            }
        namedDefaultMasks.getOrNull(maskSlot)?.let { return it }

        if (maskSlot != 0) return null
        return owner.parameters
            .lastOrNull { candidate ->
                candidate.type == pluginContext.irBuiltIns.intType &&
                    arguments.getOrNull(candidate.indexInParameters) is IrConst
            }
    }
}

/** Kotlin 2.2+：receiver 参数从 parameters 中按 kind 查找 */
private fun IrFunction.extensionReceiverParameter(): IrValueParameter? {
    return parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
}

/** 仅普通值参数（旧 valueParameters 语义） */
private fun IrFunction.regularParameters(): List<IrValueParameter> {
    return parameters.filter { it.kind == IrParameterKind.Regular }
}

/** 旧 valueParameters 下标：parameters 中排在它之前的 Regular 参数个数（2.4 已移除官方属性） */
private fun IrFunction.indexInOldValueParameters(parameter: IrValueParameter): Int {
    return parameters.asSequence()
        .take(parameter.indexInParameters)
        .count { it.kind == IrParameterKind.Regular }
}

private fun IrType.isModifierType(): Boolean {
    return (this as? IrSimpleType)
        ?.classFqName
        ?.asString() == "androidx.compose.ui.Modifier"
}

private fun IrFunction.fqNameForLocator(): String {
    return runCatching {
        val file = generateSequence(parent) { (it as? org.jetbrains.kotlin.ir.declarations.IrDeclaration)?.parent }
            .filterIsInstance<IrFile>()
            .firstOrNull()
        val packageName = file?.packageFqName ?: FqName.ROOT
        if (packageName.isRoot) name.asString() else "${packageName.asString()}.${name.asString()}"
    }.getOrDefault(name.asString())
}

private fun IrFunction.isComposableCallTarget(): Boolean {
    return hasComposeAnnotation() || parameters.any { parameter ->
        parameter.type.classFqName?.asString() == "androidx.compose.runtime.Composer"
    }
}

private fun IrFunction.hasComposeAnnotation(): Boolean {
    return annotations.any { annotation ->
        annotation.type.classFqName?.asString() == "androidx.compose.runtime.Composable"
    }
}
