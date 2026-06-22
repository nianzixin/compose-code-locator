package com.intellij.openapi.wm;

import com.intellij.ui.content.ContentManager;

public interface ToolWindow {
    ContentManager getContentManager();

    void show();
}
