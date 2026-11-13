package com.intellij.openapi.project;

/**
 * Stub: IntelliJ 项目生命周期监听器，签名对齐真实 SDK，
 * 仅声明插件实际使用的方法，未覆盖的方法在真实环境由 default 实现兜底
 */
public interface ProjectManagerListener {
    default void projectOpened(Project project) {
    }

    default void projectClosed(Project project) {
    }
}
