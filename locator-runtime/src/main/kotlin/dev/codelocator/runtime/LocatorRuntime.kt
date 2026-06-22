package dev.codelocator.runtime

object LocatorRuntime {
    private val sourceStack = ThreadLocal.withInitial { ArrayDeque<Long>() }
    val registry: LocatorRegistry = LocatorRegistry()

    @JvmStatic
    fun enter(sourceId: Long) {
        sourceStack.get().addLast(sourceId)
    }

    @JvmStatic
    fun exit() {
        val stack = sourceStack.get()
        if (stack.isNotEmpty()) {
            stack.removeLast()
        }
        if (stack.isEmpty()) {
            sourceStack.remove()
        }
    }

    @JvmStatic
    fun currentSourceId(): Long? = sourceStack.get().lastOrNull()
}

fun enterSource(sourceId: Long) {
    LocatorRuntime.enter(sourceId)
}

fun exitSource() {
    LocatorRuntime.exit()
}
