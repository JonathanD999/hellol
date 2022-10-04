package com.example.em_ble_bridge

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat.startActivity
import java.util.*
import kotlin.random.Random

class utils {
    // displays an AlertDialog for those inevitable errors
    fun setUIError(title: String, message: String, context: Context) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message) // Specifying a listener allows you to take an action before dismissing the dialog.
            // The dialog is automatically dismissed when a dialog button is clicked.
            .setPositiveButton(
                "Change setup"
            ) { _, _ ->
                // Continue with delete operation
                goToSetupPage(context)
            } // A null listener allows the button to dismiss the dialog and take no further action.
            .setNegativeButton(android.R.string.cancel, null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    // changes activity to setup
    fun goToSetupPage(context: Context) {
        val intent = Intent(context, SetupActivity::class.java)
        startActivity(context, intent, null)
    }

    fun getRandomDeviceName(): String {
        val randomString1: String = Random.nextInt(0, 100).toString().padStart(3, '0')
        val randomString2: String = Random.nextInt(0, 100).toString().padStart(3, '0')

        val deviceName: String = getDeviceName()
        // here we set this random name in the resource file?

        return "${deviceName}_${randomString1}_${randomString2}"
    }

    private fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.lowercase(Locale.getDefault()).startsWith(manufacturer.lowercase(Locale.getDefault()))) {
            capitalize(model)
        } else {
            capitalize(manufacturer) + "_" + model
        }
    }


    private fun capitalize(s: String?): String {
        if (s == null || s.isEmpty()) {
            return ""
        }
        val first = s[0]
        return if (Character.isUpperCase(first)) {
            s
        } else {
            Character.toUpperCase(first).toString() + s.substring(1)
        }
    }
}