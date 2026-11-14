package com.intellij.ui.content;

import javax.swing.JComponent;

/**
 * Stub: 对齐 Android Studio 2026.2 真实签名（ContentFactory 已从类改为 interface，
 * getInstance() 为接口静态方法；历史事故见 docs/release-engineering.md
 * "Studio Plugin API Compatibility" 一节）。
 * stub 仅用于编译，getInstance 返回 null 无运行时语义
 */
public interface ContentFactory {
    static ContentFactory getInstance() {
        return null;
    }

    Content createContent(JComponent component, String displayName, boolean isLockable);
}
