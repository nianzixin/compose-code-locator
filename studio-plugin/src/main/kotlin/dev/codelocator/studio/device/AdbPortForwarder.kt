package dev.codelocator.studio.device

class AdbPortForwarder(
    private val runner: AdbCommandRunner,
) {
    fun forwardLocalAbstract(device: DeviceDescriptor, packageName: String) {
        removeIfPresent(device)
        runner.run(
            "-s",
            device.serial,
            "forward",
            "tcp:${device.localPort}",
            "localabstract:${localSocketName(packageName)}",
        )
    }

    fun forwardTcp(device: DeviceDescriptor) {
        removeIfPresent(device)
        runner.run(
            "-s",
            device.serial,
            "forward",
            "tcp:${device.localPort}",
            "tcp:${device.remotePort}",
        )
    }

    fun forward(device: DeviceDescriptor) {
        forwardTcp(device)
    }

    fun remove(device: DeviceDescriptor) {
        removeIfPresent(device)
    }

    private fun removeIfPresent(device: DeviceDescriptor) {
        runner.tryRun(
            "-s",
            device.serial,
            "forward",
            "--remove",
            "tcp:${device.localPort}",
        )
    }

    companion object {
        fun localSocketName(packageName: String): String {
            val suffix = packageName
                .takeIf { it.isNotBlank() }
                ?.replace(Regex("""[^A-Za-z0-9_.-]"""), "_")
                ?: "unknown"
            return "codelocator.$suffix"
        }
    }
}
