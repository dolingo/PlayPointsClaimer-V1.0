package com.playpoints.manager

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID

data class AccountItem(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var email: String = "",
    var category: String = "",
    var note: String = "",
    var timezone: String = "America/Los_Angeles",
    var cookiesJson: String = "",
    var lastClaimedWeek: String = "",
    var lastPointsText: String = ""
) {
    fun hasValidSession(): Boolean {
        return cookiesJson.contains("SID=") ||
            cookiesJson.contains("__Secure-1PSID=") ||
            cookiesJson.contains("__Secure-3PSID=")
    }

    fun isClaimedThisWeek(): Boolean {
        return lastClaimedWeek.isNotBlank() && lastClaimedWeek == AccountStore.currentWeekKey(timezone)
    }
}

object AccountStore {
    private const val PREFS_NAME = "play_points_local_store"
    private const val KEY_ACCOUNTS = "accounts_json"
    private const val KEY_AUTO_CLAIM = "friday_auto_claim"

    val COOKIE_URLS = listOf(
        "https://play.google.com",
        "https://accounts.google.com",
        "https://www.google.com",
        "https://google.com"
    )

    val TIMEZONES = listOf(
        "أمريكا — المحيط الهادئ" to "America/Los_Angeles",
        "أمريكا — الشرق" to "America/New_York",
        "بريطانيا — لندن" to "Europe/London",
        "اليابان — طوكيو" to "Asia/Tokyo",
        "التوقيت العالمي (UTC)" to "UTC"
    )

    fun getAccounts(context: Context): MutableList<AccountItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ACCOUNTS, "[]") ?: "[]"
        val list = mutableListOf<AccountItem>()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                AccountItem(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", "A${i + 1}"),
                    email = o.optString("email", ""),
                    category = o.optString("category", ""),
                    note = o.optString("note", ""),
                    timezone = o.optString("timezone", "America/Los_Angeles"),
                    cookiesJson = o.optString("cookiesJson", ""),
                    lastClaimedWeek = o.optString("lastClaimedWeek", ""),
                    lastPointsText = o.optString("lastPointsText", "")
                )
            )
        }
        return list
    }

    fun saveAccounts(context: Context, list: List<AccountItem>) {
        val arr = JSONArray()
        for (item in list) {
            val o = JSONObject()
            o.put("id", item.id)
            o.put("name", item.name)
            o.put("email", item.email)
            o.put("category", item.category)
            o.put("note", item.note)
            o.put("timezone", item.timezone)
            o.put("cookiesJson", item.cookiesJson)
            o.put("lastClaimedWeek", item.lastClaimedWeek)
            o.put("lastPointsText", item.lastPointsText)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCOUNTS, arr.toString())
            .apply()
    }

    fun addOrUpdate(context: Context, account: AccountItem) {
        val list = getAccounts(context)
        val idx = list.indexOfFirst { it.id == account.id }
        if (idx >= 0) list[idx] = account else list.add(account)
        saveAccounts(context, list)
    }

    fun delete(context: Context, id: String) {
        val list = getAccounts(context).filter { it.id != id }
        saveAccounts(context, list)
    }

    fun nextDefaultName(context: Context): String {
        return "A${getAccounts(context).size + 1}"
    }

    fun isAutoClaimEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CLAIM, false)
    }

    fun setAutoClaimEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CLAIM, enabled)
            .apply()
    }

    fun captureCurrentCookiesJson(): String {
        val cm = CookieManager.getInstance()
        cm.flush()
        val obj = JSONObject()
        for (url in COOKIE_URLS) {
            val c = cm.getCookie(url)
            if (!c.isNullOrBlank()) {
                obj.put(url, c)
            }
        }
        return obj.toString()
    }

    fun restoreCookies(cookiesJson: String, onReady: () -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.removeAllCookies {
            try {
                if (cookiesJson.isNotBlank()) {
                    val obj = JSONObject(cookiesJson)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val url = keys.next()
                        val rawCookies = obj.optString(url, "")
                        rawCookies.split(";").forEach { part ->
                            val trimmed = part.trim()
                            if (trimmed.isNotEmpty()) {
                                cm.setCookie(url, trimmed)
                            }
                        }
                    }
                }
                cm.flush()
            } catch (_: Exception) {
            }
            mainHandler.post(onReady)
        }
    }

    fun currentWeekKey(zoneIdStr: String = "America/Los_Angeles"): String {
        val zone = runCatching { ZoneId.of(zoneIdStr) }.getOrDefault(ZoneId.of("America/Los_Angeles"))
        val now = ZonedDateTime.now(zone)
        var lastFriday = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY))
            .withHour(9).withMinute(0).withSecond(0).withNano(0)
        if (now.isBefore(lastFriday)) {
            lastFriday = lastFriday.minusWeeks(1)
        }
        return lastFriday.toLocalDate().toString()
    }

    fun nextFridayReset(zoneIdStr: String = "America/Los_Angeles"): ZonedDateTime {
        val zone = runCatching { ZoneId.of(zoneIdStr) }.getOrDefault(ZoneId.of("America/Los_Angeles"))
        val now = ZonedDateTime.now(zone)
        var nextFri = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
            .withHour(9).withMinute(0).withSecond(0).withNano(0)
        if (!now.isBefore(nextFri)) {
            nextFri = nextFri.plusWeeks(1)
        }
        return nextFri
    }

    fun nextResetShortLabel(zoneIdStr: String = "America/Los_Angeles"): String {
        val nextFri = nextFridayReset(zoneIdStr)
        return nextFri.format(DateTimeFormatter.ofPattern("EEE MM-dd HH:mm", Locale.US))
    }

    fun countdownText(zoneIdStr: String = "America/Los_Angeles"): String {
        val nextFri = nextFridayReset(zoneIdStr)
        val dur = Duration.between(ZonedDateTime.now(nextFri.zone), nextFri)
        val totalSec = dur.seconds.coerceAtLeast(0)
        val days = totalSec / 86400
        val hours = (totalSec % 86400) / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return String.format(Locale.US, "%d يوم %02d:%02d:%02d", days, hours, minutes, seconds)
    }
}
