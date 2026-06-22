package dev.codelocator.runtime.android

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsModifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.codelocator.runtime.LocatorRuntime
import dev.codelocator.runtime.model.LocatorNode
import dev.codelocator.runtime.model.Rect
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val FLAG_AUTO_SEMANTICS = 1
private const val FLAG_MERGED_SEMANTICS = 1 shl 1
private const val FLAG_AUTO_LAYOUT = 1 shl 2
private const val FLAG_WINDOW_ROOT = 1 shl 3
private const val AUTO_SEMANTICS_NODE_NAMESPACE = 0x4000000000000000L
private const val AUTO_LAYOUT_NODE_NAMESPACE = 0x5000000000000000L
private const val WINDOW_ROOT_NODE_NAMESPACE = 0x6000000000000000L

/**
 * Debug-only Compose semantics collector.
 *
 * This is intentionally root-level opt-in: apps add it once near setContent, rather than adding a
 * locator modifier to every business element.
 */
@Composable
fun LocatorSemanticsAutoCollector(
    enabled: Boolean = true,
    intervalMs: Long = 250L,
    mergingEnabled: Boolean = true,
    collectLayoutTree: Boolean = true,
) {
    val view = LocalView.current
    DisposableEffect(view, mergingEnabled, collectLayoutTree) {
        onDispose {
            LocatorSemanticsNodeSync.clear(layoutOwnerKey(view))
        }
    }

    if (!enabled) return
    LaunchedEffect(view, intervalMs, mergingEnabled, collectLayoutTree) {
        while (true) {
            LocatorViewTreeCollector.collect(view, mergingEnabled, collectLayoutTree)
            delay(intervalMs.coerceAtLeast(16L))
        }
    }
}

internal object LocatorViewTreeCollector {
    fun collect(
        view: View,
        mergingEnabled: Boolean = true,
        collectLayoutTree: Boolean = true,
    ): Set<String> {
        return collect(view, mergingEnabled, collectLayoutTree, WindowInfo.forView(view))
    }

    fun collect(
        view: View,
        mergingEnabled: Boolean = true,
        collectLayoutTree: Boolean = true,
        windowInfo: WindowInfo,
    ): Set<String> {
        val ownerKeys = linkedSetOf<String>()
        val windowKey = windowOwnerKey(windowInfo)
        ownerKeys += windowKey
        LocatorSemanticsNodeSync.sync(windowKey, listOfNotNull(view.toWindowRootNode(windowInfo)))
        if (collectLayoutTree) {
            ownerKeys += LocatorLayoutTreeCollector.collect(view, windowInfo)
        }
        return ownerKeys
    }

    fun clear(
        view: View,
        mergingEnabled: Boolean = true,
        collectLayoutTree: Boolean = true,
    ) {
        LocatorSemanticsNodeSync.clear(windowOwnerKey(WindowInfo.forView(view)))
        if (collectLayoutTree) {
            val layoutHosts = LocatorComposeRootReader.findRootLayoutInfos(view).map { it.first }
            if (layoutHosts.isEmpty()) {
                LocatorSemanticsNodeSync.clear(layoutOwnerKey(view))
            } else {
                layoutHosts.forEach { host ->
                    LocatorSemanticsNodeSync.clear(layoutOwnerKey(host))
                }
            }
        }
    }
}

internal object LocatorWindowRootCollector {
    fun collect(
        fallbackRoot: View?,
        mergingEnabled: Boolean = true,
        collectLayoutTree: Boolean = true,
    ): Set<String> {
        return allRoots(fallbackRoot)
            .flatMapTo(linkedSetOf()) { root ->
                LocatorViewTreeCollector.collect(root.view, mergingEnabled, collectLayoutTree, root.windowInfo)
            }
    }

    private fun allRoots(fallbackRoot: View?): List<WindowRoot> {
        val roots = linkedMapOf<View, WindowRoot>()
        if (fallbackRoot != null) {
            val fallbackView = fallbackRoot.rootView ?: fallbackRoot
            roots[fallbackView] = WindowRoot(fallbackView, WindowInfo.forView(fallbackView))
        }
        WindowRootReader.rootViews().forEach { root ->
            val rootView = root.view.rootView ?: root.view
            roots.merge(rootView, root.copy(view = rootView)) { existing, candidate ->
                when {
                    existing.view.hasVisibleArea() && !candidate.view.hasVisibleArea() -> existing
                    !existing.view.hasVisibleArea() && candidate.view.hasVisibleArea() -> candidate
                    else -> candidate
                }
            }
        }
        val result = roots.values.dedupeEquivalentRoots()
        LocatorAutoCollectStats.record(
            windowRoots = result.size,
            windowRootDetails = result.map { it.describe() },
        )
        return result
    }
}

