package dev.codelocator.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TryCatchBlockNode

abstract class InstrumentComposeBytecodeTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesDirectory: DirectoryProperty

    @get:Input
    abstract val sourceDirectories: ListProperty<String>

    @get:Input
    abstract val includePackages: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun instrument() {
        val entries = ComposeSourceScanner.scan(
            projectDir = project.projectDir,
            sourceDirectories = sourceDirectories.get(),
            pathRootDir = project.rootProject.projectDir,
        )
        val symbolEntries = entries
            .filter { it.kind == ComposeSourceEntryKind.Composable }
            .associateBy { "${it.ownerClassName.replace('.', '/')}#${it.symbol}" }
        val callSiteEntries = entries
            .filter { it.kind == ComposeSourceEntryKind.ComposableCallSite }
            .associateBy { "${it.ownerClassName.replace('.', '/')}#${it.symbol}#${it.line}" }

        val filters = includePackages.get()
        val root = classesDirectory.get().asFile
        var instrumentedCallSites = 0
        val instrumentedMethods = linkedSetOf<String>()
        val instrumentedSources = mutableListOf<String>()
        val skippedMethods = mutableListOf<String>()

        root.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .forEach { classFile ->
                val classNode = ClassNode()
                ClassReader(classFile.readBytes()).accept(classNode, 0)
                val className = classNode.name.replace('/', '.')
                if (filters.isNotEmpty() && filters.none { className.startsWith(it) }) {
                    return@forEach
                }

                var changed = false
                classNode.methods.forEach { method ->
                    if (hasRuntimeMarker(method)) {
                        skippedMethods += "$className#${method.name}${method.desc} already instrumented"
                        return@forEach
                    }
                    val inserted = instrumentComposableCallSites(method, symbolEntries, callSiteEntries) { entry ->
                        instrumentedSources += "sourceId=${entry.sourceId} kind=${entry.kind.name} ${entry.relativePath}:${entry.line}:${entry.column} ${entry.symbol}"
                    }
                    if (inserted == 0) return@forEach
                    instrumentedCallSites += inserted
                    instrumentedMethods += "$className#${method.name}${method.desc}"
                    changed = true
                }

                if (changed) {
                    val writer = SafeClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
                    classNode.accept(writer)
                    classFile.writeBytes(writer.toByteArray())
                }
            }

        val reportFile = outputFile.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(
            buildString {
                appendLine("Compose bytecode instrumentation report")
                appendLine("Classes root: ${root.absolutePath}")
                appendLine("Instrumented call sites: $instrumentedCallSites")
                appendLine("Exception-safe boundaries: true")
                appendLine("Instrumented methods: ${instrumentedMethods.size}")
                instrumentedMethods.sorted().forEach { appendLine("  instrumented $it") }
                appendLine("Instrumented sources: ${instrumentedSources.size}")
                instrumentedSources.sorted().forEach { appendLine("  $it") }
                appendLine("Skipped methods: ${skippedMethods.size}")
                skippedMethods.sorted().forEach { appendLine("  skipped $it") }
            },
        )
        logger.lifecycle("Wrote Compose bytecode instrumentation report to ${reportFile.absolutePath}")
    }

    private fun hasRuntimeMarker(method: MethodNode): Boolean {
        return generateSequence(method.instructions.first) { it.next }
            .filterIsInstance<MethodInsnNode>()
            .any { it.owner == "dev/codelocator/runtime/LocatorRuntime" && it.name == "enter" }
    }

    private fun instrumentComposableCallSites(
        method: MethodNode,
        symbolEntries: Map<String, ComposeSourceEntry>,
        callSiteEntries: Map<String, ComposeSourceEntry>,
        onInstrumented: (ComposeSourceEntry) -> Unit,
    ): Int {
        if (method.instructions == null || method.instructions.size() == 0) return 0
        var inserted = 0
        val callSites = generateSequence(method.instructions.first) { it.next }
            .filterIsInstance<MethodInsnNode>()
            .filter { it.opcode == Opcodes.INVOKESTATIC }
            .toList()

        callSites.forEach { call ->
            if (!looksLikeComposableDescriptor(call.desc)) return@forEach
            val sourceEntry = callSiteEntries["${call.owner}#${call.name}#${call.lineNumber()}"]
                ?: symbolEntries["${call.owner}#${call.name}"]
                ?: return@forEach
            method.wrapCallWithRuntimeBoundary(call, sourceEntry.sourceId)
            onInstrumented(sourceEntry)
            inserted += 1
        }
        return inserted
    }

    private fun MethodNode.wrapCallWithRuntimeBoundary(call: MethodInsnNode, sourceId: Long) {
        val start = LabelNode()
        val end = LabelNode()
        val handler = LabelNode()
        val after = LabelNode()

        instructions.insertBefore(call, runtimeEnter(sourceId))
        instructions.insertBefore(call, start)
        instructions.insert(
            call,
            InsnList().apply {
                add(end)
                add(runtimeExit())
                add(JumpInsnNode(Opcodes.GOTO, after))
                add(handler)
                add(runtimeExit())
                add(InsnNode(Opcodes.ATHROW))
                add(after)
            },
        )
        tryCatchBlocks.add(TryCatchBlockNode(start, end, handler, null))
    }

    private fun MethodInsnNode.lineNumber(): Int? {
        return generateSequence(previous) { it.previous }
            .filterIsInstance<LineNumberNode>()
            .firstOrNull()
            ?.line
    }

    private fun looksLikeComposableDescriptor(descriptor: String): Boolean {
        return descriptor.contains("Landroidx/compose/runtime/Composer;") && descriptor.endsWith(")V")
    }

    private fun runtimeEnter(sourceId: Long) = InsnList().apply {
        add(LdcInsnNode(sourceId))
        add(
            MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/codelocator/runtime/LocatorRuntime",
                "enter",
                "(J)V",
                false,
            ),
        )
    }

    private fun runtimeExit() = InsnList().apply {
        add(
            MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/codelocator/runtime/LocatorRuntime",
                "exit",
                "()V",
                false,
            ),
        )
    }

    private class SafeClassWriter(flags: Int) : ClassWriter(flags) {
        override fun getCommonSuperClass(type1: String, type2: String): String {
            return "java/lang/Object"
        }
    }
}
