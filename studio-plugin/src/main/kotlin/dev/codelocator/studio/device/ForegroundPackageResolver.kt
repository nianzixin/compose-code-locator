package dev.codelocator.studio.device

class ForegroundPackageResolver(
    private val runner: AdbCommandRunner,
) {
    fun resolve(device: DeviceDescriptor): String? {
        val output = runner.tryRun(
            "-s",
            device.serial,
            "shell",
            "dumpsys",
            "activity",
            "activities",
        ).getOrNull() ?: return null
        return parse(output)
    }

    companion object {
        fun parse(output: String): String? {
            val priorityLines = output.lineSequence()
                .filter { line ->
                    "topResumedActivity" in line ||
                        "mResumedActivity" in line ||
                        "ResumedActivity" in line ||
                        "Resumed:" in line ||
                        "mFocusedApp" in line ||
                        "mCurrentFocus" in line
                }
                .toList()
            return priorityLines.firstNotNullOfOrNull(::parsePackageFromActivityLine)
                ?: priorityLines.firstNotNullOfOrNull(::parsePackageFromWindowLine)
        }

        private fun parsePackageFromActivityLine(line: String): String? {
            val match = Regex("""\s(?:u\d+\s+)?([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/""")
                .find(line)
                ?: return null
            return match.groupValues[1]
        }

        private fun parsePackageFromWindowLine(line: String): String? {
            val match = Regex("""\s([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/""")
                .find(line)
                ?: return null
            return match.groupValues[1]
        }
    }
}
