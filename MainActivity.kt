package com.friday.launcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var bridge: FridayBridge
    private var permCallback: ((Boolean) -> Unit)? = null

    private val singlePerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permCallback?.invoke(ok); permCallback = null
    }
    private val startupPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startWakeServiceIfReady()
    }

    fun requestPerm(perm: String, cb: (Boolean) -> Unit) {
        permCallback = cb
        singlePerm.launch(perm)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/icon/", IconPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }
        bridge = FridayBridge(this, web)
        web.addJavascriptInterface(bridge, "Android")
        web.loadUrl("https://appassets.androidplatform.net/assets/friday_home.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(typeof starkBack==='function')?String(starkBack()):'false'") { r ->
                    if (r?.contains("true") != true) moveTaskToBack(true)
                }
            }
        })

        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startWakeServiceIfReady()
        } else {
            startupPerms.launch(need.toTypedArray())
        }
        handleWake(intent)
    }

    private fun startWakeServiceIfReady() {
        if (BuildConfig.PICOVOICE_KEY.isBlank()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        ContextCompat.startForegroundService(this, Intent(this, WakeService::class.java))
    }

    private fun handleWake(i: Intent?) {
        if (i?.getBooleanExtra("wake", false) == true) {
            Handler(Looper.getMainLooper()).postDelayed({ bridge.startListening() }, 900)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWake(intent)
    }

    override fun onResume() {
        super.onResume()
        web.evaluateJavascript("window.onLauncherResume&&window.onLauncherResume()", null)
    }

    override fun onDestroy() {
        bridge.release()
        web.destroy()
        super.onDestroy()
    }
}
