package com.intellij.ui.components;

import javax.swing.ListModel;
import javax.swing.JList;

public class JBList<E> extends JList<E> {
    public JBList(ListModel<E> dataModel) {
        super(dataModel);
    }
}
