package dev.codelocator.runtime.demo

import dev.codelocator.runtime.LocatorRegistry
import dev.codelocator.runtime.LocatorRuntime
import dev.codelocator.runtime.model.LocatorNode
import dev.codelocator.runtime.model.Rect
import dev.codelocator.runtime.server.LocatorHttpProtocol

fun main() {
    verifyHitTestOrdering()
    verifySourceStack()
    verifyJsonEscaping()
    println("Runtime protocol verification passed")
}

private fun verifyHitTestOrdering() {
    val flagSemantics = 1
    val flagLayout = 1 shl 2
    val flagWindowRoot = 1 shl 3
    val registry = LocatorRegistry()
    registry.upsert(
        LocatorNode(
            id = 1,
            screenBounds = Rect(0, 0, 200, 200),
            zIndex = 0f,
            sourceId = 1,
        ),
    )
    registry.upsert(
        LocatorNode(
            id = 2,
            screenBounds = Rect(10, 10, 80, 80),
            zIndex = 0f,
            sourceId = 2,
        ),
    )
    registry.upsert(
        LocatorNode(
            id = 3,
            screenBounds = Rect(20, 20, 90, 90),
            zIndex = 2f,
            sourceId = null,
            text = "Text",
            flags = flagSemantics,
        ),
    )

    val hit = registry.hitTest(30, 30)
    check(hit.candidates.map { it.id } == listOf(2L, 1L, 3L)) {
        "Unexpected hit order: ${hit.candidates.map { it.id }}"
    }

    registry.clear()
    registry.upsert(
        LocatorNode(
            id = 10,
            screenBounds = Rect(0, 0, 1000, 1000),
            zIndex = 1f,
            sourceId = 10,
            text = "Underlay CTA",
            role = "button",
            flags = flagSemantics,
            windowId = 100,
            windowLayer = 1,
        ),
    )
    registry.upsert(
        LocatorNode(
            id = 20,
            screenBounds = Rect(100, 100, 700, 500),
            zIndex = -10_000f,
            composableName = "WindowRoot",
            flags = flagWindowRoot,
            windowId = 200,
            windowLayer = 2,
        ),
    )
    registry.upsert(
        LocatorNode(
            id = 30,
            screenBounds = Rect(150, 150, 320, 220),
            zIndex = 1f,
            sourceId = 30,
            text = "Dialog confirm",
            role = "button",
            flags = flagSemantics,
            windowId = 200,
            windowLayer = 2,
        ),
    )
    registry.upsert(
        LocatorNode(
            id = 40,
            screenBounds = Rect(120, 120, 680, 480),
            zIndex = -1f,
            sourceId = 40,
            composableName = "DialogLayout",
            flags = flagLayout,
            windowId = 200,
            windowLayer = 2,
        ),
    )
    val dialogHit = registry.hitTest(180, 180)
    check(dialogHit.candidates.map { it.id } == listOf(30L, 40L, 20L, 10L)) {
        "Expected top-window real nodes before WindowRoot and underlay, got ${dialogHit.candidates.map { it.id }}"
    }

    registry.clear()
    check(registry.snapshot().isEmpty()) { "Registry clear did not remove nodes" }
}

private fun verifySourceStack() {
    LocatorRuntime.enter(10)
    LocatorRuntime.enter(20)
    check(LocatorRuntime.currentSourceId() == 20L) {
        "Expected top source id 20 but was ${LocatorRuntime.currentSourceId()}"
    }
    LocatorRuntime.exit()
    check(LocatorRuntime.currentSourceId() == 10L) {
        "Expected nested source id 10 but was ${LocatorRuntime.currentSourceId()}"
    }
    LocatorRuntime.exit()
    check(LocatorRuntime.currentSourceId() == null) {
        "Expected empty source stack but was ${LocatorRuntime.currentSourceId()}"
    }
}

private fun verifyJsonEscaping() {
    val json = LocatorHttpProtocol.encodeSnapshot(
        listOf(
            LocatorNode(
                id = 4,
                screenBounds = Rect(1, 2, 3, 4),
                sourceId = 4,
                semanticsTag = "tag\\name",
                text = "Line 1\nLine \"2\"",
                role = "button",
                composableName = "QuoteComposable",
            ),
        ),
    )

    check(json.contains("\"sourceId\":4")) { "Source id was not encoded: $json" }
    check(json.contains("Line 1\\nLine \\\"2\\\"")) { "Newline or quote was not escaped: $json" }
}
