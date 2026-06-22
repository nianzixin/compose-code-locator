package dev.codelocator.studio.client

import java.net.HttpURLConnection
import java.net.URL

class HttpLocatorTransport(
    private val endpoint: String,
    private val connectTimeoutMillis: Int = 500,
    private val readTimeoutMillis: Int = 1_000,
    private val maxAttempts: Int = 4,
) : LocatorTransport {
    override fun hitTest(x: Int, y: Int): String {
        return get("$endpoint/hitTest?x=$x&y=$y")
    }

    private fun get(url: String): String {
        var lastError: Throwable? = null
        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            runCatching { return getOnce(URL(url)) }
                .onFailure { error ->
                    lastError = error
                    if (attempt + 1 < maxAttempts) {
                        Thread.sleep(200)
                    }
                }
        }
        throw IllegalStateException("Unable to reach locator endpoint after $maxAttempts attempts: ${lastError?.message}", lastError)
    }

    private fun getOnce(url: URL): String {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            error("Locator endpoint returned HTTP $status: $body")
        }
        return body
    }
}
