package com.intellij.ui;

import java.util.Collection;
import javax.swing.DefaultListModel;

public class CollectionListModel<E> extends DefaultListModel<E> {
    // 对齐真实平台签名：AI-262 的 CollectionListModel 只有带参构造器，无参版已移除
    public CollectionListModel(Collection<? extends E> items) {
        for (E item : items) {
            addElement(item);
        }
    }

    public void add(Collection<? extends E> items) {
        for (E item : items) {
            addElement(item);
        }
    }

    public void removeAll() {
        clear();
    }
}
