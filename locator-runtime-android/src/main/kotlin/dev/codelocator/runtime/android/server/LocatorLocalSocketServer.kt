package dev.codelocator.runtime.android.server

import android.net.LocalServerSocket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LocatorLocalSocketServer(
    private val socketName: String,
) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private var serverSocket: LocalServerSocket? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val socket = runCatching { LocalServerSocket(socketName) }
            .onFailure { running.set(false) }
            .getOrNull()
            ?: return false
        serverSocket = socket
        executor.execute {
            while (running.get()) {
                val socket = runCatching { serverSocket?.accept() }.getOrNull() ?: continue
                executor.execute {
                    socket.use { client ->
                        LocatorHttpRequestHandler.handle(
                            reader = BufferedReader(InputStreamReader(client.inputStream)),
                            writer = OutputStreamWriter(client.outputStream),
                        )
                    }
                }
            }
        }
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        executor.shutdownNow()
    }
}
