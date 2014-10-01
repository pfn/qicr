package com.hanhuy.android.irc;

import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.widget.Toast;
import org.acra.ACRA;

// written in java for @SuppressWarnings
public class SettingsActivity extends PreferenceActivity {
    @SuppressWarnings("deprecation")
    public void onCreate(Bundle b) {
        super.onCreate(b);
        addPreferencesFromResource(R.xml.settings);
        Preference p = getPreferenceScreen().findPreference("debug.log");
        getPreferenceScreen().removePreference(p);
    }
}
