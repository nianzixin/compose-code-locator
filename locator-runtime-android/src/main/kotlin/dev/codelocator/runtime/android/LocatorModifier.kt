package dev.codelocator.runtime.android

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import dev.codelocator.runtime.LocatorRuntime
import dev.codelocator.runtime.model.LocatorNode
import dev.codelocator.runtime.model.Rect
import kotlin.math.roundToInt

fun Modifier.locatorNode(
    sourceId: Long? = null,
    tag: LocatorTag? = null,
    semanticsTag: String? = null,
    text: String? = null,
    role: String? = null,
    composableName: String? = null,
    zIndex: Float = 0f,
): Modifier {
    return composed {
        val view = LocalView.current
        val resolvedSourceId = sourceId ?: LocatorSourceBoundary.current() ?: LocatorRuntime.currentSourceId()
        val stableNodeId = resolvedSourceId?.let {
            LocatorIdGenerator.stable(
                sourceId = it,
                tag = tag?.value,
                semanticsTag = semanticsTag,
                composableName = composableName,
                text = text,
                role = role,
                zIndex = zIndex,
            )
        }
        val nodeId = remember(stableNodeId) { stableNodeId ?: LocatorIdGenerator.next() }
        DisposableEffect(nodeId) {
            onDispose {
                LocatorRuntime.registry.remove(nodeId)
            }
        }
        this.then(
            Modifier.onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                val windowInfo = WindowInfo.forView(view)
                val rect = windowInfo.toScreenRect(
                    Rect(
                        left = bounds.left.roundToInt(),
                        top = bounds.top.roundToInt(),
                        right = bounds.right.roundToInt(),
                        bottom = bounds.bottom.roundToInt(),
                    ),
                )
                LocatorRuntime.registry.upsert(
                    LocatorNode(
                        id = nodeId,
                        screenBounds = rect,
                        zIndex = zIndex,
                        sourceId = resolvedSourceId,
                        semanticsTag = tag?.value ?: semanticsTag,
                        text = text,
                        role = role,
                        composableName = composableName,
                        windowId = windowInfo.id,
                        windowTitle = windowInfo.title,
                        windowLayer = windowInfo.layer,
                    ),
                )
            },
        )
    }
}
