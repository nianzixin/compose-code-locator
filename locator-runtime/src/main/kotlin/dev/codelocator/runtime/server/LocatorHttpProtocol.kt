package dev.codelocator.runtime.server

import dev.codelocator.runtime.model.HitTestResult
import dev.codelocator.runtime.model.LocatorNode

object LocatorHttpProtocol {
    fun encodeSnapshot(nodes: List<LocatorNode>): String {
        return buildString {
            append("{\"nodes\":[")
            nodes.forEachIndexed { index, node ->
                if (index > 0) append(',')
                append(node.toJson())
            }
            append("]}")
        }
    }

    fun encodeHitTest(result: HitTestResult): String {
        return buildString {
            append("{\"x\":")
            append(result.x)
            append(",\"y\":")
            append(result.y)
            append(",\"candidates\":[")
            result.candidates.forEachIndexed { index, node ->
                if (index > 0) append(',')
                append(node.toJson())
            }
            append("]}")
        }
    }

    fun encodeHealth(
        nodeCount: Int,
        sourceCount: Int,
        extraFields: Map<String, Any?> = emptyMap(),
    ): String {
        return buildString {
            append("{\"status\":\"ok\",\"nodeCount\":")
            append(nodeCount)
            append(",\"sourceCount\":")
            append(sourceCount)
            extraFields.forEach { (key, value) ->
                append(',')
                append('"')
                append(escape(key))
                append("\":")
                append(jsonValue(value))
            }
            append('}')
        }
    }

    fun encodeWindows(nodes: List<LocatorNode>, diagnostics: List<String> = emptyList()): String {
        val windows = nodes.groupBy { it.windowId }
            .values
            .sortedWith(
                compareByDescending<List<LocatorNode>> { group -> group.maxOfOrNull { it.windowLayer } ?: 0 }
                    .thenBy { group -> group.firstOrNull()?.windowId ?: 0 },
            )
        return buildString {
            append("{\"windows\":[")
            windows.forEachIndexed { index, windowNodes ->
                if (index > 0) append(',')
                append(windowNodes.toWindowJson())
            }
            append("],\"diagnostics\":")
            append(jsonValue(diagnostics))
            append('}')
        }
    }

    private fun LocatorNode.toJson(): String {
        val sourceIdJson = sourceId?.toString() ?: "null"
        return "{\"id\":$id,\"bounds\":[${screenBounds.left},${screenBounds.top},${screenBounds.right},${screenBounds.bottom}],\"zIndex\":$zIndex,\"sourceId\":$sourceIdJson,\"semanticsTag\":${stringOrNull(semanticsTag)},\"text\":${stringOrNull(text)},\"role\":${stringOrNull(role)},\"composableName\":${stringOrNull(composableName)},\"flags\":$flags,\"windowId\":$windowId,\"windowTitle\":${stringOrNull(windowTitle)},\"windowLayer\":$windowLayer}"
    }

    private fun List<LocatorNode>.toWindowJson(): String {
        val windowNodes = this
        val first = firstOrNull()
        val root = firstOrNull { it.flags and 8 != 0 }
        val bounds = root?.screenBounds ?: map { it.screenBounds }.reduceOrNull { acc, rect ->
            dev.codelocator.runtime.model.Rect(
                left = minOf(acc.left, rect.left),
                top = minOf(acc.top, rect.top),
                right = maxOf(acc.right, rect.right),
                bottom = maxOf(acc.bottom, rect.bottom),
            )
        }
        return buildString {
            append("{\"windowId\":")
            append(first?.windowId ?: 0)
            append(",\"windowLayer\":")
            append(first?.windowLayer ?: 0)
            append(",\"windowTitle\":")
            append(stringOrNull(first?.windowTitle))
            append(",\"bounds\":")
            if (bounds == null) {
                append("null")
            } else {
                append("[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]")
            }
            append(",\"nodeCount\":")
            append(size)
            append(",\"sourceNodeCount\":")
            append(windowNodes.count { it.sourceId != null })
            append(",\"semanticsNodeCount\":")
            append(windowNodes.count { it.flags and 1 != 0 })
            append(",\"layoutNodeCount\":")
            append(windowNodes.count { it.flags and 4 != 0 })
            append(",\"hasWindowRoot\":")
            append(root != null)
            append('}')
        }
    }

    private fun stringOrNull(value: String?): String {
        return value?.let { "\"${escape(it)}\"" } ?: "null"
    }

    private fun escape(value: String): String {
        return buildString {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else -> {
                        if (char.code < 0x20) {
                            append("\\u")
                            append(char.code.toString(16).padStart(4, '0'))
                        } else {
                            append(char)
                        }
                    }
                }
            }
        }
    }

    private fun jsonValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is Number -> value.toString()
            is Boolean -> value.toString()
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { jsonValue(it) }
            else -> stringOrNull(value.toString())
        }
    }
}
