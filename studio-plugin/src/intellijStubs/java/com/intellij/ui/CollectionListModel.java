package com.intellij.ui;

import java.util.Collection;
import javax.swing.DefaultListModel;

public class CollectionListModel<E> extends DefaultListModel<E> {
    public void add(Collection<? extends E> items) {
        for (E item : items) {
            addElement(item);
        }
    }

    public void removeAll() {
        clear();
    }
}
