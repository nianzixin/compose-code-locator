package dev.codelocator.studio.device

data class DeviceDescriptor(
    val serial: String,
    val state: String = "device",
    val product: String? = null,
    val model: String? = null,
    val device: String? = null,
    val transportId: String? = null,
    val localPort: Int = localPortForSerial(serial),
    val remotePort: Int = 49391,
) {
    val displayName: String
        get() = buildString {
            append(model?.replace('_', ' ') ?: serial)
            if (model != null) {
                append(" (")
                append(serial)
                append(")")
            }
            product?.let {
                append(" - ")
                append(it)
            }
        }

    override fun toString(): String = displayName

    companion object {
        private const val BASE_LOCAL_PORT = 49391
        private const val PORT_SPAN = 1000

        fun localPortForSerial(serial: String): Int {
            val hash = serial.fold(0) { acc, char -> acc * 31 + char.code }
            return BASE_LOCAL_PORT + (hash and Int.MAX_VALUE) % PORT_SPAN
        }
    }
}
