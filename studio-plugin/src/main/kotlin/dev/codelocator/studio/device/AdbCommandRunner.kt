package dev.codelocator.studio.device

interface AdbCommandRunner {
    fun run(vararg args: String): String

    fun tryRun(vararg args: String): Result<String> {
        return runCatching { run(*args) }
    }
}
