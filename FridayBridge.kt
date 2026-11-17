package com.friday.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.util.Base64
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Everything the HTML calls as window.Android.* */
class FridayBridge(private val act: MainActivity, private val web: WebView) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var sr: SpeechRecognizer? = null
    @Volatile private var listening = false

    init {
        tts = TextToSpeech(act) { st -> ttsReady = (st == TextToSpeech.SUCCESS) }
        StarkNotifService.sink = { ev -> js("window.onNativeEvent&&window.onNativeEvent(${ev})") }
    }

    fun release() {
        StarkNotifService.sink = null
        main.post { stopInternal() }
        tts?.shutdown()
    }

    private fun js(code: String) { act.runOnUiThread { web.evaluateJavascript(code, null) } }

    // ---------- speech out ----------
    @JavascriptInterface fun ttsStatus(): String = if (ttsReady) "native TTS ready" else "native TTS not ready"

    @JavascriptInterface fun speakLang(text: String, pitch: Double, rate: Double, lang: String) {
        val t = tts ?: return
        if (!ttsReady) return
        try {
            t.language = Locale.forLanguageTag(lang)
            t.setPitch(pitch.toFloat())
            t.setSpeechRate(rate.toFloat())
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "friday")
        } catch (e: Exception) {}
    }

    // ---------- speech in (beep muted via notification/system streams) ----------
    @JavascriptInterface fun startListening() {
        act.runOnUiThread {
            if (!SpeechRecognizer.isRecognitionAvailable(act)) { js("window.onVoiceState&&window.onVoiceState('unavailable')"); return@runOnUiThread }
            if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                act.requestPerm(Manifest.permission.RECORD_AUDIO) { ok ->
                    if (ok) startListening() else js("window.onVoiceState&&window.onVoiceState('denied')")
                }
                return@runOnUiThread
            }
            listening = true
            muteBeep(true)
            beginRec()
        }
    }

    @JavascriptInterface fun stopListening() { main.post { stopInternal() } }

    private fun stopInternal() {
        listening = false
        try { sr?.destroy() } catch (e: Exception) {}
        sr = null
        muteBeep(false)
    }

    private fun muteBeep(mute: Boolean) {
        val dir = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        for (s in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try { audio.adjustStreamVolume(s, dir, 0) } catch (e: Exception) {}
        }
    }

    private fun beginRec() {
        if (!listening) return
        try { sr?.destroy() } catch (e: Exception) {}
        sr = SpeechRecognizer.createSpeechRecognizer(act).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: android.os.Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!t.isNullOrBlank()) js("window.onVoiceResult&&window.onVoiceResult(${JSONObject.quote(t)})")
                    main.postDelayed({ beginRec() }, 300)
                }
                override fun onError(e: Int) {
                    if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        js("window.onVoiceState&&window.onVoiceState('denied')"); stopInternal(); return
                    }
                    main.postDelayed({ beginRec() }, if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1000 else 500)
                }
                override fun onReadyForSpeech(p: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: android.os.Bundle?) {}
                override fun onEvent(t: Int, b: android.os.Bundle?) {}
            })
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            )
        }
    }

    // ---------- apps ----------
    @JavascriptInterface fun getApps(): String {
        val pm = act.packageManager
        val li = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val arr = JSONArray()
        li.sortedBy { it.loadLabel(pm).toString().lowercase() }.forEach { ri ->
            val pkg = ri.activityInfo.packageName
            val cat = when (ri.activityInfo.applicationInfo.category) {
                ApplicationInfo.CATEGORY_GAME -> "Games"
                ApplicationInfo.CATEGORY_SOCIAL -> "Social"
                ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> "Media"
                ApplicationInfo.CATEGORY_IMAGE -> "Photos"
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Work"
                ApplicationInfo.CATEGORY_MAPS -> "Maps"
                ApplicationInfo.CATEGORY_NEWS -> "News"
                else -> "Apps"
            }
            arr.put(JSONObject().put("id", pkg).put("name", ri.loadLabel(pm).toString()).put("pkg", pkg)
                .put("emoji", "◉").put("color", "#29d3ff").put("cat", cat).put("native", true))
        }
        return arr.toString()
    }

    @JavascriptInterface fun launch(pkg: String): Boolean {
        val i = act.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        act.startActivity(i)
        return true
    }

    @JavascriptInterface fun openUrl(url: String) {
        try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
    }

    @JavascriptInterface fun openPanel(name: String) {
        val action = when (name) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        try { act.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
    }

    // ---------- notifications / media / calls ----------
    @JavascriptInterface fun hasNotifAccess(): Boolean {
        val s = Settings.Secure.getString(act.contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains(act.packageName)
    }

    @JavascriptInterface fun openNotifAccess() {
        try { act.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
    }

    @JavascriptInterface fun mediaInfo(): String {
        return try {
            val msm = act.getSystemService(MediaSessionManager::class.java)
            val c = msm.getActiveSessions(ComponentName(act, StarkNotifService::class.java)).firstOrNull()
                ?: return "{}"
            val md = c.metadata
            JSONObject()
                .put("title", md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "")
                .put("artist", md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "")
                .put("playing", c.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING)
                .toString()
        } catch (e: Exception) { "{}" }
    }

    @JavascriptInterface fun mediaControl(cmd: String) {
        val key = when (cmd) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "volup" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); return }
            "voldown" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); return }
            else -> return
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }

    @SuppressLint("MissingPermission")
    @JavascriptInterface fun callAction(key: String, action: String): String {
        if (act.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            act.runOnUiThread {
                act.requestPerm(Manifest.permission.ANSWER_PHONE_CALLS) { ok -> js("window.onPermResult&&window.onPermResult($ok)") }
            }
            return "perm"
        }
        return try {
            val tm = act.getSystemService(TelecomManager::class.java)
            if (action == "answer") { tm.acceptRingingCall(); "ok" }
            else if (Build.VERSION.SDK_INT >= 28) { tm.endCall(); "ok" }
            else "unsupported"
        } catch (e: SecurityException) { "perm" } catch (e: Exception) { "error" }
    }

    // ---------- torch ----------
    @JavascriptInterface fun setTorch(on: Boolean): Boolean {
        return try {
            val cm = act.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cm.setTorchMode(id, on)
            true
        } catch (e: Exception) { false }
    }

    // ---------- backup file save ----------
    @JavascriptInterface fun saveFile(name: String, mime: String, b64: String): String {
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = act.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return "Save failed"
                act.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                "Saved in Downloads: $name"
            } else {
                val f = File(act.getExternalFilesDir(null), name)
                f.writeBytes(bytes)
                "Saved: ${f.absolutePath}"
            }
        } catch (e: Exception) { "Save failed: ${e.message}" }
    }

    // ---------- device scan ----------
    @JavascriptInterface fun getDeviceInfo(): String {
        val o = JSONObject().put("source", "android")
        try {
            val bi = act.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val st = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            o.put("battery", JSONObject()
                .put("level", if (lvl < 0) JSONObject.NULL else lvl * 100 / scale)
                .put("charging", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
                .put("tempC", (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0)
                .put("voltageMv", bi?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0)
                .put("health", bi?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0))
        } catch (e: Exception) {}
        try {
            val mi = ActivityManager.MemoryInfo()
            (act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            val total = mi.totalMem / 1048576; val avail = mi.availMem / 1048576
            o.put("memory", JSONObject().put("totalMb", total).put("availMb", avail)
                .put("usedPct", ((total - avail) * 100 / total).toInt()).put("low", mi.lowMemory))
        } catch (e: Exception) {}
        try {
            val sf = StatFs(Environment.getDataDirectory().path)
            val tot = sf.totalBytes.toDouble(); val free = sf.availableBytes.toDouble()
            o.put("storage", JSONObject().put("usedPct", ((tot - free) * 100 / tot).toInt())
                .put("freeGb", Math.round(free / 1073741824.0 * 10) / 10.0))
        } catch (e: Exception) {}
        try {
            val n = Runtime.getRuntime().availableProcessors()
            val freqs = JSONArray()
            for (i in 0 until n) {
                try { freqs.put(File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toLong() / 1000) }
                catch (e: Exception) { freqs.put(JSONObject.NULL) }
            }
            o.put("cpu", JSONObject().put("cores", n).put("freqMhz", freqs).put("load", JSONObject.NULL))
        } catch (e: Exception) {}
        try {
            val cm = act.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val tr = when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
            o.put("network", JSONObject().put("online", caps != null).put("transport", tr)
                .put("downMbps", if (caps == null) JSONObject.NULL else caps.linkDownstreamBandwidthKbps / 1000))
        } catch (e: Exception) {}
        try {
            val dm = act.resources.displayMetrics
            val br = try { Settings.System.getInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255 } catch (e: Exception) { -1 }
            o.put("display", JSONObject().put("w", dm.widthPixels).put("h", dm.heightPixels).put("dpr", dm.density)
                .put("refresh", act.display?.refreshRate?.toInt() ?: JSONObject.NULL)
                .put("brightness", if (br < 0) JSONObject.NULL else br))
        } catch (e: Exception) {}
        try {
            val th = if (Build.VERSION.SDK_INT >= 29)
                when (act.getSystemService(PowerManager::class.java).currentThermalStatus) {
                    0 -> "normal"; 1 -> "light"; 2 -> "moderate"; 3 -> "severe"; else -> "critical"
                } else "n/a"
            o.put("thermal", JSONObject().put("status", th))
        } catch (e: Exception) {}
        o.put("sensors", JSONObject().put("accelMag", JSONObject.NULL).put("light", JSONObject.NULL))
        o.put("device", JSONObject().put("maker", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("uptimeMin", SystemClock.elapsedRealtime() / 60000)
            .put("apps", act.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).size))
        return o.toString()
    }
}
