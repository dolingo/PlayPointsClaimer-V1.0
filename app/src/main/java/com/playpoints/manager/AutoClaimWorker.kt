package com.playpoints.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

class AutoClaimWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!AccountStore.isAutoClaimEnabled(applicationContext)) {
            return Result.success()
        }

        val pending = AccountStore.getAccounts(applicationContext)
            .filter { !it.isClaimedThisWeek() && it.hasValidSession() }

        if (pending.isEmpty()) return Result.success()

        var claimedCount = 0
        var totalPoints = 0

        for ((index, acc) in pending.withIndex()) {
            val res = ClaimEngine.claimAccount(applicationContext, acc)
            if (res.success && !res.alreadyClaimed) {
                claimedCount++
                totalPoints += res.pointsCount
            }
            if (index < pending.lastIndex) {
                delay(10_000)
            }
        }

        MrPlayWidget.updateAll(applicationContext)

        if (claimedCount > 0) {
            showNotification(
                "تم الاستلام التلقائي ✓",
                "تم استلام $claimedCount حساب بمجموع +$totalPoints نقطة"
            )
        }
        return Result.success()
    }

    private fun showNotification(title: String, body: String) {
        val channelId = "play_points_auto_claim"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "الاستلام التلقائي", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notif = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        nm.notify(1001, notif)
    }

    companion object {
        private const val WORK_NAME = "friday_auto_claim_worker"

        fun syncSchedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<AutoClaimWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
