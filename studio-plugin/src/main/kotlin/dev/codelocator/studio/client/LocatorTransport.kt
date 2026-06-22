package dev.codelocator.studio.client

interface LocatorTransport {
    fun hitTest(x: Int, y: Int): String
}
