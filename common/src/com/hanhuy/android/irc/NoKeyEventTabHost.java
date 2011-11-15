package com.hanhuy.android.irc;

import android.view.View;
import android.view.KeyEvent;
import android.widget.TabHost;
import android.widget.EditText;
import android.util.AttributeSet;
import android.content.Context;
import android.util.Log;

public class NoKeyEventTabHost extends TabHost {
    private final static String TAG = "NoKeyEventTabHost";
    public NoKeyEventTabHost(Context c) {
        super(c);
    }
    public NoKeyEventTabHost(Context c, AttributeSet a) {
        super(c, a);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int key = e.getKeyCode();
        boolean handled = false;
        EditText t = (EditText) findViewById(R.id.input);
        if (t.getVisibility() == View.VISIBLE) {
            t.requestFocus(View.FOCUS_DOWN);
            handled = t.dispatchKeyEvent(e);
        }
        //handled = handled || super.dispatchKeyEvent(e);
        return handled;
    }
}
