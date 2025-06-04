package com.pz.ecg_project

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        fun updateSummaries(pref: Preference) {
            when (pref) {
                is EditTextPreference -> {
                    pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                        it.text ?: ""
                    }
                }
                is ListPreference -> {
                    pref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                }
                is PreferenceCategory -> {
                    for (i in 0 until pref.preferenceCount) {
                        updateSummaries(pref.getPreference(i))
                    }
                }
            }
        }

        val screen = preferenceScreen
        for (i in 0 until screen.preferenceCount) {
            updateSummaries(screen.getPreference(i))
        }
    }
}
