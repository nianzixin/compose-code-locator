package dev.codelocator.compiler

import java.io.File
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
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
        val locatorSourceExtensionFunction = pluginContext
            .referenceFunctions(
                CallableId(
                    FqName("dev.codelocator.runtime.android"),
                    Name.identifier("locatorSource"),
                ),
            )
            .firstOrNull { symbol ->
                val owner = symbol.owner
                owner.extensionReceiverParameter?.type?.isModifierType() == true &&
                    owner.valueParameters.singleOrNull()?.type == pluginContext.irBuiltIns.longType
            }
        val locatorSourceFactoryFunction = pluginContext
            .referenceFunctions(
                CallableId(
                    FqName("dev.codelocator.runtime.android"),
                    Name.identifier("locatorSource"),
                ),
            )
            .firstOrNull { symbol ->
                val owner = symbol.owner
                owner.extensionReceiverParameter == null &&
                    owner.valueParameters.singleOrNull()?.type == pluginContext.irBuiltIns.longType &&
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
                        val modifierParameterIndex = owner.valueParameters.indexOfFirst { it.type.isModifierType() }
                        val currentModifier = modifierParameterIndex
                            .takeIf { it >= 0 }
                            ?.let(expression::getValueArgument)

                        var injected = false
                        var injectionKind = "none"
                        if (
                            locatorSourceExtensionFunction != null &&
                            currentModifier != null &&
                            currentModifier.type.isModifierType()
                        ) {
                            val sourceCall = IrCallImpl.fromSymbolOwner(
                                startOffset = expression.startOffset,
                                endOffset = expression.endOffset,
                                type = currentModifier.type,
                                symbol = locatorSourceExtensionFunction,
                            ).apply {
                                extensionReceiver = currentModifier
                                putValueArgument(
                                    0,
                                    IrConstImpl.long(
                                        startOffset = expression.startOffset,
                                        endOffset = expression.endOffset,
                                        type = pluginContext.irBuiltIns.longType,
                                        value = sourceId,
                                    ),
                                )
                            }
                            expression.putValueArgument(modifierParameterIndex, sourceCall)
                            injected = true
                            injectionKind = "modifier"
                        } else if (modifierParameterIndex >= 0 && locatorSourceFactoryFunction != null) {
                            val sourceCall = IrCallImpl.fromSymbolOwner(
                                startOffset = expression.startOffset,
                                endOffset = expression.endOffset,
                                type = owner.valueParameters[modifierParameterIndex].type,
                                symbol = locatorSourceFactoryFunction,
                            ).apply {
                                putValueArgument(
                                    0,
                                    IrConstImpl.long(
                                        startOffset = expression.startOffset,
                                        endOffset = expression.endOffset,
                                        type = pluginContext.irBuiltIns.longType,
                                        value = sourceId,
                                    ),
                                )
                            }
                            expression.putValueArgument(modifierParameterIndex, sourceCall)
                            expression.clearDefaultMaskBit(
                                parameterIndex = modifierParameterIndex,
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
        parameterIndex: Int,
        pluginContext: IrPluginContext,
    ): Boolean {
        val maskParameterIndex = defaultMaskArgumentIndex(
            parameterIndex = parameterIndex,
            pluginContext = pluginContext,
        ) ?: return false
        val current = getValueArgument(maskParameterIndex) as? IrConst<*> ?: return false
        val currentValue = current.value as? Int ?: return false
        val nextValue = currentValue and (1 shl (parameterIndex % 32)).inv()
        if (nextValue == currentValue) return false
        putValueArgument(
            maskParameterIndex,
            IrConstImpl.int(
                startOffset = current.startOffset,
                endOffset = current.endOffset,
                type = pluginContext.irBuiltIns.intType,
                value = nextValue,
            ),
        )
        return true
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun IrCall.defaultMaskArgumentIndex(
        parameterIndex: Int,
        pluginContext: IrPluginContext,
    ): Int? {
        val maskSlot = parameterIndex / 32
        val namedDefaultMasks = symbol.owner.valueParameters
            .mapIndexed { index, parameter -> index to parameter }
            .filter { (_, parameter) ->
                parameter.type == pluginContext.irBuiltIns.intType &&
                    parameter.name.asString().contains("default", ignoreCase = true)
            }
        namedDefaultMasks.getOrNull(maskSlot)?.first?.let { return it }

        if (maskSlot != 0) return null
        return (0 until valueArgumentsCount)
            .asSequence()
            .map { index -> index to symbol.owner.valueParameters.getOrNull(index) }
            .filter { (index, parameter) ->
                parameter?.type == pluginContext.irBuiltIns.intType &&
                    getValueArgument(index) is IrConst<*>
            }
            .lastOrNull()
            ?.first
    }
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
    return hasComposeAnnotation() || valueParameters.any { parameter ->
        parameter.type.classFqName?.asString() == "androidx.compose.runtime.Composer"
    }
}

private fun IrFunction.hasComposeAnnotation(): Boolean {
    return annotations.any { annotation ->
        annotation.type.classFqName?.asString() == "androidx.compose.runtime.Composable"
    }
}
