package com.intellij.openapi.application;

public interface Application {
    void executeOnPooledThread(Runnable runnable);

    void invokeLater(Runnable runnable);
}
