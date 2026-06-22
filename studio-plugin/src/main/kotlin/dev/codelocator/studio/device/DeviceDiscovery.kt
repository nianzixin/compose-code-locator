package dev.codelocator.studio.device

class DeviceDiscovery(
    private val runner: AdbCommandRunner,
) {
    fun listDevices(): List<DeviceDescriptor> {
        val output = runner.run("devices", "-l")
        return withUniqueLocalPorts(parseDevices(output))
    }

    companion object {
        fun parseDevices(output: String): List<DeviceDescriptor> {
            return output.lineSequence()
                .drop(1)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("*") }
                .mapNotNull(::parseDeviceLine)
                .toList()
        }

        private fun parseDeviceLine(line: String): DeviceDescriptor? {
            val parts = line.split(Regex("""\s+"""))
            val serial = parts.firstOrNull() ?: return null
            val state = parts.getOrNull(1) ?: return null
            if (state != "device") return null
            val attributes = parts
                .drop(2)
                .mapNotNull { part ->
                    val separator = part.indexOf(':')
                    if (separator <= 0) return@mapNotNull null
                    part.substring(0, separator) to part.substring(separator + 1)
                }
                .toMap()

            return DeviceDescriptor(
                serial = serial,
                state = state,
                product = attributes["product"],
                model = attributes["model"],
                device = attributes["device"],
                transportId = attributes["transport_id"],
            )
        }

        fun withUniqueLocalPorts(devices: List<DeviceDescriptor>): List<DeviceDescriptor> {
            val usedPorts = mutableSetOf<Int>()
            return devices.map { device ->
                var port = device.localPort
                while (!usedPorts.add(port)) {
                    port += 1
                }
                device.copy(localPort = port)
            }
        }
    }
}