private fun View.hasVisibleArea(): Boolean {
    return isShown && width > 0 && height > 0
}

internal data class WindowInfo(
    val id: Int,
    val title: String?,
    val layer: Int,
    val offsetX: Int,
    val offsetY: Int,
    val type: Int?,
    val tokenId: Int?,
) {
    fun toScreenRect(boundsInWindow: Rect): Rect {
        if (offsetX == 0 && offsetY == 0) return boundsInWindow
        return Rect(
            left = boundsInWindow.left + offsetX,
            top = boundsInWindow.top + offsetY,
            right = boundsInWindow.right + offsetX,
            bottom = boundsInWindow.bottom + offsetY,
        )
    }

    companion object {
        const val UNKNOWN_ID: Int = 0
        val UNKNOWN: WindowInfo = WindowInfo(
            id = UNKNOWN_ID,
            title = null,
            layer = 0,
            offsetX = 0,
            offsetY = 0,
            type = null,
            tokenId = null,
        )

        fun forView(view: View): WindowInfo {
            return forWindow(view = view.rootView ?: view, layer = 0, params = null)
        }

        fun forWindow(view: View, layer: Int, params: Any?): WindowInfo {
            val root = view.rootView ?: view
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            return WindowInfo(
                id = System.identityHashCode(root),
                title = params.readWindowTitle() ?: root.javaClass.simpleName.takeIf(String::isNotBlank),
                layer = layer,
                offsetX = location[0],
                offsetY = location[1],
                type = params.readWindowType(),
                tokenId = params.readWindowTokenId(),
            )
        }
    }
}

private data class WindowRoot(
    val view: View,
    val windowInfo: WindowInfo,
) {
    private fun boundsKey(): String {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return listOf(
            view.javaClass.name,
            windowInfo.title.orEmpty(),
            windowInfo.type?.toString().orEmpty(),
            windowInfo.tokenId?.toString().orEmpty(),
            location[0],
            location[1],
            location[0] + view.width,
            location[1] + view.height,
        ).joinToString(separator = "|")
    }

    fun describe(): String {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return buildString {
            append("layer=")
            append(windowInfo.layer)
            append(",id=")
            append(windowInfo.id)
            append(",class=")
            append(view.javaClass.name.substringAfterLast('.'))
            append(",title=")
            append(windowInfo.title ?: "n/a")
            append(",type=")
            append(windowInfo.type ?: "n/a")
            append(",token=")
            append(windowInfo.tokenId ?: "n/a")
            append(",bounds=[")
            append(location[0])
            append(',')
            append(location[1])
            append(',')
            append(location[0] + view.width)
            append(',')
            append(location[1] + view.height)
            append(']')
        }
    }

    companion object {
        fun equivalentKey(root: WindowRoot): String = root.boundsKey()
    }
}

private fun Collection<WindowRoot>.dedupeEquivalentRoots(): List<WindowRoot> {
    return this
        .groupBy(WindowRoot::equivalentKey)
        .values
        .map { group ->
            group.sortedWith(
                compareBy<WindowRoot> { it.windowInfo.layer <= 0 }
                    .thenBy { it.windowInfo.layer },
            ).first()
        }
        .sortedBy { it.windowInfo.layer }
}

private object WindowRootReader {
    @Volatile
    private var cachedViewsField: Field? = null
    @Volatile
    private var cachedParamsField: Field? = null

    fun rootViews(): List<WindowRoot> {
        return runCatching {
            val globalClass = Class.forName("android.view.WindowManagerGlobal")
            val instance = globalClass.getMethod("getInstance").invoke(null)
            val viewsField = cachedViewsField ?: globalClass.findField("mViews")?.also {
                cachedViewsField = it
            } ?: return emptyList()
            val paramsField = cachedParamsField ?: globalClass.findField("mParams")?.also {
                cachedParamsField = it
            }
            val params = paramsField?.get(instance).toAnyList()
            viewsField.get(instance).toViewList().mapIndexed { index, view ->
                val root = view.rootView ?: view
                WindowRoot(
                    view = root,
                    windowInfo = WindowInfo.forWindow(
                        view = root,
                        layer = index + 1,
                        params = params.getOrNull(index),
                    ),
                )
            }
        }.getOrElse {
            LocatorAutoCollectStats.record(
                failures = listOf("windowRoots:${it.javaClass.simpleName}:${it.message.orEmpty()}"),
            )
            emptyList()
        }
    }

