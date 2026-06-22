package dev.codelocator.runtime.android

import dev.codelocator.runtime.LocatorRuntime
import dev.codelocator.runtime.model.HitTestResult
import dev.codelocator.runtime.model.LocatorNode

object LocatorDebugFacade {
    fun snapshot(): List<LocatorNode> = LocatorRuntime.registry.snapshot()

    fun hitTest(x: Int, y: Int): HitTestResult = LocatorRuntime.registry.hitTest(x, y)

    fun currentSourceId() = LocatorRuntime.currentSourceId()

    internal fun autoCollectStats() = LocatorAutoCollectStats.snapshot()
}
