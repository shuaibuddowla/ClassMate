package com.shuaib.classmate.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.shuaib.classmate.R
import com.shuaib.classmate.transit.ClassMateTransitBridge
import com.shuaib.classmate.ui.ClassMateHaptics

class ClassMateTransitActivity : AppCompatActivity() {

    private var webView: android.webkit.WebView? = null
    private var pendingBusId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContentView(R.layout.activity_classmate_transit)

        pendingBusId = intent.getStringExtra("EXTRA_BUS_ID")

        val root = findViewById<View>(R.id.transitActivityRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, statusBars.top, 0, navBars.bottom)
            insets
        }

        findViewById<ImageButton>(R.id.btnTransitBack).setOnClickListener {
            ClassMateHaptics.selection(it)
            handleBackAction()
        }

        findViewById<ImageButton>(R.id.btnTransitSchedule).setOnClickListener {
            ClassMateHaptics.selection(it)
            startActivity(Intent(this, BusScheduleManagementActivity::class.java))
        }

        setupWebView()
    }

    private fun setupWebView() {
        val wv = findViewById<android.webkit.WebView>(R.id.transit_webview) ?: return
        webView = wv
        val loadingView = findViewById<View>(R.id.transit_loading_view)
        loadingView?.visibility = View.VISIBLE
        loadingView?.alpha = 1f

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadsImagesAutomatically = true
            allowFileAccess = true
            allowContentAccess = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            cacheMode = if (ClassMateAcademicCache.online(this@ClassMateTransitActivity)) android.webkit.WebSettings.LOAD_DEFAULT else android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
            userAgentString = "${userAgentString} ClassMate-Android"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                offscreenPreRaster = true
            }
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            wv.setRendererPriorityPolicy(android.webkit.WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }

        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.setBackgroundColor(android.graphics.Color.parseColor("#090D16"))

        val bridge = ClassMateTransitBridge(this)
        var canBridgeGoBack = false

        bridge.onBackStateChanged = { canGoBack ->
            runOnUiThread {
                canBridgeGoBack = canGoBack
            }
        }

        bridge.onMapReadyListener = {
            runOnUiThread {
                if (loadingView?.visibility == View.VISIBLE) {
                    loadingView?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
                        loadingView?.visibility = View.GONE
                    }?.start()
                }
                pendingBusId?.let { targetBus ->
                    val cleanBus = if (targetBus.startsWith("SB", ignoreCase = true)) targetBus.uppercase() else "SB$targetBus"
                    wv.evaluateJavascript("if (window.__classmateSelectBus) { window.__classmateSelectBus('$cleanBus'); }", null)
                    pendingBusId = null
                }
            }
        }

        wv.addJavascriptInterface(bridge, "ClassMateNativeBridge")

        wv.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
        }

        wv.webViewClient = object : android.webkit.WebViewClient() {
            override fun onReceivedError(
                view: android.webkit.WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val failingUrl = request.url?.toString() ?: ""
                    android.util.Log.w("ClassMateTransit", "Transit load error: ${error?.errorCode} ${error?.description} for $failingUrl")
                    if (!failingUrl.startsWith("file://") && view?.url?.startsWith("file://") != true) {
                        view?.loadUrl("file:///android_asset/transit/index.html")
                    }
                }
            }

            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                val targetBus = pendingBusId
                if (!targetBus.isNullOrBlank()) {
                    val cleanBus = if (targetBus.startsWith("SB", ignoreCase = true)) targetBus.uppercase() else "SB$targetBus"
                    view?.evaluateJavascript(
                        "if (window.__classmateSelectBus) { window.__classmateSelectBus('$cleanBus'); } else { window.__classmateInitialBusId = '$cleanBus'; window.location.hash = '#transit?bus=$cleanBus'; }",
                        null
                    )
                }
            }
        }

        val viewParam = if (pendingBusId != null) "view=map" else "view=list"
        val busParam = pendingBusId?.let { "&bus=$it" } ?: ""
        val transitUrl = "https://classmatebd.vercel.app/transit-preview?app=android&$viewParam$busParam&v=20261101_v3_clean"
        wv.clearCache(true)
        wv.loadUrl(transitUrl)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canBridgeGoBack) {
                    wv.evaluateJavascript("if (window.__classmateTransitGoBack) { window.__classmateTransitGoBack(); } else if (window.history.length > 1) { window.history.back(); }", null)
                } else if (wv.canGoBack()) {
                    wv.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun handleBackAction() {
        onBackPressedDispatcher.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