    private fun Any?.toViewList(): List<View> {
        return when (this) {
            is Array<*> -> filterIsInstance<View>()
            is List<*> -> filterIsInstance<View>()
            is Iterable<*> -> filterIsInstance<View>()
            else -> emptyList()
        }
    }

    private fun Any?.toAnyList(): List<Any?> {
        return when (this) {
            is Array<*> -> toList()
            is List<*> -> this
            is Iterable<*> -> toList()
            else -> emptyList()
        }
    }
}

internal object LocatorSemanticsNodeSync {
    private val activeIds = ConcurrentHashMap<String, Set<Long>>()

    fun sync(ownerKey: String, nodes: List<LocatorNode>) {
        val nextIds = nodes.mapTo(linkedSetOf()) { it.id }
        val previousIds = activeIds.put(ownerKey, nextIds).orEmpty()
        (previousIds - nextIds).forEach(LocatorRuntime.registry::remove)
        nodes.forEach(LocatorRuntime.registry::upsert)
    }

    fun clear(ownerKey: String) {
        activeIds.remove(ownerKey).orEmpty().forEach(LocatorRuntime.registry::remove)
    }
}

private object LocatorLayoutTreeCollector {
    fun collect(view: View, windowInfo: WindowInfo): Set<String> {
        var locatorNodeCount = 0
        var semanticsNodeCount = 0
        val failures = mutableListOf<String>()
        val roots = LocatorComposeRootReader.findRootLayoutInfos(view)
        val ownerKeys = linkedSetOf<String>()
        roots.forEach { (hostView, root) ->
            val nodes = buildList {
                root.collectLayoutNodes(
                    view = hostView,
                    depth = 0,
                    parentId = null,
                    windowInfo = windowInfo,
                    output = this,
                    failures = failures,
                )
            }
            locatorNodeCount += nodes.size
            semanticsNodeCount += nodes.count { it.flags and FLAG_AUTO_SEMANTICS != 0 }
            val key = layoutOwnerKey(hostView)
            ownerKeys += key
            LocatorSemanticsNodeSync.sync(key, nodes)
        }
        LocatorAutoCollectStats.record(
            layoutHosts = roots.size,
            layoutNodes = locatorNodeCount,
            semanticsHosts = roots.size,
            semanticsRawNodes = semanticsNodeCount,
            semanticsNodes = semanticsNodeCount,
            failures = failures,
        )
        return ownerKeys
    }

    private fun LayoutInfo.collectLayoutNodes(
        view: View,
        depth: Int,
        parentId: Long?,
        windowInfo: WindowInfo,
        output: MutableList<LocatorNode>,
        failures: MutableList<String>,
    ) {
        var childParentId = parentId
        runCatching {
            val sourceId = locatorSourceId()
            val semantics = modifierSemanticsConfiguration()
            val nodeId = sourceId?.stableLayoutNodeId(semanticsId)
                ?: autoLayoutNodeId(view, semanticsId, windowInfo.id)
            val rect = toScreenRect(windowInfo) ?: return@runCatching
            childParentId = nodeId
            if (semantics != null) {
                semantics.toLocatorNode(
                    view = view,
                    layoutInfo = this,
                    bounds = rect,
                    sourceId = sourceId,
                    parentId = parentId,
                    windowInfo = windowInfo,
                )?.let(output::add)
            }
            output += LocatorNode(
                id = nodeId,
                screenBounds = rect,
                zIndex = -1f - depth,
                sourceId = sourceId,
                composableName = layoutNodeName(),
                parentId = parentId,
                flags = FLAG_AUTO_LAYOUT,
                windowId = windowInfo.id,
                windowTitle = windowInfo.title,
                windowLayer = windowInfo.layer,
            )
        }.onFailure { error ->
            failures += "layoutNode:${error.javaClass.simpleName}:${error.message.orEmpty()}"
        }
        val children = runCatching {
            LocatorLayoutChildrenReader.childrenOf(this)
        }.getOrElse { error ->
            failures += "layoutChildren:${error.javaClass.simpleName}:${error.message.orEmpty()}"
            emptyList()
        }
        children.forEach { child ->
            child.collectLayoutNodes(
                view = view,
                depth = depth + 1,
                parentId = childParentId,
                windowInfo = windowInfo,
                output = output,
                failures = failures,
            )
        }
    }

