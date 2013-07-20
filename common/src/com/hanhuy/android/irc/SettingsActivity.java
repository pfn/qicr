package com.hanhuy.android.irc;

import android.os.Bundle;
import android.preference.PreferenceActivity;

// written in java for @SuppressWarnings
public class SettingsActivity extends PreferenceActivity {
    @SuppressWarnings("deprecation")
    public void onCreate(Bundle b) {
        super.onCreate(b);
        addPreferencesFromResource(R.xml.settings);
    }
}
