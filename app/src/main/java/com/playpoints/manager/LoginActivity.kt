package com.playpoints.manager

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACCOUNT_ID = "extra_account_id"
    }

    private lateinit var webView: WebView
    private var editingAccount: AccountItem? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.statusBarColor = Color.parseColor("#0E1619")

        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
        if (accountId != null) {
            editingAccount = AccountStore.getAccounts(this).firstOrNull { it.id == accountId }
        }
        val displayTitle = editingAccount?.name ?: AccountStore.nextDefaultName(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1619"))
        }

        // الشريط العلوي
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#162228"))
        }

        val btnClose = TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(16, 8, 16, 8)
            setOnClickListener { finish() }
        }

        val tvTitle = TextView(this).apply {
            text = displayTitle
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnRefresh = TextView(this).apply {
            text = "↻"
            textSize = 22f
            setTextColor(Color.parseColor("#28D7B8"))
            setPadding(16, 8, 16, 8)
            setOnClickListener { webView.reload() }
        }

        topBar.addView(btnClose)
        topBar.addView(tvTitle)
        topBar.addView(btnRefresh)

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8)
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = ClaimEngine.USER_AGENT
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
                }
            }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // الشريط السفلي لحفظ الحساب
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 18, 28, 24)
            setBackgroundColor(Color.parseColor("#162228"))
        }

        val tvHint = TextView(this).apply {
            text = "أكمل تسجيل الدخول في الصفحة، ثم اضغط «حفظ الحساب»\nجلسة متصفح نظيفة لهذا الحساب فقط"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 14)
        }

        val btnSave = Button(this).apply {
            text = "حفظ الحساب"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#082026"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#28D7B8"))
                cornerRadius = 24f
            }
            setOnClickListener { saveAccountSession(displayTitle) }
        }

        bottomBar.addView(tvHint)
        bottomBar.addView(btnSave)

        root.addView(topBar)
        root.addView(progressBar)
        root.addView(webView)
        root.addView(bottomBar)
        setContentView(root)

        // تنظيف الجلسة قبل فتح صفحة تسجيل الدخول
        WebStorage.getInstance().deleteAllData()
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.removeAllCookies {
            cm.flush()
            runOnUiThread {
                webView.loadUrl("https://accounts.google.com/ServiceLogin?continue=https://play.google.com/store/points/perks")
            }
        }
    }

    private fun saveAccountSession(defaultName: String) {
        val cookiesJson = AccountStore.captureCurrentCookiesJson()
        val account = editingAccount?.apply {
            this.cookiesJson = cookiesJson
        } ?: AccountItem(
            name = defaultName,
            cookiesJson = cookiesJson
        )

        AccountStore.addOrUpdate(this, account)
        MrPlayWidget.updateAll(this)
        Toast.makeText(this, "تم حفظ الحساب «${account.name}» بنجاح ✓", Toast.LENGTH_LONG).show()
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
