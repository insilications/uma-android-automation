package com.steve1316.uma_android_automation.ui.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.core.content.edit
import androidx.preference.*
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.R

class TrainingStatTargetFragment : PreferenceFragmentCompat() {
	private val logTag: String = "[${MainActivity.loggerTag}]TrainingStatTargetFragment"
	private lateinit var sharedPreferences: SharedPreferences
	private lateinit var distanceType: String
	
	// This listener is triggered whenever the user changes a Preference setting in the Training Stat Targets Settings Page.
	private val onSharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
		if (key != null) {
			when (key) {
				"speedStatTarget" -> {
					val speedStatTargetPreference = findPreference<SeekBarPreference>("speedStatTarget")!!
					sharedPreferences.edit {
						putInt("${distanceType}_speedStatTarget", speedStatTargetPreference.value)
						commit()
					}
				}
				"staminaStatTarget" -> {
					val staminaStatTargetPreference = findPreference<SeekBarPreference>("staminaStatTarget")!!
					sharedPreferences.edit {
						putInt("${distanceType}_staminaStatTarget", staminaStatTargetPreference.value)
						commit()
					}
				}
				"powerStatTarget" -> {
					val powerStatTargetPreference = findPreference<SeekBarPreference>("powerStatTarget")!!
					sharedPreferences.edit {
						putInt("${distanceType}_powerStatTarget", powerStatTargetPreference.value)
						commit()
					}
				}
				"gutsStatTarget" -> {
					val gutsStatTargetPreference = findPreference<SeekBarPreference>("gutsStatTarget")!!
					sharedPreferences.edit {
						putInt("${distanceType}_gutsStatTarget", gutsStatTargetPreference.value)
						commit()
					}
				}
				"witStatTarget" -> {
					val witStatTargetPreference = findPreference<SeekBarPreference>("witStatTarget")!!
					sharedPreferences.edit {
						putInt("${distanceType}_witStatTarget", witStatTargetPreference.value)
						commit()
					}
				}
			}
		}
	}
	
	override fun onResume() {
		super.onResume()
		
		// Makes sure that OnSharedPreferenceChangeListener works properly and avoids the situation where the app suddenly stops triggering the listener.
		preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener)
	}
	
	override fun onPause() {
		super.onPause()
		preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener)
	}
	
	// This function is called right after the user navigates to the SettingsFragment.
	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		// Display the layout using the preferences xml.
		setPreferencesFromResource(R.xml.preferences_training_stat_target, rootKey)
		
		// Get the SharedPreferences.
		sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
		
		// Get the distance type from arguments
		distanceType = arguments?.getString("distanceType") ?: "trainingSprintStatTarget"
		
		// Load the saved stat targets for this distance type
		loadStatTargets()
		
		Log.d(logTag, "Training Stat Target Preferences created successfully for $distanceType.")
	}
	
	/**
	 * Load the saved stat targets for the current distance type.
	 */
	private fun loadStatTargets() {
		// Get references to the SeekBarPreference components
		val speedStatTargetPreference = findPreference<SeekBarPreference>("speedStatTarget")!!
		val staminaStatTargetPreference = findPreference<SeekBarPreference>("staminaStatTarget")!!
		val powerStatTargetPreference = findPreference<SeekBarPreference>("powerStatTarget")!!
		val gutsStatTargetPreference = findPreference<SeekBarPreference>("gutsStatTarget")!!
		val witStatTargetPreference = findPreference<SeekBarPreference>("witStatTarget")!!
		
		// Load saved values or use defaults based on distance type
		val (defaultSpeed, defaultStamina, defaultPower, defaultGuts, defaultWit) = getDefaultTargets()
		
		val savedSpeed = sharedPreferences.getInt("${distanceType}_speedStatTarget", defaultSpeed)
		val savedStamina = sharedPreferences.getInt("${distanceType}_staminaStatTarget", defaultStamina)
		val savedPower = sharedPreferences.getInt("${distanceType}_powerStatTarget", defaultPower)
		val savedGuts = sharedPreferences.getInt("${distanceType}_gutsStatTarget", defaultGuts)
		val savedWit = sharedPreferences.getInt("${distanceType}_witStatTarget", defaultWit)
		
		// Set the values
		speedStatTargetPreference.value = savedSpeed
		staminaStatTargetPreference.value = savedStamina
		powerStatTargetPreference.value = savedPower
		gutsStatTargetPreference.value = savedGuts
		witStatTargetPreference.value = savedWit
	}
	
	/**
	 * Get the default stat targets based on the distance type.
	 *
	 * @return The ArrayList of stat targets for training.
	 */
	private fun getDefaultTargets(): ArrayList<Int> {
		return when (distanceType) {
			"trainingSprintStatTarget" -> arrayListOf(900, 300, 600, 300, 300)
			"trainingMileStatTarget" -> arrayListOf(900, 300, 600, 300, 300)
			"trainingMediumStatTarget" -> arrayListOf(800, 450, 550, 300, 300)
			"trainingLongStatTarget" -> arrayListOf(700, 600, 450, 300, 300)
			else -> arrayListOf(900, 300, 600, 300, 300)
		}
	}
}