package com.example.em_ble_bridge

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

import androidx.preference.PreferenceManager
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.android.synthetic.main.settings_activity.*
import java.lang.Exception


class SetupActivity : AppCompatActivity() {
    // all of the form fields
    private lateinit var rssiFilter: SeekBar
    private lateinit var rssiFilterLabel: TextView
    private lateinit var friendlyNameFilter: EditText
    private lateinit var mqttPort: EditText
    private lateinit var mqttHost: EditText
    private lateinit var mqttUsername: EditText
    private lateinit var mqttPassword: EditText
    private lateinit var mqttTopic: EditText
    private lateinit var mqttBridgeName: EditText
    private lateinit var errorReportSwitch: SwitchMaterial
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler(CustomExceptionHandler(this))
        setContentView(R.layout.settings_activity)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Setup"
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        initFormFields()
        setupSeekBar()
        setFormHintsToPreferences()
    }

    private fun initFormFields() {
        rssiFilter = findViewById(R.id.rssiFilter)
        rssiFilterLabel = findViewById(R.id.rssiFilterLabel)
        friendlyNameFilter = findViewById(R.id.friendlyNameFilter)
        mqttPort = findViewById(R.id.mqttPort)
        mqttHost = findViewById(R.id.mqttHost)
        mqttUsername = findViewById(R.id.mqttUsername)
        mqttPassword = findViewById(R.id.mqttPassword)
        mqttTopic = findViewById(R.id.mqttTopic)
        mqttBridgeName = findViewById(R.id.mqttBridgeName)
        errorReportSwitch = findViewById(R.id.errorReportSwitch)
    }

    private fun setupSeekBar() {
        rssiFilter.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar,
                                           progress: Int, fromUser: Boolean) {
                if (progress == 100) {
                    rssiFilterLabel?.text = "RSSI filter: all dbm"
                    return
                }
                rssiFilterLabel?.text = "RSSI filter: -$progress dbm"
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })
    }

    private fun setFormHintsToPreferences() {
        mqttHost.hint = prefs.getString("mqttHost", BuildConfig.MQTT_HOST)
        mqttPort.hint = prefs.getInt("mqttPort", BuildConfig.MQTT_PORT).toString()
        mqttTopic.hint = prefs.getString("mqttTopic", getString(R.string.defaultMQTTTopic))
        mqttUsername.hint = prefs.getString("mqttUsername", "******")
        mqttPassword.hint = "******"
        val rssiFilterValue = prefs.getInt("rssiFilter", getString(R.string.defaultRssiFilter).toInt())
        rssiFilter.progress = -rssiFilterValue
        rssiFilterLabel.text = "RSSI filter $rssiFilterValue dbm"
        mqttBridgeName.hint = prefs.getString("bridgeName", utils().getRandomDeviceName())
        friendlyNameFilter.hint = prefs.getString("friendlyNameFilter", getString(R.string.defaultFriendlyNameFilter))
        errorReportSwitch.isChecked = prefs.getBoolean("errorReports", false)
    }

    // functionality for the back button to return to main activity
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                goToMainActivity()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // util function for going to main activity
    private fun goToMainActivity() {
        // call finish here, it will bring you back to the main activity
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
    }

    // sets the text value to empty strings so they doesn't get recorded in the preferences. Current
    // preferences are shown in the hints because apparently that's good UI design
    private fun resetForm() {
        friendlyNameFilter.setText("")
        mqttTopic.setText("")
        mqttHost.setText("")
        mqttUsername.setText("")
        mqttPassword.setText("")
        mqttBridgeName.setText("")
    }

    // makes sure all the form values are correct
    private fun validateForm() {
        if (mqttPort.text.toString().isNotEmpty() && mqttPort.text.toString().length != 4) {
            throw Exception("Invalid port number of ${mqttPort.text}\nMust be 4 digits")
        }

        if (rssiFilter.progress < 0 || rssiFilter.progress > 100) {
            throw Exception("Invalid rssi filter value of ${rssiFilter.progress}... Don't know how this could ever happen")
        }
    }

    // called when restore defaults button is pressed. Restores preferences to default preferences
    // found in BuildConfig and string resources
    fun handleRestoreDefaults(view: View) {
        val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        with (preferences.edit()) {
            putInt("rssiFilter", getString(R.string.defaultRssiFilter).toInt())
            putString("friendlyNameFilter", getString(R.string.defaultFriendlyNameFilter))
            putString("mqttTopic", getString(R.string.defaultMQTTTopic))
            putString("mqttHost", BuildConfig.MQTT_HOST)
            putString("mqttUsername", BuildConfig.MQTT_USERNAME)
            putString("mqttPassword", BuildConfig.MQTT_PASSWORD)
            putInt("mqttPort", BuildConfig.MQTT_PORT)
            putString("bridgeName", utils().getRandomDeviceName())
            putBoolean("errorReports", false)
            commit()
        }
        resetForm()
        setFormHintsToPreferences()
    }

    // called when save and continue button is pressed. Sets all the preferences to values entered
    // in the form.
    fun handleSaveAndContinue(view: View) {
        // get values from all the form stuff
        with (prefs.edit()) {
            try {
                validateForm()
                putInt("rssiFilter", -rssiFilter.progress)
                putBoolean("errorReports", errorReportSwitch.isChecked)

                if (friendlyNameFilter.text.toString().isNotEmpty()) {
                    putString("friendlyNameFilter", friendlyNameFilter.text.toString())
                }
                if (mqttTopic.text.isNotEmpty()) {
                    putString("mqttTopic", mqttTopic.text.toString())
                }
                if (mqttHost.text.toString().isNotEmpty()) {
                    putString("mqttHost", mqttHost.text.toString())
                }
                if (mqttUsername.text.toString().isNotEmpty()) {
                    putString("mqttUsername", mqttUsername.text.toString())
                }
                if (mqttPassword.text.toString().isNotEmpty()) {
                    putString("mqttPassword", mqttPassword.text.toString())
                }
                if (mqttPort.text.toString().isNotEmpty()) {
                    putInt("mqttPort", mqttPort.text.toString().toInt())
                }
                if (mqttBridgeName.text.toString().isNotEmpty()) {
                    putString("bridgeName", mqttBridgeName.text.toString())
                }
                apply()
            }
            catch (e: Exception) {
                e.message?.let { utils().setUIError("Form error", it, this@SetupActivity) }
                return
            }
        }
        setFormHintsToPreferences()
        goToMainActivity()
    }
}