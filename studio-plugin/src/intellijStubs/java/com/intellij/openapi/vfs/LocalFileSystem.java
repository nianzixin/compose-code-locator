package com.intellij.openapi.vfs;

import java.io.File;

public class LocalFileSystem {
    public static LocalFileSystem getInstance() {
        return new LocalFileSystem();
    }

    public VirtualFile refreshAndFindFileByIoFile(File file) {
        return null;
    }
}
