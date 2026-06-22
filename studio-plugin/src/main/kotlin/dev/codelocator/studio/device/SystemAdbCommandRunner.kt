package dev.codelocator.studio.device

import java.io.BufferedReader
import java.io.InputStreamReader

class SystemAdbCommandRunner(
    private val adbPath: String = AdbPath.resolve(),
) : AdbCommandRunner {
    override fun run(vararg args: String): String {
        val process = ProcessBuilder(listOf(adbPath) + args)
            .redirectErrorStream(true)
            .start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.readText()
        }
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "adb command failed ($exitCode): $adbPath ${args.joinToString(" ")}\n$output"
        }
        return output
    }
}
