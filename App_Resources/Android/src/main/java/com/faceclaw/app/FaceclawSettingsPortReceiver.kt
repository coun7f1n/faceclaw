package com.faceclaw.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** adb-only transfer of ordinary settings. Credentials are never exported or imported. */
class FaceclawSettingsPortReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_EXPORT = "com.faceclaw.app.SETTINGS_EXPORT"
        const val ACTION_IMPORT = "com.faceclaw.app.SETTINGS_IMPORT"
        private const val PREFS_NAME = "faceclaw_settings"
        private const val EXPORT_NAME = "faceclaw-settings-export.xml"
        private const val IMPORT_NAME = "faceclaw-settings-import.xml"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            FaceclawSettings.getInstance(context) // Eagerly migrate any old credentials.
            resultData = when (intent?.action) {
                ACTION_EXPORT -> exportSettings(context)
                ACTION_IMPORT -> importSettings(context)
                else -> "error: unsupported action"
            }
        } catch (_: Exception) {
            // Never put credential or parser/crypto exception text in adb output or logcat.
            resultData = "error: settings transfer failed; check installed app and input format"
        } finally {
            if (intent?.action == ACTION_IMPORT) {
                runCatching { externalFile(context, IMPORT_NAME).delete() }
            }
        }
    }

    private fun externalFile(context: Context, name: String): File =
        File(context.getExternalFilesDir(null) ?: throw IOException("External storage unavailable"), name)

    private fun exportSettings(context: Context): String {
        val out = externalFile(context, EXPORT_NAME)
        val values = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
        FileOutputStream(out).use { stream ->
            val xml = Xml.newSerializer()
            xml.setOutput(stream, "UTF-8")
            xml.startDocument("UTF-8", true)
            xml.startTag(null, "map")
            for ((name, value) in values.toSortedMap()) {
                if (FaceclawSecretKeys.contains(name) || FaceclawSecretKeys.isInternal(name)) continue
                writeValue(xml, name, value)
            }
            xml.endTag(null, "map")
            xml.endDocument()
            stream.fd.sync()
        }
        return "exported: $out (ordinary settings only)"
    }

    private fun importSettings(context: Context): String {
        val staged = externalFile(context, IMPORT_NAME)
        if (!staged.exists()) return "error: no staged settings file"
        val incoming = parse(staged)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        for (name in prefs.all.keys) {
            if (!FaceclawSecretKeys.contains(name) && !FaceclawSecretKeys.isInternal(name)) edit.remove(name)
        }
        for ((name, value) in incoming) {
            when (value) {
                is String -> edit.putString(name, value)
                is Boolean -> edit.putBoolean(name, value)
                is Int -> edit.putInt(name, value)
                is Long -> edit.putLong(name, value)
                is Float -> edit.putFloat(name, value)
            }
        }
        if (!edit.commit()) throw IOException("Settings import could not be committed")
        staged.delete()
        return "imported: ordinary settings only; restart app to refresh settings"
    }

    private fun parse(file: File): Map<String, Any> {
        val values = mutableMapOf<String, Any>()
        FileInputStream(file).use { stream ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(stream, "UTF-8")
            if (parser.nextTag() != XmlPullParser.START_TAG || parser.name != "map") throw IOException("Invalid settings")
            while (parser.nextTag() == XmlPullParser.START_TAG) {
                val type = parser.name
                val name = parser.getAttributeValue(null, "name") ?: throw IOException("Missing setting name")
                if (name.isEmpty() || name in values || FaceclawSecretKeys.contains(name) ||
                    FaceclawSecretKeys.isInternal(name)) throw IOException("Protected or duplicate setting")
                val attribute = parser.getAttributeValue(null, "value")
                val value: Any = when (type) {
                    "string" -> parser.nextText()
                    "boolean" -> when (attribute) { "true" -> true; "false" -> false; else -> throw IOException("Invalid boolean") }
                    "int" -> attribute?.toIntOrNull() ?: throw IOException("Invalid integer")
                    "long" -> attribute?.toLongOrNull() ?: throw IOException("Invalid long")
                    "float" -> attribute?.toFloatOrNull()?.takeIf { it.isFinite() } ?: throw IOException("Invalid float")
                    else -> throw IOException("Unsupported setting type")
                }
                if (type != "string" && parser.nextTag() != XmlPullParser.END_TAG) throw IOException("Nested setting")
                values[name] = value
            }
            if (parser.name != "map") throw IOException("Invalid settings end")
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (!parser.isWhitespace) throw IOException("Trailing settings content")
            }
        }
        return values
    }

    private fun writeValue(xml: XmlSerializer, name: String, value: Any?) {
        val type = when (value) {
            is String -> "string"
            is Boolean -> "boolean"
            is Int -> "int"
            is Long -> "long"
            is Float -> "float"
            else -> return
        }
        xml.startTag(null, type)
        xml.attribute(null, "name", name)
        if (type == "string") xml.text(value as String)
        else xml.attribute(null, "value", value.toString())
        xml.endTag(null, type)
    }
}
