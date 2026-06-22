package com.intellij.openapi.wm;

import com.intellij.openapi.project.Project;

public class ToolWindowManager {
    public static ToolWindowManager getInstance(Project project) {
        return new ToolWindowManager();
    }

    public ToolWindow getToolWindow(String id) {
        return null;
    }
}
