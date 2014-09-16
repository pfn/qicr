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
        getPreferenceScreen().findPreference(
                "debug.log").setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference pref) {
                        if ("debug.log".equals(pref.getKey())) {
                            Toast.makeText(SettingsActivity.this,
                                    "Debug log sent",
                                    Toast.LENGTH_SHORT).show();
                            Exception e = new Exception("User submitted log");
                            e.setStackTrace(new StackTraceElement[0]);
                            ACRA.getErrorReporter().handleSilentException(e);
                            return true;
                        }
                        return false;
                    }
                });
    }
}
