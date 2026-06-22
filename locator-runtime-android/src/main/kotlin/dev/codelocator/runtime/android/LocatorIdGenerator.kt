package dev.codelocator.runtime.android

import java.util.concurrent.atomic.AtomicLong

object LocatorIdGenerator {
    private val nextId = AtomicLong(1L)

    fun next(): Long = nextId.getAndIncrement()

    fun stable(
        sourceId: Long?,
        tag: String?,
        semanticsTag: String?,
        composableName: String?,
        text: String?,
        role: String?,
        zIndex: Float,
        scope: String? = null,
    ): Long {
        val key = buildString {
            append(scope.orEmpty())
            append('|')
            append(sourceId ?: 0L)
            append('|')
            append(tag.orEmpty())
            append('|')
            append(semanticsTag.orEmpty())
            append('|')
            append(composableName.orEmpty())
            append('|')
            append(text.orEmpty())
            append('|')
            append(role.orEmpty())
            append('|')
            append(zIndex)
        }
        return fnv1a64(key)
    }

    fun stableKey(key: String): Long = fnv1a64(key)

    private fun fnv1a64(value: String): Long {
        var hash = -0x340d631b8c46753bL
        value.forEach { char ->
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash and Long.MAX_VALUE
    }
}
