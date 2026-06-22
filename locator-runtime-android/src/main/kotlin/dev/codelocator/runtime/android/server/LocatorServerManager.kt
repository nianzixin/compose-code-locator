package dev.codelocator.runtime.android.server

object LocatorServerManager {
    private var tcpServer: LocatorDebugHttpServer? = null
    private var localServer: LocatorLocalSocketServer? = null

    @Synchronized
    fun start(port: Int = 49391, packageName: String? = null) {
        if (localServer == null) {
            val socketName = localSocketName(packageName)
            val next = LocatorLocalSocketServer(socketName)
            if (next.start()) {
                localServer = next
            }
        }
        if (tcpServer == null) {
            val next = LocatorDebugHttpServer(port)
            if (next.start()) {
                tcpServer = next
            }
        }
    }

    @Synchronized
    fun stop() {
        tcpServer?.stop()
        localServer?.stop()
        tcpServer = null
        localServer = null
    }

    fun localSocketName(packageName: String?): String {
        val suffix = packageName
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("""[^A-Za-z0-9_.-]"""), "_")
            ?: "unknown"
        return "codelocator.$suffix"
    }
}
