package dev.codelocator.studio.client

class FallbackLocatorTransport(
    private val primary: LocatorTransport,
    private val switchToFallback: () -> Boolean,
    private val fallback: LocatorTransport,
) : LocatorTransport {
    private var fallbackEnabled = false

    override fun hitTest(x: Int, y: Int): String {
        if (fallbackEnabled) {
            return fallback.hitTest(x, y)
        }
        return runCatching {
            primary.hitTest(x, y)
        }.getOrElse { primaryError ->
            if (!switchToFallback()) {
                throw primaryError
            }
            fallbackEnabled = true
            fallback.hitTest(x, y)
        }
    }
}
