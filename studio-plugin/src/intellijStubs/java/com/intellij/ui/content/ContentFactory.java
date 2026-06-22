package com.intellij.ui.content;

import javax.swing.JComponent;

public class ContentFactory {
    public static ContentFactory getInstance() {
        return new ContentFactory();
    }

    public Content createContent(JComponent component, String displayName, boolean isLockable) {
        return new StubContent();
    }

    private static final class StubContent implements Content {
    }
}
