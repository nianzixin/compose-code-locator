package com.intellij.openapi.wm;

import com.intellij.openapi.project.Project;

public interface ToolWindowFactory {
    void createToolWindowContent(Project project, ToolWindow toolWindow);
}