    private fun LayoutInfo.toScreenRect(windowInfo: WindowInfo): Rect? {
        if (!isAttached || !isPlaced || isDeactivated || width <= 0 || height <= 0) return null
        val bounds = coordinates.boundsInWindow()
        val rawRect = Rect(
            left = bounds.left.roundToInt(),
            top = bounds.top.roundToInt(),
            right = bounds.right.roundToInt(),
            bottom = bounds.bottom.roundToInt(),
        )
        return windowInfo.toScreenRect(rawRect).takeIf { it.area() > 0 }
    }

    private fun LayoutInfo.layoutNodeName(): String {
        return getModifierInfo()
            .firstNotNullOfOrNull { it.modifier.javaClass.simpleName.takeIf(String::isNotBlank) }
            ?: "LayoutNode"
    }

    private fun LayoutInfo.locatorSourceId(): Long? {
        return getModifierInfo()
            .firstNotNullOfOrNull { info ->
                val modifier = info.modifier
                modifier.readLocatorSourceId() ?: info.readLocatorSourceId()
            }
    }

    private fun LayoutInfo.modifierSemanticsConfiguration(): SemanticsConfiguration? {
        val configs = getModifierInfo().mapNotNull { info ->
            (info.modifier as? SemanticsModifier)?.semanticsConfiguration
                ?: info.modifier.readSemanticsConfiguration()
        }
        if (configs.isEmpty()) return null
        return SemanticsConfiguration().also { merged ->
            configs.forEach { config ->
                merged.collapsePeerCompat(config)
            }
        }
    }

    private fun SemanticsConfiguration.toLocatorNode(
        view: View,
        layoutInfo: LayoutInfo,
        bounds: Rect,
        sourceId: Long?,
        parentId: Long?,
        windowInfo: WindowInfo,
    ): LocatorNode? {
        val text = readText()
        val semanticsTag = getOrNull(SemanticsProperties.TestTag)
        val role = getOrNull(SemanticsProperties.Role)?.toString()?.normalizeRole()
        val hasClickAction = SemanticsActions.OnClick in this
        if (text == null && semanticsTag == null && role == null && !hasClickAction) return null
        val nodeId = sourceId?.stableSemanticsNodeId(
            bounds = bounds,
            semanticsTag = semanticsTag,
            text = text,
            role = role,
        ) ?: autoSemanticsNodeId(view, layoutInfo.semanticsId, mergingEnabled = false, windowId = windowInfo.id)
        return LocatorNode(
            id = nodeId,
            screenBounds = bounds,
            zIndex = if (hasClickAction) 2f else 0f,
            sourceId = sourceId,
            semanticsTag = semanticsTag,
            text = text,
            role = role,
            composableName = role?.replaceFirstCharCompat { it.uppercaseChar() },
            parentId = parentId,
            flags = FLAG_AUTO_SEMANTICS,
            windowId = windowInfo.id,
            windowTitle = windowInfo.title,
            windowLayer = windowInfo.layer,
        )
    }

    private fun SemanticsConfiguration.collapsePeerCompat(peer: SemanticsConfiguration) {
        runCatching {
            javaClass.findMethod("collapsePeer\$ui_release")?.invoke(this, peer)
        }.onFailure {
            peer.forEach { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                set(key as androidx.compose.ui.semantics.SemanticsPropertyKey<Any?>, value)
            }
        }
    }

    private fun Any.readSemanticsConfiguration(): SemanticsConfiguration? {
        return runCatching {
            (javaClass.methods.asSequence() + javaClass.declaredMethods.asSequence())
                .firstOrNull {
                    it.name == "getSemanticsConfiguration" &&
                        it.parameterCount == 0 &&
                        SemanticsConfiguration::class.java.isAssignableFrom(it.returnType)
                }
                ?.also { it.isAccessible = true }
                ?.invoke(this) as? SemanticsConfiguration
        }.getOrNull()
    }

