package com.intellij.openapi.diagnostic;

/**
 * Stub: IntelliJ 日志门面，签名对齐真实 SDK（getInstance/info/warn），
 * stub 环境下直接走 stderr
 */
public class Logger {
    private final String category;

    private Logger(String category) {
        this.category = category;
    }

    public static Logger getInstance(Class<?> clazz) {
        return new Logger(clazz.getName());
    }

    public void info(String message) {
        System.out.println("[" + category + "] INFO: " + message);
    }

    public void warn(String message, Throwable t) {
        System.err.println("[" + category + "] WARN: " + message + " :: " + t);
    }
}
