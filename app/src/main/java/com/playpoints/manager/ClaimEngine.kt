package com.playpoints.manager

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class ClaimResult(
    val success: Boolean,
    val alreadyClaimed: Boolean = false,
    val sessionExpired: Boolean = false,
    val pointsCount: Int = 0,
    val message: String
)

object ClaimEngine {
    const val PERKS_URL = "https://play.google.com/store/points/perks"
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; SM-A155F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun claimAccount(context: Context, account: AccountItem): ClaimResult =
        withContext(Dispatchers.Main) {
            if (!account.hasValidSession()) {
                return@withContext ClaimResult(
                    success = false,
                    sessionExpired = true,
                    message = "الجلسة غير محفوظة، يرجى إعادة تسجيل الدخول"
                )
            }

            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                val handler = Handler(Looper.getMainLooper())
                var webView: WebView? = null

                fun finish(result: ClaimResult) {
                    if (done.compareAndSet(false, true)) {
                        handler.removeCallbacksAndMessages(null)
                        try {
                            webView?.stopLoading()
                            webView?.destroy()
                        } catch (_: Exception) {
                        }
                        if (cont.isActive) cont.resume(result)
                    }
                }

                cont.invokeOnCancellation {
                    handler.post {
                        handler.removeCallbacksAndMessages(null)
                        try {
                            webView?.stopLoading()
                            webView?.destroy()
                        } catch (_: Exception) {
                        }
                    }
                }

                AccountStore.restoreCookies(account.cookiesJson) {
                    val wv = WebView(context.applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.userAgentString = USER_AGENT
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                    webView = wv

                    var claimStepStarted = false

                    // مهلة أمان 28 ثانية حتى لا يتوقف التطبيق
                    handler.postDelayed({
                        finish(ClaimResult(false, message = "انتهت المهلة، تأكد من الاتصال بالإنترنت"))
                    }, 28_000)

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (url == null || done.get()) return

                            if (url.contains("accounts.google.com/v3/signin") ||
                                url.contains("accounts.google.com/ServiceLogin")
                            ) {
                                finish(
                                    ClaimResult(
                                        success = false,
                                        sessionExpired = true,
                                        message = "انتهت الجلسة، يرجى إعادة تسجيل الدخول"
                                    )
                                )
                                return
                            }

                            if (url.contains("play.google.com/store/points") && !claimStepStarted) {
                                claimStepStarted = true
                                handler.postDelayed({
                                    val jsClickClaim = """
                                        (function() {
                                            var btns = Array.from(document.querySelectorAll('button, [role="button"]'));
                                            var claimBtn = btns.find(function(b) {
                                                var t = (b.innerText || b.textContent || '').trim().toLowerCase();
                                                return (t === 'claim' || t === 'مطالبة' || t.indexOf('claim') === 0 || t.indexOf('مطالبة') === 0) && !b.disabled;
                                            });
                                            if (claimBtn) {
                                                claimBtn.click();
                                                return 'CLICKED_CLAIM';
                                            }
                                            var txt = (document.body.innerText || '').toLowerCase();
                                            if (txt.indexOf('available on') !== -1 || txt.indexOf('تتاح المكافأة') !== -1 || txt.indexOf('claimed') !== -1) {
                                                return 'ALREADY_CLAIMED';
                                            }
                                            return 'NOT_FOUND';
                                        })();
                                    """.trimIndent()

                                    wv.evaluateJavascript(jsClickClaim) { rawStatus ->
                                        val status = rawStatus.replace("\"", "")
                                        when (status) {
                                            "CLICKED_CLAIM" -> {
                                                // النقر على صندوق الجائزة إن ظهر ثم قراءة النقاط
                                                handler.postDelayed({
                                                    val jsOpenBox = """
                                                        (function() {
                                                            var btns = Array.from(document.querySelectorAll('button, [role="button"]'));
                                                            var openBtn = btns.find(function(b) {
                                                                var t = (b.innerText || '').trim().toLowerCase();
                                                                return t.indexOf('open') !== -1 || t.indexOf('فتح') !== -1 || t.indexOf('tap') !== -1 || t.indexOf('انقر') !== -1;
                                                            });
                                                            if (openBtn) openBtn.click();
                                                        })();
                                                    """.trimIndent()
                                                    wv.evaluateJavascript(jsOpenBox, null)

                                                    handler.postDelayed({
                                                        val jsReadPoints = """
                                                            (function() {
                                                                var t = document.body.innerText || '';
                                                                var m = t.match(/(\+?\d+)\s*(points|point|نقطة|نقاط)/i);
                                                                return m ? m[1].replace('+', '') : '0';
                                                            })();
                                                        """.trimIndent()
                                                        wv.evaluateJavascript(jsReadPoints) { rawPts ->
                                                            val pts = rawPts.replace("\"", "").toIntOrNull() ?: 0
                                                            // تحديث الكوكيز المحفوظة لضمان بقاء الجلسة نشطة
                                                            val freshCookies = AccountStore.captureCurrentCookiesJson()
                                                            if (freshCookies.contains("SID=")) {
                                                                account.cookiesJson = freshCookies
                                                            }
                                                            account.lastClaimedWeek = AccountStore.currentWeekKey(account.timezone)
                                                            account.lastPointsText = if (pts > 0) "+$pts نقاط" else "تم الاستلام"
                                                            AccountStore.addOrUpdate(context, account)

                                                            val msg = if (pts > 0) "حصلت على $pts نقاط" else "تم استلام الجائزة بنجاح"
                                                            finish(ClaimResult(success = true, pointsCount = pts, message = msg))
                                                        }
                                                    }, 4000)
                                                }, 2500)
                                            }
                                            "ALREADY_CLAIMED" -> {
                                                account.lastClaimedWeek = AccountStore.currentWeekKey(account.timezone)
                                                AccountStore.addOrUpdate(context, account)
                                                finish(
                                                    ClaimResult(
                                                        success = true,
                                                        alreadyClaimed = true,
                                                        message = "تم استلام جائزة هذا الأسبوع مسبقاً"
                                                    )
                                                )
                                            }
                                            else -> {
                                                finish(
                                                    ClaimResult(
                                                        success = false,
                                                        message = "زر المطالبة غير متاح حالياً لهذا الحساب"
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }, 3500)
                            }
                        }
                    }

                    wv.loadUrl(PERKS_URL)
                }
            }
        }
}