    private fun Any.readLocatorSourceId(): Long? {
        return readLongMember("getSourceId")
            ?: readLongMember("sourceId")
            ?: readLongField("sourceId")
            ?: readNestedModifierSourceId()
    }

    private fun Any.readNestedModifierSourceId(): Long? {
        return runCatching {
            javaClass.methods.firstOrNull { it.name == "getModifier" && it.parameterCount == 0 }
                ?.invoke(this)
                ?.takeIf { it !== this }
                ?.readLocatorSourceId()
        }.getOrNull()
            ?: runCatching {
                javaClass.declaredFields.firstOrNull { it.name == "modifier" }
                    ?.also { it.isAccessible = true }
                    ?.get(this)
                    ?.takeIf { it !== this }
                    ?.readLocatorSourceId()
            }.getOrNull()
    }

    private fun Any.readLongMember(name: String): Long? {
        return runCatching {
            (javaClass.methods.asSequence() + javaClass.declaredMethods.asSequence())
                .firstOrNull { it.name == name && it.parameterCount == 0 }
                ?.also { it.isAccessible = true }
                ?.invoke(this)
                .toLongOrNull()
        }.getOrNull()
    }

    private fun Any.readLongField(name: String): Long? {
        return runCatching {
            javaClass.declaredFields.firstOrNull { it.name == name }
                ?.also { it.isAccessible = true }
                ?.get(this)
                .toLongOrNull()
        }.getOrNull()
    }

    private fun Any?.toLongOrNull(): Long? {
        return when (this) {
            is Number -> toLong()
            is String -> toLongOrNull()
            else -> null
        }?.takeIf { it != 0L }
    }
}

private object LocatorComposeRootReader {
    private val accessors = ConcurrentHashMap<Class<*>, AccessibleObject?>()

    fun findRootLayoutInfos(view: View): List<Pair<View, LayoutInfo>> {
        return buildList {
            findRootLayoutInfos(view, visited = linkedSetOf(), output = this)
        }
    }

    private fun findRootLayoutInfos(
        view: View,
        visited: MutableSet<View>,
        output: MutableList<Pair<View, LayoutInfo>>,
    ) {
        if (!visited.add(view)) return
        view.readRootLayoutInfo()?.let { output += view to it }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findRootLayoutInfos(view.getChildAt(index), visited, output)
            }
        }

        val root = view.rootView
        if (root != view) {
            findRootLayoutInfos(root, visited, output)
        }
    }

    private fun View.readRootLayoutInfo(): LayoutInfo? {
        val accessor = accessors.computeIfAbsent(javaClass) { clazz ->
            clazz.findMethod("getRoot") ?: clazz.findField("root")
        } ?: return null
        return runCatching {
            when (accessor) {
                is Method -> accessor.invoke(this)
                is Field -> accessor.get(this)
                else -> null
            } as? LayoutInfo
        }.getOrNull()
    }
}

private object LocatorLayoutChildrenReader {
    private val accessors = ConcurrentHashMap<Class<*>, AccessibleObject?>()

    fun childrenOf(layoutInfo: LayoutInfo): List<LayoutInfo> {
        val accessor = accessors.computeIfAbsent(layoutInfo.javaClass) { clazz ->
            clazz.findMethod("getChildren")
                ?: clazz.findMethod("getChildren\$ui_release")
                ?: clazz.findMethod("getZSortedChildren")
                ?: clazz.findMethod("get_children\$ui_release")
                ?: clazz.findField("children")
                ?: clazz.findField("_children")
        } ?: return emptyList()
        return runCatching {
            when (accessor) {
                is Method -> accessor.invoke(layoutInfo)
                is Field -> accessor.get(layoutInfo)
                else -> null
            }.toLayoutInfoList()
        }.getOrDefault(emptyList())
    }

    private fun Any?.toLayoutInfoList(): List<LayoutInfo> {
        return when (this) {
            is List<*> -> filterIsInstance<LayoutInfo>()
            is Iterable<*> -> filterIsInstance<LayoutInfo>()
            else -> emptyList()
        }
    }
}

private fun SemanticsConfiguration.readText(): String? {
    getOrNull(SemanticsProperties.Text)
        ?.joinToString(separator = " ") { it.text }
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    getOrNull(SemanticsProperties.EditableText)
        ?.text
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return getOrNull(SemanticsProperties.ContentDescription)
        ?.joinToString(separator = " ")
        ?.takeIf { it.isNotBlank() }
}

