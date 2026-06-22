package dev.codelocator.runtime.server

import dev.codelocator.runtime.LocatorRegistry
import dev.codelocator.runtime.model.HitTestResult
import dev.codelocator.runtime.model.LocatorNode

class LocatorServer(
    private val registry: LocatorRegistry,
) {
    fun snapshot(): List<LocatorNode> {
        return registry.snapshot()
    }

    fun hitTest(x: Int, y: Int): HitTestResult {
        return registry.hitTest(x, y)
    }
}
