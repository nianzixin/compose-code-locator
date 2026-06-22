package dev.codelocator.studio.demo

import dev.codelocator.studio.ui.LocatorToolWindow
import java.io.File

fun main() {
    val toolWindow = LocatorToolWindow(projectRoot = File("."))
    println("Studio tool window scaffold ready: $toolWindow")
}
