package dev.codelocator.studio.client

import dev.codelocator.studio.model.HitCandidate
import dev.codelocator.studio.model.HitTestResponse
import dev.codelocator.studio.model.Bounds
import dev.codelocator.studio.model.HitCandidateOrdering

object LocatorProtocol {
    fun parseHitTestResponse(raw: String): HitTestResponse {
        val root = JsonParser.parse(raw) as? JsonObject
            ?: return HitTestResponse(x = 0, y = 0, candidates = emptyList())
        val x = root.intOrNull("x") ?: 0
        val y = root.intOrNull("y") ?: 0
        val candidates = extractCandidates(root)
        return HitTestResponse(x = x, y = y, candidates = candidates)
    }

    private fun extractCandidates(root: JsonObject): List<HitCandidate> {
        return root.array("candidates").mapNotNull { value ->
            val candidate = value as? JsonObject ?: return@mapNotNull null
            val id = candidate.longOrNull("id") ?: return@mapNotNull null
            val bounds = parseBounds(candidate.array("bounds"))
            val zIndex = candidate.floatOrNull("zIndex") ?: 0f
            val tag = candidate.stringOrNull("semanticsTag")
            val text = candidate.stringOrNull("text")
            val role = candidate.stringOrNull("role")
            val composableName = candidate.stringOrNull("composableName")
            val flags = candidate.intOrNull("flags") ?: 0
            val windowId = candidate.intOrNull("windowId") ?: 0
            val windowTitle = candidate.stringOrNull("windowTitle")
            val windowLayer = candidate.intOrNull("windowLayer") ?: 0
            val sourceId = candidate.longOrNull("sourceId")
            val label = buildLabel(sourceId, composableName, tag, text)
            HitCandidate(
                id = id,
                sourceId = sourceId,
                location = null,
                label = label,
                bounds = bounds,
                zIndex = zIndex,
                semanticsTag = tag,
                text = text,
                role = role,
                composableName = composableName,
                flags = flags,
                windowId = windowId,
                windowTitle = windowTitle,
                windowLayer = windowLayer,
            )
        }.sortedWith(HitCandidateOrdering.byLocationPriority).toList()
    }

    private fun parseBounds(raw: List<JsonValue>): Bounds {
        val values = raw.mapNotNull { (it as? JsonNumber)?.raw?.toIntOrNull() }
        return Bounds(
            left = values.getOrElse(0) { 0 },
            top = values.getOrElse(1) { 0 },
            right = values.getOrElse(2) { 0 },
            bottom = values.getOrElse(3) { 0 },
        )
    }

    private fun buildLabel(
        sourceId: Long?,
        composableName: String?,
        tag: String?,
        text: String?,
    ): String {
        return when {
            sourceId != null -> "${composableName ?: tag ?: text ?: "source"} #${sourceId.toString(16)}"
            tag != null && text != null -> "$tag ($text)"
            composableName != null -> composableName
            tag != null -> tag
            text != null -> text
            else -> "unknown"
        }
    }

}
