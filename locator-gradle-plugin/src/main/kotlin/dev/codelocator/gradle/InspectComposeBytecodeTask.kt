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
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode

abstract class InspectComposeBytecodeTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesDirectory: DirectoryProperty

    @get:Input
    abstract val includePackages: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun inspect() {
        val root = classesDirectory.get().asFile
        val filters = includePackages.get()
        val report = buildString {
            appendLine("Compose bytecode inspection report")
            appendLine("Classes root: ${root.absolutePath}")
            appendLine()

            root.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { classFile ->
                    val bytes = classFile.readBytes()
                    val node = ClassNode()
                    ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                    val className = node.name.replace('/', '.')
                    if (filters.isNotEmpty() && filters.none { className.startsWith(it) }) {
                        return@forEach
                    }

                    val composeMethods = node.methods.filter { method ->
                        method.visibleAnnotations?.any { it.desc.contains("Composable") } == true ||
                            method.invisibleAnnotations?.any { it.desc.contains("Composable") } == true ||
                            methodInsnNodes(method).any {
                                it.owner.contains("Composer") || it.name.contains("composable", ignoreCase = true)
                            }
                    }

                    if (composeMethods.isNotEmpty()) {
                        appendLine("Class: $className")
                        composeMethods.forEach { method ->
                            appendLine("  Method: ${method.name}${method.desc}")
                            val callSites = methodInsnNodes(method)
                                .filter { insn ->
                                    insn.owner.contains("Composer") ||
                                        insn.owner.contains("Locator") ||
                                        insn.name.contains("startRestartGroup") ||
                                        insn.name.contains("endRestartGroup")
                                }
                                .take(20)
                                .toList()
                            callSites.forEach { call ->
                                appendLine("    call ${call.owner}.${call.name}${call.desc}")
                            }
                        }
                        appendLine()
                    }
                }
        }

        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(report)
        logger.lifecycle("Wrote Compose bytecode inspection report to ${file.absolutePath}")
    }

    private fun methodInsnNodes(method: org.objectweb.asm.tree.MethodNode): Sequence<MethodInsnNode> {
        return generateSequence(method.instructions.first) { it.next }
            .filterIsInstance<MethodInsnNode>()
    }
}
