package com.hamzeh.scrcpyclient

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Full-screen, touch-enabled ws-scrcpy viewer.
 *
 * - Runs edge-to-edge in immersive mode (also under the camera cutout).
 * - Handles fold / unfold / rotation / multi-window resizes without recreating the
 *   WebView, then (optionally) reconnects so ws-scrcpy re-fits the video to the new size.
 * - Touches go straight to the page; WebView zoom/scroll is disabled so gestures reach
 *   ws-scrcpy's touch handler.
 */
class StreamActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var webView: WebView
    private lateinit var menuButton: TextView
    private lateinit var startUrl: String

    private val handler = Handler(Looper.getMainLooper())
    private var lastScreenW = 0
    private var lastScreenH = 0
    private var errorDialog: AlertDialog? = null
    private var authAttempts = 0

    private val refitRunnable = Runnable { refit() }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        startUrl = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }

        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isHapticFeedbackEnabled = false
            // Avoid the long-press text selection / context menu stealing touches.
            isLongClickable = false
            setOnLongClickListener { true }
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = false
            useWideViewPort = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            textZoom = 100
        }
        webView.webViewClient = Client()
        webView.webChromeClient = WebChromeClient()

        root.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        menuButton = TextView(this).apply {
            text = "⋮"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.fab_bg)
            alpha = 0.55f
            contentDescription = "Menu"
            visibility = if (prefs.showMenuButton) View.VISIBLE else View.GONE
        }
        val size = dp(44)
        root.addView(menuButton, FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(16)
            marginEnd = dp(16)
        })
        makeDraggable(menuButton)

        setContentView(root)
        goImmersive()

        rememberScreenSize(resources.configuration)
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(startUrl)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        errorDialog?.dismiss()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        super.onDestroy()
    }

    /** Called instead of recreation thanks to android:configChanges (fold, unfold, rotate…). */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        goImmersive()
        val changed = newConfig.screenWidthDp != lastScreenW || newConfig.screenHeightDp != lastScreenH
        rememberScreenSize(newConfig)
        if (changed) {
            // Let the WebView lay out at the new size first, then re-fit. Debounced because
            // a fold animation can deliver several configuration changes in a row.
            handler.removeCallbacks(refitRunnable)
            handler.postDelayed(refitRunnable, REFIT_DELAY_MS)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        showMenu()
    }

    // ---------------------------------------------------------------- menu

    private fun showMenu() {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += getString(R.string.menu_toolbar) to {
            prefs.hideToolbar = !prefs.hideToolbar
            injectPageTweaks()
            // ws-scrcpy sizes the video around its toolbar, so reconnect to re-fit.
            if (isStreamPage()) webView.reload()
        }
        actions += getString(R.string.menu_reload) to { webView.reload() }
        if (isStreamPage() && webView.canGoBack()) {
            actions += "Device list" to { webView.goBack() }
        }
        if (prefs.showMenuButton) {
            actions += getString(R.string.menu_hide_button) to {
                prefs.showMenuButton = false
                menuButton.visibility = View.GONE
            }
        } else {
            actions += "Show menu button" to {
                prefs.showMenuButton = true
                menuButton.visibility = View.VISIBLE
            }
        }
        actions += getString(R.string.menu_disconnect) to { finish() }

        AlertDialog.Builder(this)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setOnDismissListener { goImmersive() }
            .show()
    }

    /** Small floating button: tap opens the menu, drag moves it out of the way. */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(view: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startTx = 0f
        var startTy = 0f
        var dragging = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startTx = v.translationX; startTy = v.translationY
                    dragging = false
                    v.alpha = 0.9f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val parent = v.parent as View
                        // Keep the button fully on screen.
                        v.translationX = (startTx + dx).coerceIn(-(v.left.toFloat()), (parent.width - v.right).toFloat())
                        v.translationY = (startTy + dy).coerceIn(-(v.top.toFloat()), (parent.height - v.bottom).toFloat())
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 0.55f
                    if (!dragging) showMenu()
                }
                MotionEvent.ACTION_CANCEL -> v.alpha = 0.55f
            }
            true
        }
    }

    // ---------------------------------------------------------------- fold / resize

    private fun rememberScreenSize(c: Configuration) {
        lastScreenW = c.screenWidthDp
        lastScreenH = c.screenHeightDp
    }

    private fun refit() {
        // Keep the floating button on screen after the window changed size.
        menuButton.translationX = 0f
        menuButton.translationY = 0f
        if (prefs.reloadOnResize && isStreamPage()) {
            // ws-scrcpy picks the video resolution from the window size when it connects,
            // so a quick reconnect gives a sharp, correctly-fitted picture on the new screen.
            webView.reload()
        } else {
            webView.evaluateJavascript("window.dispatchEvent(new Event('resize'));", null)
        }
    }

    private fun isStreamPage(): Boolean = webView.url?.contains("action=stream") == true

    // ---------------------------------------------------------------- page tweaks

    /**
     * Injects CSS that removes margins/scrolling on the stream page (and optionally hides the
     * ws-scrcpy side toolbar) plus a viewport meta tag so the page is laid out at device width.
     * ws-scrcpy switches pages through the URL hash, so the tweaks re-apply on hashchange.
     */
    private fun injectPageTweaks() {
        val hide = if (prefs.hideToolbar) "true" else "false"
        val js = """
            (function() {
              window.__smHideToolbar = $hide;
              if (!window.__smApply) {
                window.__smApply = function() {
                  var head = document.head || document.documentElement;
                  var m = document.querySelector('meta[name=viewport]');
                  if (!m) { m = document.createElement('meta'); m.name = 'viewport'; head.appendChild(m); }
                  m.content = 'width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover';
                  var s = document.getElementById('sm-style');
                  if (!s) { s = document.createElement('style'); s.id = 'sm-style'; head.appendChild(s); }
                  var stream = location.href.indexOf('action=stream') >= 0;
                  var css = '* { -webkit-tap-highlight-color: transparent; }';
                  if (stream) {
                    css += 'html, body { margin:0 !important; padding:0 !important; background:#000 !important;' +
                           ' overflow:hidden !important; overscroll-behavior:none !important;' +
                           ' -webkit-user-select:none; user-select:none; -webkit-touch-callout:none; }' +
                           '.device-view { margin:0 !important; }' +
                           'canvas, video { touch-action:none; }';
                    if (window.__smHideToolbar) {
                      css += '.control-buttons-list, .more-box { display:none !important; }';
                    }
                  }
                  s.textContent = css;
                };
                window.addEventListener('hashchange', function() { setTimeout(window.__smApply, 0); });
              }
              window.__smApply();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ---------------------------------------------------------------- web client

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") return false
            // Hand anything else (intent:, mailto:, …) to the system.
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, request.url)); true
            } catch (_: Exception) { true }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            injectPageTweaks()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            injectPageTweaks()
            authAttempts = 0
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            // Fires on hash navigation too (device list -> stream).
            injectPageTweaks()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            showLoadError(error.description?.toString() ?: "")
        }

        override fun onReceivedHttpAuthRequest(view: WebView, h: HttpAuthHandler, host: String, realm: String) {
            val db = WebViewDatabase.getInstance(this@StreamActivity)
            val saved = db.getHttpAuthUsernamePassword(host, realm)
            if (authAttempts == 0 && h.useHttpAuthUsernamePassword() && saved != null &&
                saved.size == 2 && saved[0] != null && saved[1] != null
            ) {
                authAttempts++
                h.proceed(saved[0], saved[1])
                return
            }
            authAttempts++
            askCredentials(host, realm, h)
        }
    }

    private fun askCredentials(host: String, realm: String, h: HttpAuthHandler) {
        val user = EditText(this).apply { hint = getString(R.string.auth_user); isSingleLine = true }
        val pass = EditText(this).apply {
            hint = getString(R.string.auth_pass)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(user); addView(pass)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.auth_title))
            .setMessage(host)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(R.string.ok) { _, _ ->
                val u = user.text.toString()
                val p = pass.text.toString()
                WebViewDatabase.getInstance(this).setHttpAuthUsernamePassword(host, realm, u, p)
                h.proceed(u, p)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> h.cancel(); finish() }
            .show()
    }

    private fun showLoadError(description: String) {
        if (errorDialog?.isShowing == true) return
        val host = Uri.parse(startUrl).host ?: startUrl
        errorDialog = AlertDialog.Builder(this)
            .setMessage(getString(R.string.load_error, host, description))
            .setCancelable(false)
            .setPositiveButton(R.string.retry) { _, _ -> webView.reload() }
            .setNegativeButton(R.string.menu_disconnect) { _, _ -> finish() }
            .setOnDismissListener { goImmersive() }
            .show()
    }

    // ---------------------------------------------------------------- immersive

    private fun goImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        const val EXTRA_URL = "url"
        private const val REFIT_DELAY_MS = 700L
    }
}
