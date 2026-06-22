package dev.codelocator.studio.client

import dev.codelocator.studio.model.HitCandidate

class LocatorClient {
    constructor() : this(InMemoryLocatorTransport())

    constructor(transport: LocatorTransport) {
        this.transport = transport
    }

    private val transport: LocatorTransport

    fun hitTest(x: Int, y: Int): List<HitCandidate> {
        val raw = transport.hitTest(x, y)
        val candidates = LocatorProtocol.parseHitTestResponse(raw).candidates
        val topWindowLayer = candidates.maxOfOrNull { it.windowLayer } ?: return emptyList()
        return candidates.filter { it.windowLayer == topWindowLayer }
    }
}
