package com.hamzeh.scrcpyclient

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Entry screen: enter / pick a ws-scrcpy URL and tweak a few options. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var urlField: EditText
    private lateinit var content: View
    private lateinit var recentList: LinearLayout
    private lateinit var recentLabel: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        urlField = findViewById(R.id.url)
        content = findViewById(R.id.content)
        recentList = findViewById(R.id.recentList)
        recentLabel = findViewById(R.id.recentLabel)

        applyInsets(findViewById(R.id.root))
        capContentWidth()

        urlField.setText(prefs.lastUrl ?: "")
        urlField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { connect(); true } else false
        }
        findViewById<Button>(R.id.connect).setOnClickListener { connect() }

        bindOption(R.id.optHideToolbar, prefs.hideToolbar) { prefs.hideToolbar = it }
        bindOption(R.id.optReloadOnResize, prefs.reloadOnResize) { prefs.reloadOnResize = it }
        bindOption(R.id.optKeepScreenOn, prefs.keepScreenOn) { prefs.keepScreenOn = it }
        bindOption(R.id.optAutoConnect, prefs.autoConnect) { prefs.autoConnect = it }

        if (savedInstanceState == null && prefs.autoConnect) {
            prefs.lastUrl?.let { openStream(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        renderRecent()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        capContentWidth()
    }

    private fun bindOption(id: Int, value: Boolean, save: (Boolean) -> Unit) {
        val cb = findViewById<CheckBox>(id)
        cb.isChecked = value
        cb.setOnCheckedChangeListener { _, checked -> save(checked) }
    }

    private fun connect() {
        val url = Prefs.normalizeUrl(urlField.text.toString())
        if (url == null) {
            Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
            return
        }
        openStream(url)
    }

    private fun openStream(url: String) {
        prefs.addRecent(url)
        urlField.setText(url)
        startActivity(Intent(this, StreamActivity::class.java).putExtra(StreamActivity.EXTRA_URL, url))
    }

    private fun renderRecent() {
        recentList.removeAllViews()
        val urls = prefs.recentUrls
        recentLabel.visibility = if (urls.isEmpty()) View.GONE else View.VISIBLE
        for (url in urls) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.field_bg)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            val label = TextView(this).apply {
                text = url
                setTextColor(getColor(R.color.text))
                textSize = 14f
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(dp(14), dp(14), dp(8), dp(14))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { openStream(url) }
            }
            val remove = TextView(this).apply {
                text = "✕"
                contentDescription = getString(R.string.remove)
                setTextColor(getColor(R.color.text_dim))
                textSize = 16f
                setPadding(dp(14), dp(14), dp(14), dp(14))
                setOnClickListener { prefs.removeRecent(url); renderRecent() }
            }
            row.addView(label)
            row.addView(remove)
            recentList.addView(row)
        }
    }

    /** Keep the form at a comfortable width on wide (unfolded / tablet) screens. */
    private fun capContentWidth() {
        val screenW = resources.displayMetrics.widthPixels
        val lp = content.layoutParams as FrameLayout.LayoutParams
        lp.width = minOf(screenW, dp(560))
        lp.gravity = Gravity.CENTER_HORIZONTAL
        content.layoutParams = lp
    }

    private fun applyInsets(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or
                        WindowInsets.Type.displayCutout()
                )
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
