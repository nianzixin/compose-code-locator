package com.intellij.openapi.application;

public final class ApplicationManager {
    private static final Application APPLICATION = new StubApplication();

    private ApplicationManager() {
    }

    public static Application getApplication() {
        return APPLICATION;
    }

    private static final class StubApplication implements Application {
        @Override
        public void executeOnPooledThread(Runnable runnable) {
            runnable.run();
        }

        @Override
        public void invokeLater(Runnable runnable) {
            runnable.run();
        }
    }
}