private fun Long.stableSemanticsNodeId(
    bounds: Rect,
    semanticsTag: String?,
    text: String?,
    role: String?,
): Long {
    return LocatorIdGenerator.stable(
        sourceId = this,
        tag = null,
        semanticsTag = semanticsTag,
        composableName = "semantics:${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
        text = if (semanticsTag == null) null else text,
        role = role,
        zIndex = 0f,
    )
}

private fun Long.stableLayoutNodeId(
    semanticsNodeId: Int,
): Long {
    return LocatorIdGenerator.stable(
        sourceId = this,
        tag = null,
        semanticsTag = null,
        composableName = "layout:$semanticsNodeId",
        text = null,
        role = null,
        zIndex = -1f,
    )
}

private fun autoSemanticsNodeId(
    view: View,
    semanticsNodeId: Int,
    mergingEnabled: Boolean,
    windowId: Int = 0,
): Long {
    val key = "semantics|window:$windowId|view:${System.identityHashCode(view)}|merged:$mergingEnabled|node:$semanticsNodeId"
    return AUTO_SEMANTICS_NODE_NAMESPACE or (LocatorIdGenerator.stableKey(key) and 0x0FFF_FFFF_FFFF_FFFFL)
}

private fun autoLayoutNodeId(
    view: View,
    semanticsNodeId: Int,
    windowId: Int = 0,
): Long {
    val key = "layout|window:$windowId|view:${System.identityHashCode(view)}|node:$semanticsNodeId"
    return AUTO_LAYOUT_NODE_NAMESPACE or (LocatorIdGenerator.stableKey(key) and 0x0FFF_FFFF_FFFF_FFFFL)
}

private fun View.toWindowRootNode(windowInfo: WindowInfo): LocatorNode? {
    if (width <= 0 || height <= 0 || !isShown) return null
    val location = IntArray(2)
    getLocationOnScreen(location)
    val rect = Rect(
        left = location[0],
        top = location[1],
        right = location[0] + width,
        bottom = location[1] + height,
    )
    if (rect.area() <= 0) return null
    val nodeId = WINDOW_ROOT_NODE_NAMESPACE or
        (LocatorIdGenerator.stableKey("window-root:${windowInfo.id}") and 0x0FFF_FFFF_FFFF_FFFFL)
    return LocatorNode(
        id = nodeId,
        screenBounds = rect,
        zIndex = -10_000f,
        composableName = "WindowRoot",
        flags = FLAG_WINDOW_ROOT,
        windowId = windowInfo.id,
        windowTitle = windowInfo.title,
        windowLayer = windowInfo.layer,
    )
}

private fun layoutOwnerKey(view: View): String {
    return "${System.identityHashCode(view)}:layout"
}

private fun windowOwnerKey(windowInfo: WindowInfo): String {
    return "${windowInfo.id}:window"
}

private fun Any?.readWindowTitle(): String? {
    if (this == null) return null
    val direct = runCatching {
        javaClass.findMethod("getTitle")?.invoke(this)
    }.getOrNull()
    val fromMethod = direct?.toString()?.takeIf { it.isNotBlank() }
    if (fromMethod != null) return fromMethod
    return runCatching {
        javaClass.findField("title")?.get(this)?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

private fun Any?.readWindowType(): Int? {
    if (this == null) return null
    return runCatching {
        (javaClass.findField("type")?.get(this) as? Number)?.toInt()
    }.getOrNull()
}

private fun Any?.readWindowTokenId(): Int? {
    if (this == null) return null
    return runCatching {
        javaClass.findField("token")?.get(this)?.let(System::identityHashCode)
    }.getOrNull()
}

private fun String.normalizeRole(): String = trim().lowercase()

private inline fun String.replaceFirstCharCompat(transform: (Char) -> Char): String {
    if (isEmpty()) return this
    return transform(first()) + drop(1)
}

private fun Class<*>.findMethod(name: String): Method? {
    var current: Class<*>? = this
    while (current != null) {
        current.declaredMethods.firstOrNull { it.name == name }?.let {
            it.isAccessible = true
            return it
        }
        current = current.superclass
    }
    return null
}

private fun Class<*>.findField(name: String): Field? {
    var current: Class<*>? = this
    while (current != null) {
        current.declaredFields.firstOrNull { it.name == name }?.let {
            it.isAccessible = true
            return it
        }
        current = current.superclass
    }
    return null
}
