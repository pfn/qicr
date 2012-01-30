package com.hanhuy.android.irc;

import android.view.View;
import android.view.KeyEvent;
import android.widget.TabHost;
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

    private View input = null;
    private View container = null;
    private View getInput() {
        if (input == null)
            input = findViewById(R.id.input);
        return input;
    }
    private View getContainer() {
        if (container == null)
            container = findViewById(R.id.top_container);
        return container;
    }
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        View input = getInput();
        if (input.getVisibility() == View.VISIBLE) {
            getInput().requestFocus(View.FOCUS_DOWN);
            return getInput().dispatchKeyEvent(e);
        }
        return getContainer().dispatchKeyEvent(e);
    }
}
