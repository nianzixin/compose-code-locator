package dev.codelocator.studio.device

import java.io.File

object AdbPath {
    fun resolve(): String {
        val home = System.getProperty("user.home")
        return listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            home?.let { "$it/Library/Android/sdk" },
            home?.let { "$it/Android/Sdk" },
        ).asSequence()
            .map { File(it, "platform-tools/adb") }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
            ?: "adb"
    }
}
