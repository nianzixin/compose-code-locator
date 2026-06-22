package dev.codelocator.runtime.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object LocatorAutoInstaller {
    private var handle: InstallHandle? = null

    @Synchronized
    fun install(
        application: Application,
        intervalMs: Long = 250L,
        mergingEnabled: Boolean = true,
        collectLayoutTree: Boolean = true,
    ): InstallHandle {
        handle?.let { return it }
        val next = InstallHandle(
            application = application,
            intervalMs = intervalMs.coerceAtLeast(16L),
            mergingEnabled = mergingEnabled,
            collectLayoutTree = collectLayoutTree,
            onDispose = {
                synchronized(this) {
                    handle = null
                }
            },
        )
        next.start()
        handle = next
        return next
    }

    fun collectNow() {
        handle?.collectNow(force = true)
    }

    class InstallHandle internal constructor(
        private val application: Application,
        @Suppress("unused") private val intervalMs: Long,
        private val mergingEnabled: Boolean,
        private val collectLayoutTree: Boolean,
        private val onDispose: () -> Unit,
    ) {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val activities = linkedSetOf<Activity>()
        private var disposed = false
        private var lastCollectionUptimeMs = 0L
        private var activeOwnerKeys: Set<String> = emptySet()

        private val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                track(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                track(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                track(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                // Keep paused activities until stopped so short focus transitions do not clear the
                // only visible root while Studio is querying the app.
            }

            override fun onActivityStopped(activity: Activity) {
                untrack(activity)
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                untrack(activity)
            }

            private fun track(activity: Activity) {
                activities += activity
                LocatorAutoCollectStats.record(activeActivities = activities.size)
            }

            private fun untrack(activity: Activity) {
                activities -= activity
                LocatorAutoCollectStats.record(activeActivities = activities.size)
                LocatorViewTreeCollector.clear(
                    view = activity.window.decorView,
                    mergingEnabled = mergingEnabled,
                    collectLayoutTree = collectLayoutTree,
                )
            }
        }

        internal fun start() {
            application.registerActivityLifecycleCallbacks(callbacks)
            mainHandler.post {
                syncExistingActivities()
            }
        }

        fun collectNow(force: Boolean = false) {
            if (disposed) return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                collectActiveActivitiesIfDue(force)
            } else {
                val latch = CountDownLatch(1)
                mainHandler.post {
                    try {
                        collectActiveActivitiesIfDue(force)
                    } finally {
                        latch.countDown()
                    }
                }
                latch.await(1, TimeUnit.SECONDS)
            }
        }

        fun dispose() {
            if (disposed) return
            disposed = true
            application.unregisterActivityLifecycleCallbacks(callbacks)
            activities.toList().forEach { activity ->
                LocatorViewTreeCollector.clear(
                    view = activity.window.decorView,
                    mergingEnabled = mergingEnabled,
                    collectLayoutTree = collectLayoutTree,
                )
            }
            activities.clear()
            onDispose()
        }

        private fun collectActiveActivitiesIfDue(force: Boolean) {
            val now = android.os.SystemClock.uptimeMillis()
            if (!force && now - lastCollectionUptimeMs < MIN_COLLECT_INTERVAL_MS) return
            lastCollectionUptimeMs = now
            syncExistingActivities()
            val activeActivities = activities.toList()
            LocatorAutoCollectStats.record(activeActivities = activeActivities.size)
            val nextOwnerKeys = linkedSetOf<String>()
            if (activeActivities.isEmpty()) {
                nextOwnerKeys += LocatorWindowRootCollector.collect(
                    fallbackRoot = null,
                    mergingEnabled = mergingEnabled,
                    collectLayoutTree = collectLayoutTree,
                )
            } else {
                activeActivities.forEach { activity ->
                    nextOwnerKeys += LocatorWindowRootCollector.collect(
                        fallbackRoot = activity.window.decorView,
                        mergingEnabled = mergingEnabled,
                        collectLayoutTree = collectLayoutTree,
                    )
                }
            }
            (activeOwnerKeys - nextOwnerKeys).forEach(LocatorSemanticsNodeSync::clear)
            activeOwnerKeys = nextOwnerKeys
        }

        private fun syncExistingActivities() {
            activities.removeAll { activity -> activity.isFinishing || activity.isDestroyed }
            ActivityThreadActivityReader.currentActivities().forEach { activity ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activities += activity
                }
            }
            LocatorAutoCollectStats.record(activeActivities = activities.size)
        }
    }

    private const val MIN_COLLECT_INTERVAL_MS = 80L
}

private object ActivityThreadActivityReader {
    fun currentActivities(): List<Activity> {
        return runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.findMethod("currentActivityThread")?.invoke(null) ?: return emptyList()
            val activities = threadClass.findField("mActivities")?.get(thread) as? Map<*, *>
                ?: thread.findActivityRecordMap()
                ?: return emptyList()
            activities.values.mapNotNull { record ->
                record.toActivityIfStarted()
            }
        }.getOrElse { error ->
            LocatorAutoCollectStats.record(
                failures = listOf("activityThread:${error.javaClass.simpleName}:${error.message.orEmpty()}"),
            )
            emptyList()
        }
    }

    private fun Any?.toActivityIfStarted(): Activity? {
        if (this == null) return null
        if (this is Activity) {
            return takeUnless { it.isFinishing || it.isDestroyed }
        }
        val stopped = readBooleanField("stopped")
            ?: readBooleanField("mStopped")
            ?: false
        if (stopped) return null
        return (readField("activity") as? Activity)
            ?: (readField("mActivity") as? Activity)
            ?: readFirstActivityField()
    }

    private fun Any.findActivityRecordMap(): Map<*, *>? {
        return javaClass.allFields().firstNotNullOfOrNull { field ->
            val value = runCatching { field.get(this) as? Map<*, *> }.getOrNull()
                ?: return@firstNotNullOfOrNull null
            value.takeIf { records ->
                records.values.any { record -> record.toActivityIfStarted() != null }
            }
        }
    }

    private fun Any.readFirstActivityField(): Activity? {
        return javaClass.allFields().firstNotNullOfOrNull { field ->
            runCatching { field.get(this) as? Activity }.getOrNull()
        }
    }

    private fun Any.readField(name: String): Any? {
        return javaClass.findField(name)?.get(this)
    }

    private fun Any.readBooleanField(name: String): Boolean? {
        return readField(name) as? Boolean
    }

    private fun Class<*>.findMethod(name: String): java.lang.reflect.Method? {
        var current: Class<*>? = this
        while (current != null) {
            current.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }?.let {
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

    private fun Class<*>.allFields(): Sequence<Field> {
        return generateSequence(this) { it.superclass }
            .flatMap { clazz -> clazz.declaredFields.asSequence() }
            .onEach { it.isAccessible = true }
    }
}
