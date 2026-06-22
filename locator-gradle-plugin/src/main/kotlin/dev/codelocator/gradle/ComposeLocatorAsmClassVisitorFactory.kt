package dev.codelocator.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

abstract class ComposeLocatorAsmClassVisitorFactory :
    AsmClassVisitorFactory<ComposeLocatorAsmClassVisitorFactory.Parameters> {

    interface Parameters : InstrumentationParameters {
        @get:Input
        val includePackages: ListProperty<String>

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        val sourceMapFile: RegularFileProperty
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        val includePackages = parameters.get().includePackages.get()
        return includePackages.isEmpty() || includePackages.any { classData.className.startsWith(it) }
    }

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor {
        return ComposeLocatorClassVisitor(
            api = instrumentationContext.apiVersion.get(),
            next = nextClassVisitor,
            sourceIds = AsmSourceIdCache.get(parameters.get().sourceMapFile.get().asFile),
        )
    }
}

private object AsmSourceIdCache {
    private val cache = ConcurrentHashMap<String, CachedSourceIds>()

    fun get(file: File): Map<String, Long> {
        val absolutePath = file.absolutePath
        val stamp = SourceMapStamp(
            lastModified = file.takeIf(File::isFile)?.lastModified() ?: -1L,
            length = file.takeIf(File::isFile)?.length() ?: -1L,
        )
        val cached = cache[absolutePath]
        if (cached?.stamp == stamp) return cached.sourceIds
        val sourceIds = file.readSourceIds()
        cache[absolutePath] = CachedSourceIds(stamp, sourceIds)
        return sourceIds
    }

    private fun File.readSourceIds(): Map<String, Long> {
        if (!isFile) return emptyMap()
        return useLines { lines ->
            lines.mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("#")) return@mapNotNull null
                val key = trimmed.substringBefore('=', missingDelimiterValue = "")
                val value = trimmed.substringAfter('=', missingDelimiterValue = "").toLongOrNull()
                if (key.isBlank() || value == null) null else key to value
            }.toMap()
        }
    }

    private data class SourceMapStamp(
        val lastModified: Long,
        val length: Long,
    )

    private data class CachedSourceIds(
        val stamp: SourceMapStamp,
        val sourceIds: Map<String, Long>,
    )
}

private class ComposeLocatorClassVisitor(
    api: Int,
    next: ClassVisitor,
    private val sourceIds: Map<String, Long>,
) : ClassVisitor(api, next) {
    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (
            sourceIds.isEmpty() ||
            access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0
        ) {
            return delegate
        }
        return ComposeLocatorMethodVisitor(api, delegate, sourceIds)
    }
}

private class ComposeLocatorMethodVisitor(
    api: Int,
    next: MethodVisitor,
    private val sourceIds: Map<String, Long>,
) : MethodVisitor(api, next) {
    private var currentLine: Int? = null
    private var sawRuntimeBoundary = false

    override fun visitLineNumber(line: Int, start: Label?) {
        currentLine = line
        super.visitLineNumber(line, start)
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean,
    ) {
        if (owner == LOCATOR_RUNTIME_OWNER && name == "enter") {
            sawRuntimeBoundary = true
        }

        val line = currentLine
        val sourceId = if (
            !sawRuntimeBoundary &&
            opcode == Opcodes.INVOKESTATIC &&
            owner != null &&
            name != null &&
            descriptor != null &&
            descriptor.looksLikeComposableDescriptor() &&
            line != null
        ) {
            sourceIds["$owner#$name#$line"]
        } else {
            null
        }
        if (sourceId == null) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            return
        }

        val start = Label()
        val end = Label()
        val handler = Label()
        val after = Label()
        super.visitTryCatchBlock(start, end, handler, null)
        visitRuntimeEnter(sourceId)
        super.visitLabel(start)
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        super.visitLabel(end)
        visitRuntimeExit()
        super.visitJumpInsn(Opcodes.GOTO, after)
        super.visitLabel(handler)
        visitRuntimeExit()
        super.visitInsn(Opcodes.ATHROW)
        super.visitLabel(after)
    }

    private fun String.looksLikeComposableDescriptor(): Boolean {
        return contains("Landroidx/compose/runtime/Composer;") && endsWith(")V")
    }

    private fun visitRuntimeEnter(sourceId: Long) {
        super.visitLdcInsn(sourceId)
        super.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            LOCATOR_RUNTIME_OWNER,
            "enter",
            "(J)V",
            false,
        )
    }

    private fun visitRuntimeExit() {
        super.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            LOCATOR_RUNTIME_OWNER,
            "exit",
            "()V",
            false,
        )
    }
}

private const val LOCATOR_RUNTIME_OWNER = "dev/codelocator/runtime/LocatorRuntime"
