package dev.codelocator.studio.intellij

import javax.swing.SwingUtilities

/**
 * Studio 插件线程工具：全部基于普通 JDK 能力（Thread / SwingUtilities）实现。
 *
 * 背景：插件用 stubs 编译，IntelliJ 平台 API 签名会随 AS 版本漂移
 * （2026.2 已移除 Application.executeOnPooledThread，历史事故见 docs/release-engineering.md
 * "Studio Plugin API Compatibility" 一节），因此后台/UI 调度一律不经过平台 API
 */
object IdeThreading {
    /** 在后台线程执行 block；错误经 onError 回调（未传则忽略），不向平台异常通道抛出 */
    fun runInBackground(block: () -> Unit, onError: ((Throwable) -> Unit)? = null) {
        Thread {
            runCatching(block).onFailure { error ->
                onError?.invoke(error)
            }
        }.apply { isDaemon = true; start() }
    }

    /** 切回 EDT 执行 block（UI 更新用） */
    fun runOnUi(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeLater(block)
        }
    }
}
