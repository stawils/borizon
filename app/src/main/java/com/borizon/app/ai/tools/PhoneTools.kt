package com.borizon.app.ai.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.text.format.DateUtils
import android.util.Log
import com.borizon.app.util.debugLog
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.borizon.app.util.escapeLike
import com.borizon.app.ai.harness.ToolResultCache

import kotlinx.coroutines.channels.Channel
import com.borizon.app.ai.tools.ToolCallTracker

class PhoneTools(
    private val context: Context,
    private val actionChannel: Channel<BorizonAction>,
) : ToolSet {

    companion object {
        private const val TAG = "PhoneTools"
    }

    /** Cached resolved contact number to avoid double-resolution in readCallLog. */
    @Volatile private var _lastContactResolve: String? = null

    private fun launchIntent(intent: Intent, toolType: ToolType, successLabel: String, errorLabel: String): Map<String, String> {
        return try {
            context.startActivity(intent)
            actionChannel.trySend(BorizonAction.Progress(label = successLabel, isInProgress = false, toolType = toolType))
            mapOf("result" to "succeeded")
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "$errorLabel: ${e.message}")
            actionChannel.trySend(BorizonAction.Progress(label = errorLabel, isInProgress = false, toolType = toolType))
            mapOf("result" to "error", "error" to "Activity not found: $errorLabel")
        } catch (e: SecurityException) {
            Log.e(TAG, "$errorLabel: permission denied", e)
            actionChannel.trySend(BorizonAction.Progress(label = errorLabel, isInProgress = false, toolType = toolType))
            mapOf("result" to "error", "error" to "Permission denied: $errorLabel")
        } catch (e: Exception) {
            Log.e(TAG, "$errorLabel", e)
            actionChannel.trySend(BorizonAction.Progress(label = errorLabel, isInProgress = false, toolType = toolType))
            mapOf("result" to "error", "error" to "${e.message ?: errorLabel}")
        }
    }

    private fun validatePhone(phoneNumber: String): Boolean =
        phoneNumber.matches(Regex("^\\+?[\\d\\-\\s()]{5,20}$"))

    private fun validateEmail(to: String): Boolean =
        to.matches(Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$"))

    private fun hasSmsPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasCallLogPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun resolveContactNumber(name: String): String? {
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
                arrayOf("%${name.escapeLike()}%"),
                null,
            )?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        } catch (_: Exception) {}
        return null
    }

    @Tool(description = "Set alarms or timers.")
    fun setTimeAction(
        @ToolParam(description = "alarm or timer") action: String,
        @ToolParam(description = "Hour 0-23") hour: Int = -1,
        @ToolParam(description = "Minute 0-59") minute: Int = -1,
        @ToolParam(description = "Seconds (timer)") seconds: Int = -1,
        @ToolParam(description = "Label") message: String = "",
        @ToolParam(description = "'yyyy-MM-dd HH:mm'") datetime: String = "",
    ): Map<String, String> {
        ToolCallTracker.increment()
        return when (action.lowercase().trim()) {
            "alarm" -> {
                if (hour < 0 || minute < 0) return mapOf("result" to "error", "error" to "alarm action requires hour and minute parameters")
                actionChannel.trySend(BorizonAction.Progress(
                    label = "Setting alarm for $hour:${minute.toString().padStart(2, '0')}",
                    isInProgress = true,
                    toolType = ToolType.SET_ALARM,
                    detailDescription = message.ifBlank { "Alarm at $hour:${minute.toString().padStart(2, '0')}" },
                ))
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    if (message.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launchIntent(intent, ToolType.SET_ALARM, "Alarm set", "Alarm not supported")
            }
            "timer" -> {
                if (seconds < 0) return mapOf("result" to "error", "error" to "timer action requires seconds parameter")
                actionChannel.trySend(BorizonAction.Progress(
                    label = "Setting timer for ${seconds}s",
                    isInProgress = true,
                    toolType = ToolType.SET_ALARM,
                    detailDescription = message.ifBlank { "Timer: ${seconds}s" },
                ))
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    if (message.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launchIntent(intent, ToolType.SET_ALARM, "Timer set", "Timer not supported")
            }
            "reminder" -> {
                if (message.isBlank()) return mapOf("result" to "error", "error" to "reminder action requires a message (title)")
                actionChannel.trySend(BorizonAction.Progress(
                    label = "Creating reminder",
                    isInProgress = true,
                    toolType = ToolType.CREATE_REMINDER,
                    detailDescription = message,
                ))
                val secondsUntil = try {
                    val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    val target = format.parse(datetime)?.time
                    if (target == null) 300L else ((target - System.currentTimeMillis()) / 1000).coerceAtLeast(60)
                } catch (_: Exception) { 300L }
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_MESSAGE, message)
                    putExtra(AlarmClock.EXTRA_LENGTH, secondsUntil)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launchIntent(intent, ToolType.CREATE_REMINDER, "Reminder created", "Reminder not supported")
            }
            else -> mapOf("result" to "error", "error" to "Unknown action '$action'. Valid actions: alarm, timer, reminder")
        }
    }

    @Tool(description = "Call, SMS, or email.")
    fun communicate(
        @ToolParam(description = "call, sms, or email") action: String,
        @ToolParam(description = "Phone or email") address: String,
        @ToolParam(description = "Message body") body: String = "",
        @ToolParam(description = "Email subject") subject: String = "",
    ): Map<String, String> {
        ToolCallTracker.increment()
        return when (action.lowercase().trim()) {
            "call" -> {
                if (!validatePhone(address)) return mapOf("result" to "error", "error" to "Invalid phone number format: $address")
                actionChannel.trySend(BorizonAction.Progress(label = "Opening dialer", isInProgress = true, toolType = ToolType.PHONE_CALL, detailDescription = address))
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$address")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launchIntent(intent, ToolType.PHONE_CALL, "Dialer opened", "Phone not available")
            }
            "sms" -> {
                if (!validatePhone(address)) return mapOf("result" to "error", "error" to "Invalid phone number format: $address")
                actionChannel.trySend(BorizonAction.Progress(label = "Opening messages", isInProgress = true, toolType = ToolType.SEND_SMS, detailDescription = "To: $address"))
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$address")).apply {
                    putExtra("sms_body", body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launchIntent(intent, ToolType.SEND_SMS, "Messages opened", "Messaging not available")
            }
            "email" -> {
                if (!validateEmail(address)) return mapOf("result" to "error", "error" to "Invalid email address: $address")
                actionChannel.trySend(BorizonAction.Progress(label = "Opening email", isInProgress = true, toolType = ToolType.SEND_EMAIL, detailDescription = "To: $address"))
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launchIntent(intent, ToolType.SEND_EMAIL, "Email client opened", "No email client available")
            }
            else -> mapOf("result" to "error", "error" to "Unknown action '$action'. Valid actions: call, sms, email")
        }
    }

    @Tool(description = "Search or create contacts.")
    fun manageContacts(
        @ToolParam(description = "search or create") action: String,
        @ToolParam(description = "Name to search or create") query: String = "",
        @ToolParam(description = "Last name") lastName: String = "",
        @ToolParam(description = "Phone number") phoneNumber: String = "",
        @ToolParam(description = "Email") email: String = "",
    ): Map<String, String> {
        ToolCallTracker.increment()
        return when (action.lowercase().trim()) {
            "search" -> {
                if (query.isBlank()) return mapOf("result" to "error", "error" to "search action requires a query (name to search for)")
                actionChannel.trySend(BorizonAction.Progress(label = "Searching contacts", isInProgress = true, toolType = ToolType.READ_CONTACTS, detailDescription = query))
                if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    actionChannel.trySend(BorizonAction.Progress(label = "Contacts permission denied", isInProgress = false, toolType = ToolType.READ_CONTACTS))
                    return mapOf("result" to "error", "error" to "Contacts permission not granted. Ask the user to enable it in Settings > Permissions > Contacts.")
                }
                val contacts = mutableListOf<String>()
                try {
                    context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.TYPE,
                        ),
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
                        arrayOf("%${query.escapeLike()}%"),
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
                    )?.use { cursor ->
                        val seen = mutableSetOf<String>()
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(0) ?: continue
                            val number = cursor.getString(1) ?: continue
                            val key = "$name:$number"
                            if (seen.add(key)) {
                                contacts.add("$name: $number")
                            }
                        }
                    }
                } catch (e: SecurityException) {
                    actionChannel.trySend(BorizonAction.Progress(label = "Contacts permission denied", isInProgress = false, toolType = ToolType.READ_CONTACTS))
                    return mapOf("result" to "error", "error" to "Contacts permission not granted")
                } catch (e: Exception) {
                    Log.e(TAG, "Contacts query failed", e)
                    actionChannel.trySend(BorizonAction.Progress(label = "Contacts read failed", isInProgress = false, toolType = ToolType.READ_CONTACTS))
                    return mapOf("result" to "error", "error" to "Failed to read contacts: ${e.message}")
                }
                actionChannel.trySend(BorizonAction.Progress(label = "Found ${contacts.size} contacts", isInProgress = false, toolType = ToolType.READ_CONTACTS))
                val contactList = contacts.take(10).joinToString("; ")
                ToolResultCache.put("contacts", contactList.take(300))
                mapOf("result" to "success", "count" to contacts.size.toString(), "contacts" to contactList)
            }
            "create" -> {
                if (query.isBlank()) return mapOf("result" to "error", "error" to "create action requires a first name (query parameter)")
                actionChannel.trySend(BorizonAction.Progress(label = "Creating contact", isInProgress = true, toolType = ToolType.CREATE_CONTACT, detailDescription = "$query $lastName"))
                val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                    type = ContactsContract.RawContacts.CONTENT_TYPE
                    putExtra(ContactsContract.Intents.Insert.NAME, "$query $lastName")
                    putExtra(ContactsContract.Intents.Insert.PHONE, phoneNumber)
                    putExtra(ContactsContract.Intents.Insert.PHONE_TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    putExtra(ContactsContract.Intents.Insert.EMAIL, email)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launchIntent(intent, ToolType.CREATE_CONTACT, "Contact editor opened", "Contacts not available")
            }
            else -> mapOf("result" to "error", "error" to "Unknown action '$action'. Valid actions: search, create")
        }
    }

    @Tool(description = "Open apps, settings, share, URLs.")
    fun appControl(
        @ToolParam(description = "open_app, open_settings, share_text, open_url") action: String,
        @ToolParam(description = "App, settings, text, or URL") target: String,
        @ToolParam(description = "Share title") title: String = "",
    ): Map<String, String> {
        ToolCallTracker.increment()
        return when (action.lowercase().trim()) {
            "open_app" -> {
                actionChannel.trySend(BorizonAction.Progress(label = "Opening app", isInProgress = true, toolType = ToolType.OPEN_APP, detailDescription = target))
                val pm = context.packageManager
                val cached = InstalledAppsTools.getCachedApps(pm)
                    .filter { it.packageName != context.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
                val match = cached.firstOrNull {
                    it.label.equals(target, ignoreCase = true)
                } ?: cached.firstOrNull {
                    it.label.contains(target, ignoreCase = true)
                } ?: cached.firstOrNull {
                    it.packageName.contains(target.lowercase().replace(" ", ""), ignoreCase = true)
                }
                if (match != null) {
                    val launchIntent = pm.getLaunchIntentForPackage(match.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (launchIntent != null) {
                        return launchIntent(launchIntent, ToolType.OPEN_APP, "${match.label} opened", "Could not open app")
                    }
                }
                actionChannel.trySend(BorizonAction.Progress(label = "App not found", isInProgress = false, toolType = ToolType.OPEN_APP))
                mapOf("result" to "error", "error" to "Could not find app '$target'")
            }
            "open_settings" -> {
                val settingsAction = when (target.lowercase().trim()) {
                    "wifi" -> Settings.ACTION_WIFI_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "display" -> Settings.ACTION_DISPLAY_SETTINGS
                    "sound" -> Settings.ACTION_SOUND_SETTINGS
                    "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                    "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                    "about" -> Settings.ACTION_DEVICE_INFO_SETTINGS
                    else -> Settings.ACTION_SETTINGS
                }
                actionChannel.trySend(BorizonAction.Progress(label = "Opening settings", isInProgress = true, toolType = ToolType.OPEN_SETTINGS, detailDescription = target))
                val intent = Intent(settingsAction).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                launchIntent(intent, ToolType.OPEN_SETTINGS, "Settings opened", "Could not open settings")
            }
            "share_text" -> {
                actionChannel.trySend(BorizonAction.Progress(label = "Sharing content", isInProgress = true, toolType = ToolType.SHARE_TEXT, detailDescription = title.ifBlank { target.take(50) }))
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, target)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(shareIntent, title.ifBlank { "Share" }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent(chooser, ToolType.SHARE_TEXT, "Shared", "Share failed")
            }
            "open_url" -> {
                val scheme = Uri.parse(target).scheme?.lowercase()
                if (scheme !in listOf("http", "https")) {
                    return mapOf("result" to "error", "error" to "Only http/https URLs are supported")
                }
                actionChannel.trySend(BorizonAction.Progress(label = "Opening URL", isInProgress = true, toolType = ToolType.OPEN_URL, detailDescription = target.take(80)))
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                launchIntent(intent, ToolType.OPEN_URL, "URL opened", "No browser available")
            }
            else -> mapOf("result" to "error", "error" to "Unknown action '$action'. Valid actions: open_app, open_settings, share_text, open_url")
        }
    }



    @Tool(description = "Read or create calendar events.")
    fun calendarAction(
        @ToolParam(description = "read or create") action: String,
        @ToolParam(description = "Days ahead (default 7)") days: Int = 7,
        @ToolParam(description = "Event title") title: String = "",
        @ToolParam(description = "'yyyy-MM-dd HH:mm'") startTime: String = "",
    ): Map<String, String> {
        ToolCallTracker.increment()
        return when (action.lowercase().trim()) {
            "read" -> {
                actionChannel.trySend(BorizonAction.Progress(label = "Reading calendar", isInProgress = true, toolType = ToolType.OPEN_CALENDAR, detailDescription = "Next $days days"))
                if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "Calendar permission not granted")
                    actionChannel.trySend(BorizonAction.Progress(label = "Calendar permission denied", isInProgress = false, toolType = ToolType.OPEN_CALENDAR))
                    return mapOf("result" to "error", "error" to "Calendar permission not granted. Ask the user to enable it in Settings > Permissions > Calendar.")
                }
                val now = System.currentTimeMillis()
                val safeDays = days.coerceIn(1, 365)
                val endTime = now + safeDays.toLong() * DateUtils.DAY_IN_MILLIS
                val sdf = java.text.SimpleDateFormat("EEE, MMM d 'at' h:mm a", java.util.Locale.US)
                val events = mutableListOf<String>()
                try {
                    val instanceProjection = arrayOf(
                        CalendarContract.Instances.TITLE,
                        CalendarContract.Instances.BEGIN,
                        CalendarContract.Instances.END,
                        CalendarContract.Instances.DESCRIPTION,
                        CalendarContract.Instances.EVENT_LOCATION,
                    )
                    val instanceUri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                        .appendPath(now.toString())
                        .appendPath(endTime.toString())
                        .build()
                    context.contentResolver.query(instanceUri, instanceProjection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
                        debugLog(TAG, "Calendar instances query returned ${cursor.count} rows")
                        val seen = mutableSetOf<String>()
                        while (cursor.moveToNext()) {
                            val evTitle = cursor.getString(0) ?: continue
                            val dtStart = cursor.getLong(1)
                            val dtEnd = cursor.getLong(2)
                            val location = cursor.getString(4) ?: ""
                            val loc = if (location.isNotBlank()) " at $location" else ""
                            val end = if (dtEnd > 0) " - ${sdf.format(java.util.Date(dtEnd))}" else ""
                            val key = "$evTitle$dtStart"
                            if (seen.add(key)) {
                                events.add("$evTitle: ${sdf.format(java.util.Date(dtStart))}$end$loc")
                            }
                        }
                    }
                    if (events.isEmpty()) {
                        debugLog(TAG, "Instances empty, falling back to Events table")
                        val eventsProjection = arrayOf(
                            CalendarContract.Events.TITLE,
                            CalendarContract.Events.DTSTART,
                            CalendarContract.Events.DTEND,
                            CalendarContract.Events.DESCRIPTION,
                            CalendarContract.Events.EVENT_LOCATION,
                        )
                        context.contentResolver.query(
                            CalendarContract.Events.CONTENT_URI,
                            eventsProjection,
                            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                            arrayOf(now.toString(), endTime.toString()),
                            "${CalendarContract.Events.DTSTART} ASC",
                        )?.use { cursor ->
                            debugLog(TAG, "Calendar events fallback query returned ${cursor.count} rows")
                            while (cursor.moveToNext()) {
                                val evTitle = cursor.getString(0) ?: continue
                                val dtStart = cursor.getLong(1)
                                val dtEnd = cursor.getLong(2)
                                val location = cursor.getString(4) ?: ""
                                val loc = if (location.isNotBlank()) " at $location" else ""
                                val end = if (dtEnd > 0) " - ${sdf.format(java.util.Date(dtEnd))}" else ""
                                events.add("$evTitle: ${sdf.format(java.util.Date(dtStart))}$end$loc")
                            }
                        }
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Calendar security exception", e)
                    actionChannel.trySend(BorizonAction.Progress(label = "Calendar permission denied", isInProgress = false, toolType = ToolType.OPEN_CALENDAR))
                    return mapOf("result" to "error", "error" to "Calendar permission not granted")
                } catch (e: Exception) {
                    Log.e(TAG, "Calendar query failed", e)
                    actionChannel.trySend(BorizonAction.Progress(label = "Calendar read failed", isInProgress = false, toolType = ToolType.OPEN_CALENDAR))
                    return mapOf("result" to "error", "error" to "Failed to read calendar: ${e.message}")
                }
                actionChannel.trySend(BorizonAction.Progress(label = "Found ${events.size} events", isInProgress = false, toolType = ToolType.OPEN_CALENDAR, detailDescription = "Next $days days"))
                val eventList = events.take(10).joinToString("; ")
                ToolResultCache.put("calendar", eventList.take(300))
                mapOf("result" to "success", "count" to events.size.toString(), "events" to eventList)
            }
            "create" -> {
                if (title.isBlank()) return mapOf("result" to "error", "error" to "create action requires a title")
                actionChannel.trySend(BorizonAction.Progress(label = "Opening calendar", isInProgress = true, toolType = ToolType.OPEN_CALENDAR, detailDescription = title))
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, title)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launchIntent(intent, ToolType.OPEN_CALENDAR, "Calendar opened", "Calendar not available")
            }
            else -> mapOf("result" to "error", "error" to "Unknown action '$action'. Valid actions: read, create")
        }
    }

    @Tool(description = "Read SMS or call history.")
    fun readSms(
        @ToolParam(description = "search, thread, or calls") action: String,
        @ToolParam(description = "Keyword or contact name") query: String = "",
        @ToolParam(description = "Max 1-30 results") limit: Int = 10,
        @ToolParam(description = "Call filter: incoming, outgoing, missed, all") typeFilter: String = "all",
    ): Map<String, String> {
        ToolCallTracker.increment()
        val actualLimit = limit.coerceIn(1, 30)
        val toolType = when (action) {
            "thread" -> ToolType.SMS_CONVERSATION
            "calls" -> ToolType.CALL_LOG_READ
            else -> ToolType.SMS_READ
        }

        return try {
            actionChannel.trySend(BorizonAction.Progress(label = "Reading messages", isInProgress = true, toolType = toolType))
            val results = mutableListOf<String>()

            when (action) {
                "calls" -> {
                    if (!hasCallLogPermission()) {
                        return mapOf("result" to "error", "error" to "Call log permission not granted.")
                    }
                    val typeWhere = when (typeFilter) {
                        "incoming" -> " AND ${CallLog.Calls.TYPE} = ${CallLog.Calls.INCOMING_TYPE}"
                        "outgoing" -> " AND ${CallLog.Calls.TYPE} = ${CallLog.Calls.OUTGOING_TYPE}"
                        "missed" -> " AND ${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE}"
                        else -> ""
                    }
                    val selection = if (query.isNotBlank()) {
                        val resolved = resolveContactNumber(query)
                            ?: query.takeIf { it.matches(Regex("^\\+?[\\d\\-\\s]{5,20}$")) }
                            ?: return mapOf("result" to "not_found", "message" to "Contact '$query' not found")
                        _lastContactResolve = resolved
                        "${CallLog.Calls.NUMBER} LIKE ? ESCAPE '\\' $typeWhere"
                    } else "1=1$typeWhere"
                    val selectionArgs = if (query.isNotBlank()) {
                        _lastContactResolve?.let { arrayOf("%${it.replace(Regex("[^\\d]"), "").escapeLike()}%") }
                    } else null
                    context.contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME),
                        selection,
                        selectionArgs,
                        "${CallLog.Calls.DATE} DESC LIMIT $actualLimit",
                    )?.use { cursor ->
                        val dateFormat = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US)
                        while (cursor.moveToNext()) {
                            val number = cursor.getString(0)
                            val date = dateFormat.format(java.util.Date(cursor.getLong(1)))
                            val duration = cursor.getLong(2)
                            val type = when (cursor.getInt(3)) {
                                CallLog.Calls.INCOMING_TYPE -> "Incoming"
                                CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                                CallLog.Calls.MISSED_TYPE -> "Missed"
                                else -> "Unknown"
                            }
                            val cachedName = cursor.getString(4)
                            val name = cachedName ?: number
                            val durationStr = if (duration > 0) "${duration / 60}m ${duration % 60}s" else "no answer"
                            results.add("[$type] $name ($date, $durationStr)")
                        }
                    }
                }
                "thread" -> {
                    if (!hasSmsPermission()) {
                        return mapOf("result" to "error", "error" to "SMS permission not granted.")
                    }
                    val number = resolveContactNumber(query)
                        ?: query.takeIf { it.matches(Regex("^\\+?[\\d\\-\\s]{5,20}$")) }
                        ?: return mapOf("result" to "not_found", "message" to "Contact '$query' not found")
                    context.contentResolver.query(
                        Telephony.Sms.CONTENT_URI,
                        arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.ADDRESS),
                        "${Telephony.Sms.ADDRESS} = ?",
                        arrayOf(number),
                        "${Telephony.Sms.DATE} DESC LIMIT $actualLimit",
                    )?.use { cursor ->
                        val smsFormat = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US)
                        while (cursor.moveToNext()) {
                            val body = cursor.getString(0)
                            val date = smsFormat.format(java.util.Date(cursor.getLong(1)))
                            val type = if (cursor.getInt(2) == Telephony.Sms.MESSAGE_TYPE_INBOX) "From" else "To"
                            results.add("$type $number ($date): ${body.take(200)}")
                        }
                    }
                }
                else -> {
                    if (!hasSmsPermission()) {
                        return mapOf("result" to "error", "error" to "SMS permission not granted.")
                    }
                    val selection = if (query.isNotBlank()) "${Telephony.Sms.BODY} LIKE ? ESCAPE '\\'" else null
                    val selectionArgs = if (query.isNotBlank()) arrayOf("%${query.escapeLike()}%") else null
                    context.contentResolver.query(
                        Telephony.Sms.CONTENT_URI,
                        arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.ADDRESS),
                        selection,
                        selectionArgs,
                        "${Telephony.Sms.DATE} DESC LIMIT $actualLimit",
                    )?.use { cursor ->
                        val smsFormat = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US)
                        while (cursor.moveToNext()) {
                            val body = cursor.getString(0)
                            val date = smsFormat.format(java.util.Date(cursor.getLong(1)))
                            val dir = if (cursor.getInt(2) == Telephony.Sms.MESSAGE_TYPE_INBOX) "From" else "To"
                            val addr = cursor.getString(3)
                            results.add("$dir $addr ($date): ${body.take(200)}")
                        }
                    }
                }
            }

            actionChannel.trySend(BorizonAction.Progress(label = "Found ${results.size} results", isInProgress = false, toolType = toolType))
            if (results.isEmpty()) mapOf("result" to "empty", "message" to "No results found")
            else {
                val smsResults = results.joinToString("\n").take(4000)
                ToolResultCache.put("sms", smsResults.take(300))
                mapOf("result" to "found", "count" to results.size.toString(), "messages" to smsResults)
            }
        } catch (e: SecurityException) {
            mapOf("result" to "error", "error" to "Permission denied.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read messages", e)
            mapOf("result" to "error", "error" to "Failed: ${e.message}")
        }
    }
}
