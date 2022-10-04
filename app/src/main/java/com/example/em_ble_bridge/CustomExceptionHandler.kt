package com.example.em_ble_bridge

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.PreferenceManager

import kotlin.system.exitProcess

import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage


class CustomExceptionHandler(context: Context) : Thread.UncaughtExceptionHandler {
    private var _app: Context = context
    private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        Toast.makeText(_app, ex.localizedMessage, Toast.LENGTH_LONG).show()
        if (preferences.getBoolean("errorReports", false)) {
            // do we need a new thread here? Threads are kind of an iffy thing to do here
            val t = Thread {
                Transport.send(plainMail(ex.localizedMessage))
            }
            t.start()
            t.join()
        }
        exitProcess(1)
    }

    private fun plainMail(messageText: String?): MimeMessage {
        val tos = arrayListOf(BuildConfig.ERROR_EMAIL)
        val from = BuildConfig.SENDER_EMAIL

        val properties = System.getProperties()

        with (properties) {
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "587")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.auth", "true")
            setProperty("mail.smtp.quitwait", "false")
            setProperty("mail.transport.protocol", "smtp")
        }

        val auth = object: Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(from, BuildConfig.SENDER_PASSWORD)
        }

        val session = Session.getInstance(properties, auth)

        val message = MimeMessage(session)

        with (message) {
            setFrom(InternetAddress(from))
            for (to in tos) {
                addRecipient(Message.RecipientType.TO, InternetAddress(to))
                subject = "EM BLE Bridge error"
                setContent("<html><body><p>${messageText}</p></body></html>", "text/html; charset=utf-8")
            }
        }

        return message
    }
}