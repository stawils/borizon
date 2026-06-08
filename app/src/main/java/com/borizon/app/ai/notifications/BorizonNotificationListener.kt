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

        // Never capture our own notifications
        if (sbn.packageName == packageName) return

        val pkg = sbn.packageName

        // Drop entirely — these are captured by dedicated tools (readSms, communicate)
        if (pkg in BLOCKED_PACKAGES) return

        // Apply privacy filter
        val sensitivity = classifySensitivity(pkg, title, text)
        if (sensitivity == Sensitivity.BLOCK) return

        val storedText = when (sensitivity) {
            Sensitivity.HIDDEN -> "[content hidden — sensitive app]"
            Sensitivity.REDACTED -> redactSensitiveContent(text)
            Sensitivity.VISIBLE -> text.take(500)
            Sensitivity.BLOCK -> return // unreachable but exhausts when
        }

        val entry = com.borizon.app.data.models.NotificationEntry(
            packageName = pkg,
            title = title,
            text = storedText,
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

    /**
     * Sensitivity classification for notification content.
     *
     * Decision flow (evaluated in order, first match wins):
     *
     *   1. BLOCKED_PACKAGES   → BLOCK   (SMS/dialer — captured by dedicated tools)
     *   2. SENSITIVE_PACKAGES → HIDDEN  (known sensitive apps — title kept, text replaced)
     *   3. Keyword scan       → REDACTED (OTP/auth codes redacted from otherwise visible content)
     *   4. Default            → VISIBLE (full content stored)
     *
     * Known gaps (documented for transparency):
     *   - Banking apps not in SENSITIVE_PACKAGES will be VISIBLE unless their
     *     notifications contain keywords matching SENSITIVE_KEYWORDS. Regional banks,
     *     neobanks, and fintech apps are likely missing from the list.
     *   - The keyword scan catches common patterns (OTP, verification code, etc.)
     *     but cannot detect all sensitive content formats.
     *   - Android notification channels: we don't use channel IDs for classification
     *     because they're unreliable across OEMs and app versions.
     *
     * This list should be audited periodically. Additions welcome via PR.
     */
    enum class Sensitivity {
        BLOCK,     // Drop entirely (captured by dedicated tools)
        HIDDEN,    // Store title only, replace text with placeholder
        REDACTED,  // Store with sensitive patterns redacted
        VISIBLE,   // Store full content
    }

    /**
     * Classify a notification's sensitivity based on package name and content.
     */
    fun classifySensitivity(pkg: String, title: String, text: String): Sensitivity {
        if (pkg in BLOCKED_PACKAGES) return Sensitivity.BLOCK
        if (pkg in SENSITIVE_PACKAGES) return Sensitivity.HIDDEN
        if (containsSensitiveKeywords(text)) return Sensitivity.REDACTED
        return Sensitivity.VISIBLE
    }

    /**
     * Detect sensitive content patterns in notification text.
     *
     * Catches: OTP codes, verification codes, authentication tokens,
     * banking transaction details, and common 2FA phrases.
     *
     * Regex-based — language-agnostic patterns that work across English and Arabic.
     */
    private fun containsSensitiveKeywords(text: String): Boolean {
        val lower = text.lowercase()
        return SENSITIVE_PATTERNS.any { it.containsMatchIn(lower) }
    }

    /**
     * Redact sensitive content from notification text.
     * Replaces detected sensitive patterns with [REDACTED] while
     * preserving the rest of the notification for context.
     */
    private fun redactSensitiveContent(text: String): String {
        var result = text
        for (pattern in SENSITIVE_PATTERNS) {
            result = pattern.replace(result) { match ->
                "${match.value.take(10)}[REDACTED]"
            }
        }
        return result.take(500)
    }

    companion object {
        private const val TAG = "BorizonNotification"
        private const val TTL_MS = 24L * 60 * 60 * 1000
        private const val PURGE_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes

        // === TIER 1: Blocked packages ===
        // These are captured by dedicated tools (readSms, communicate)
        // and should never appear in the notification history.
        private val BLOCKED_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.google.android.dialer",
            "com.android.phone",
            "com.android.providers.telephony",
        )

        // === TIER 2: Sensitive packages ===
        // Known apps where ALL notification content is considered sensitive.
        // Title is preserved (useful for context), text is replaced with placeholder.
        //
        // Categories: banking/finance, health, encrypted messaging.
        // GAP: Regional banks, neobanks, and fintech apps may be missing.
        // If you're reading this and know of missing apps, add them.
        private val SENSITIVE_PACKAGES = setOf(
            // Banking & finance — US/EU banks
            "com.chase.sig.android",              // Chase
            "com.bankofamerica.banking",          // Bank of America
            "com.wellsfargo.mobile",              // Wells Fargo
            "com.citi.citimobile",                // Citi
            "com.usaa.mobile.android",            // USAA
            "com.capitalone.mobile",              // Capital One
            "com.amex.mobile",                    // Amex
            "com.discoverfinancial.mobile",       // Discover
            "com.bbt.mobile",                     // Truist (BB&T)
            "com.td.mobile",                      // TD Bank
            "com.pnc.mobile",                     // PNC
            // Banking & finance — Middle East
            "com.arabi.bank",                     // Arab Bank
            "eg.banks.alahli",                    // National Bank of Egypt
            "sa.boks.alrajhi",                    // Al Rajhi Bank
            "com.riyadhb ank",                    // Riyad Bank
            "com.sabb.mobile",                    // SABB
            "com.qnb.mobile",                     // QNB
            // Banking & finance — payments
            "com.google.android.apps.finance",    // Google Finance
            "com.westernunion.android",            // Western Union
            "com.paypal.android.p2pmobile",       // PayPal
            "com.venmo",                          // Venmo
            "com.squareup.cash",                  // Cash App
            "com.intuit.mint",                    // Mint
            // Health
            "com.google.android.apps.fitness",    // Google Fit
            "com.fitbit.FitbitMobile",            // Fitbit
            "com.underarmour.myfitnesspal",       // MyFitnessPal
            // Encrypted / private messaging
            "com.whatsapp",                       // WhatsApp
            "com.Slack",                          // Slack
            "com.discord",                        // Discord
            "org.telegram.messenger",             // Telegram
            "com.google.android.apps.dynamite",   // Google Chat
            "com.facebook.orca",                  // Messenger
            "org.thoughtcrime.securesms",          // Signal
            "com.threema.app",                    // Threema
        )

        // === TIER 3: Content-based sensitive patterns ===
        // Applied to all notifications NOT in SENSITIVE_PACKAGES.
        // Catches OTP/2FA/auth patterns from any app (e.g., food delivery sending
        // a login code, an unknown banking app sending transaction alerts).
        private val SENSITIVE_PATTERNS = listOf(
            // OTP / verification codes
            Regex("""(?i)\b(otp|one\s*time\s*password|verification\s*code|verify\s*code|login\s*code|security\s*code|auth(?:entication)?\s*code)\b"""),
            // Standalone 4-8 digit numbers (common OTP format) — only when preceded by
            // a code-like keyword within 30 chars, to avoid redacting random numbers
            Regex("""(?i)(?:code|رمز|كود|pin|passcode|رقم).{0,30}?\b\d{4,8}\b"""),
            // 2FA phrases
            Regex("""(?i)\b(two[- ]?factor|2fa|two[- ]?step|doble\s*factor|تسليم\s*مزدوج)\b"""),
            // Transaction keywords
            Regex("""(?i)\b(transaction|transfer|withdrawal|debit|credit|payment\s*(?:of|approved|declined|processed)|تحويل|دفع|سحب)\b"""),
            // Auth/password
            Regex("""(?i)\b(password|reset\s*(?:your)?\s*password|تغيير\s*كلمة\s*المرور|رمز\s*الدخول)\b"""),
        )
    }
}
