package com.borizon.app.ai.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.borizon.app.util.debugLog
import com.borizon.app.data.database.BorizonDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BorizonNotificationListener : NotificationListenerService() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var notificationDao: com.borizon.app.data.dao.NotificationDao? = null

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString()?.takeIf { it.isNotBlank() } ?: return
        val text = extras.getCharSequence("android.text")?.toString()?.takeIf { it.isNotBlank() } ?: return

        if (sbn.packageName == packageName) return

        val pkg = sbn.packageName
        if (pkg in BLOCKED_PACKAGES) return

        val entry = com.borizon.app.data.models.NotificationEntry(
            packageName = pkg,
            title = title,
            text = if (pkg in SENSITIVE_PACKAGES) "[content hidden]" else text.take(500),
            timestamp = sbn.postTime,
        )

        scope.launch {
            try {
                val dao = notificationDao ?: BorizonDatabase.getDatabase(applicationContext).notificationDao().also { notificationDao = it }
                dao.insert(entry)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to store notification", e)
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Create fresh scope (old one may be cancelled from disconnect)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        notificationDao = BorizonDatabase.getDatabase(applicationContext).notificationDao()
        scope.launch {
            while (true) {
                delay(PURGE_INTERVAL_MS)
                try {
                    notificationDao?.deleteOlderThan(System.currentTimeMillis() - TTL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Purge failed", e)
                }
            }
        }
    }

    override fun onListenerDisconnected() {
        debugLog(TAG, "Notification listener disconnected")
        scope.cancel()
        notificationDao = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BorizonNotification"
        private const val TTL_MS = 24L * 60 * 60 * 1000
        private const val PURGE_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes

        private val BLOCKED_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.google.android.dialer",
            "com.android.phone",
            "com.android.providers.telephony",
        )

        private val SENSITIVE_PACKAGES = setOf(
            // Banking & finance
            "com.google.android.apps.finance",
            "com.banking",
            "com.westernunion.android",
            "com.paypal.android.p2pmobile",
            "com.venmo",
            "com.squareup.cash",
            "com.intuit.mint",
            "com.chase.sig.android",
            "com.bankofamerica.banking",
            "com.wellsfargo.mobile",
            "com.usaa.mobile.android",
            "com.citi.citimobile",
            "com.infonow.bofa",
            // Health
            "com.google.android.apps.fitness",
            "com.fitbit.FitbitMobile",
            "com.underarmour.myfitnesspal",
            // Messaging (hide content but still store)
            "com.whatsapp",
            "com.Slack",
            "com.discord",
            "org.telegram.messenger",
            "com.google.android.apps.dynamite",
            "com.facebook.orca",
        )
    }
}
