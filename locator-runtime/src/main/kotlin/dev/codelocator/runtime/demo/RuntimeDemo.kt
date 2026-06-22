package dev.codelocator.runtime.demo

import dev.codelocator.runtime.LocatorRuntime
import dev.codelocator.runtime.model.LocatorNode
import dev.codelocator.runtime.model.Rect
import dev.codelocator.runtime.server.LocatorHttpProtocol
import dev.codelocator.runtime.server.LocatorServer

fun main() {
    val registry = LocatorRuntime.registry
    registry.upsert(
        LocatorNode(
            id = 1L,
            screenBounds = Rect(0, 0, 300, 160),
            zIndex = 0f,
            sourceId = 101L,
            semanticsTag = "profile_header",
            composableName = "ProfileHeader",
        ),
    )
    registry.upsert(
        LocatorNode(
            id = 2L,
            screenBounds = Rect(16, 90, 180, 140),
            zIndex = 1f,
            sourceId = 102L,
            semanticsTag = "follow_button",
            text = "Follow",
            role = "button",
            composableName = "PrimaryButton",
        ),
    )

    val server = LocatorServer(registry)
    println("Snapshot:")
    println(LocatorHttpProtocol.encodeSnapshot(server.snapshot()))
    println()
    println("Hit test at (20,100):")
    println(LocatorHttpProtocol.encodeHitTest(server.hitTest(20, 100)))
}
