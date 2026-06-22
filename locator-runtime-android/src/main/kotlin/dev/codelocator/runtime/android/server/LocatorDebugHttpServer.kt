package dev.codelocator.runtime.android.server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LocatorDebugHttpServer(
    private val port: Int = 49391,
) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val socket = runCatching { ServerSocket(port) }
            .onFailure { running.set(false) }
            .getOrNull()
            ?: return false
        serverSocket = socket
        executor.execute {
            while (running.get()) {
                val socket = runCatching { serverSocket?.accept() }.getOrNull() ?: continue
                executor.execute { handle(socket) }
            }
        }
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        executor.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            LocatorHttpRequestHandler.handle(
                reader = BufferedReader(InputStreamReader(client.getInputStream())),
                writer = OutputStreamWriter(client.getOutputStream()),
            )
        }
    }
}
