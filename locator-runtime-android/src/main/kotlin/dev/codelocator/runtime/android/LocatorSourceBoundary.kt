package dev.codelocator.runtime.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

private val LocalLocatorSourceId = compositionLocalOf<Long?> { null }

object LocatorSourceBoundary {
    @Composable
    fun Provide(
        sourceId: Long?,
        content: @Composable () -> Unit,
    ) {
        CompositionLocalProvider(LocalLocatorSourceId provides sourceId) {
            content()
        }
    }

    @Composable
    fun current(): Long? = LocalLocatorSourceId.current
}
