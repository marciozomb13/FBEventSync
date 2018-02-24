/*
    Copyright (C) 2017 - 2018  Daniel Vrátil <me@dvratil.cz>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package cz.dvratil.fbeventsync

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SyncRequest
import android.content.SyncResult
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.support.v4.app.NotificationCompat
import android.support.v4.content.ContextCompat

import com.loopj.android.http.DataAsyncHttpResponseHandler
import com.loopj.android.http.RequestParams

import org.json.JSONObject

import java.util.ArrayList
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

import biweekly.Biweekly
import cz.msebera.android.httpclient.Header
import cz.msebera.android.httpclient.client.utils.URIBuilder

class CalendarSyncAdapter(context: Context, autoInitialize: Boolean) : AbstractThreadedSyncAdapter(context, autoInitialize) {

    private var logger: Logger

    private var mSyncContext: SyncContext? = null

    init {
        PreferencesMigrator.migrate(context)
        logger = Logger.getInstance(context)

        checkPermissions()
    }


    override fun onPerformSync(account: Account, bundle: Bundle, authority: String,
                               provider: ContentProviderClient, syncResult: SyncResult) {
        logger.info(TAG, "performSync request for account %s, authority %s", account.name, authority)

        if (mSyncContext != null) {
            logger.warning(TAG, "SyncContext not null, another sync already running? Aborting this one")
            return
        }

        if (!checkPermissions()) {
            logger.info(TAG, "Skipping sync, missing permissions")
            return
        }

        val prefs = Preferences(context)

        // Don't sync more often than every minute
        val calendar = Calendar.getInstance()
        val lastSync = prefs.lastSync()
        if (!BuildConfig.DEBUG) {
            if (calendar.timeInMillis - lastSync < 60 * 1000) {
                logger.info(TAG, "Skipping sync, last sync was only %d seconds ago",
                        (calendar.timeInMillis - lastSync) / 1000)
                return
            }
            prefs.setLastSync(Calendar.getInstance().timeInMillis)
        }

        // Allow up to 5 syncs per hour
        var syncsPerHour = prefs.syncsPerHour()
        if (!BuildConfig.DEBUG) {
            if (calendar.timeInMillis - lastSync < 3600 * 1000) {
                val hour = calendar.get(Calendar.HOUR)
                calendar.timeInMillis = lastSync
                logger.debug(TAG, "Lasy sync hour: %d, now sync hour: %d", calendar.get(Calendar.HOUR), hour)
                if (calendar.get(Calendar.HOUR) != hour) {
                    syncsPerHour = 1
                } else {
                    syncsPerHour++
                }
                if (syncsPerHour > 5) {
                    logger.info(TAG, "Skipping sync, too many syncs per hour")
                    return
                }
            } else {
                syncsPerHour = 1
                prefs.setSyncsPerHour(syncsPerHour)
            }
        }

        val mgr = AccountManager.get(context)
        val accessToken: String?
        try {
            val result = mgr.getAuthToken(account, Authenticator.FB_OAUTH_TOKEN, null, false, null, null).result
            accessToken = result.getString(AccountManager.KEY_AUTHTOKEN)
            if (accessToken == null) {
                logger.debug(TAG, "Needs to reauthenticate, will wait for user")
                createAuthNotification()
                return
            } else {
                logger.debug(TAG, "Access token received")
            }
        } catch (e: android.accounts.OperationCanceledException) {
            logger.error(TAG, "Failed to obtain auth token: %s", e.message)
            syncResult.stats.numAuthExceptions++
            return
        } catch (e: android.accounts.AuthenticatorException) {
            logger.error(TAG, "Failed to obtain auth token: %s", e.message)
            syncResult.stats.numAuthExceptions++
            return
        } catch (e: java.io.IOException) {
            logger.error(TAG, "Failed to obtain auth token: %s", e.message)
            syncResult.stats.numAuthExceptions++
            return
        }

        val syncContext = SyncContext(context, account, accessToken, provider, syncResult, logger)
        mSyncContext = syncContext

        val calendars = FBCalendar.Set()
        calendars.initialize(syncContext)
        if (prefs.lastVersion() != BuildConfig.VERSION_CODE) {
            logger.info(TAG, "New version detected: deleting all calendars")
            removeOldBirthdayCalendar(syncContext)
            try {
                calendars.values.forEach {
                    it.deleteLocalCalendar()
                }
            } catch (e: android.os.RemoteException) {
                // FIXME: Handle exceptions
                logger.error(TAG, "Failed to cleanup calendars: %s", e.message)
                syncResult.stats.numIoExceptions++
                return
            } catch (e: android.database.sqlite.SQLiteException) {
                logger.error(TAG, "Failed to cleanup calendars: %s", e.message)
                syncResult.stats.numIoExceptions++
                return
            }
            prefs.setLastVersion(BuildConfig.VERSION_CODE)

            // We have to re-initialize calendars now so that they get re-created
            calendars.initialize(syncContext)
        }

        // Sync via iCal only - not avoiding Graph calls, but it gives us access to private group
        // events which are otherwise missing from Graph
        if (syncEventsViaICal(calendars)) {
            calendars.forEach {
                if (it.value.type() != FBCalendar.CalendarType.TYPE_BIRTHDAY) {
                    it.value.finalizeSync()
                }
            }
        }

        if (calendars[FBCalendar.CalendarType.TYPE_BIRTHDAY]?.isEnabled == true) {
            if (syncBirthdayCalendar(calendars)) {
                calendars[FBCalendar.CalendarType.TYPE_BIRTHDAY]!!.finalizeSync()
            }
        }

        mSyncContext = null

        logger.info(TAG, "Sync for %s done", account.name)
    }

    @Suppress("unused")
    private fun syncEventsViaGraph(calendars: FBCalendar.Set) {
        val syncContext = mSyncContext ?: return
        var cursor: String? = null
        do {
            val response = fetchEvents(cursor) ?: continue
            try {
                if (response.has("data")) {
                    val data = response.getJSONArray("data")
                    val len = data.length()
                    var lastEvent: FBEvent? = null
                    for (i in 0 until len) {
                        val event = FBEvent.parse(data.getJSONObject(i)!!, syncContext)
                        val calendar = calendars.getCalendarForEvent(event)
                        if (calendar == null) {
                            logger.error(TAG, "Failed to find calendar for event!")
                            continue
                        }
                        event.setCalendar(calendar)
                        calendar.syncEvent(event)
                        lastEvent = event
                    }

                    // Only sync events back one year, don't go any further in the past to save
                    // bandwidth
                    if (lastEvent != null) {
                        val cal = Calendar.getInstance(TimeZone.getDefault())
                        cal.add(Calendar.YEAR, -1)
                        val lastEventStart = lastEvent.values.getAsLong(CalendarContract.Events.DTSTART)
                        if (lastEventStart < cal.timeInMillis) {
                            break
                        }
                    }
                }
                cursor = getNextCursor(response)
            } catch (e: java.text.ParseException) {
                cursor = null
                logger.error(TAG, "Text parse exception: %s", e.message)
                syncContext.syncResult.stats.numParseExceptions++
            } catch (e: org.json.JSONException) {
                cursor = null
                logger.error(TAG, "JSON exception in main loop: %s", e.message)
                syncContext.syncResult.stats.numParseExceptions++
            }

        } while (cursor != null)
    }

    private fun fetchEvents(cursor: String?): JSONObject? {
        val syncContext = mSyncContext ?: return null
        val params = RequestParams()
        params.add(Graph.FIELDS_PARAM, "id,name,description,place,start_time,end_time,owner,is_canceled,rsvp_status")
        params.add(Graph.LIMIT_PARAM, "100")
        if (cursor != null) {
            params.add(Graph.AFTER_PARAM, cursor)
        }

        val handler = GraphResponseHandler(syncContext.context)

        logger.debug(TAG, "Sending Graph request...")
        Graph.events(syncContext.accessToken, params, handler)
        logger.debug(TAG, "Graph response received")

        return handler.response
    }

    private fun getNextCursor(obj: JSONObject): String? {
        return try {
            obj.getJSONObject("paging")?.getJSONObject("cursors")?.getString("after")
        } catch (e: org.json.JSONException) {
            null
        }

    }

    private enum class ICalURIType {
        EVENTS,
        BIRTHDAYS
    }

    private fun getICalSyncURI(uriType: ICalURIType): Uri? {
        val syncContext = mSyncContext ?: return null
        val accManager = AccountManager.get(syncContext.context)
        val uid: String?
        val key: String?
        try {
            // This block will automatically trigger authentication if the tokens are missing, so
            // no explicit migration from the old bday_uri is needed
            uid = accManager.blockingGetAuthToken(syncContext.account, Authenticator.FB_UID_TOKEN, false)
            key = accManager.blockingGetAuthToken(syncContext.account, Authenticator.FB_KEY_TOKEN, false)
        } catch (e: android.accounts.OperationCanceledException) {
            logger.error(TAG, "User cancelled obtaining UID/KEY token: %s", e.message)
            return null
        } catch (e: java.io.IOException) {
            logger.error(TAG, "IO Exception while obtaining UID/KEY token: %s", e.message)
            return null
        } catch (e: android.accounts.AuthenticatorException) {
            logger.error(TAG, "Authenticator exception while obtaining UID/KEY token: %s", e.message)
            return null
        }

        if (uid == null || key == null || uid.isEmpty() || key.isEmpty()) {
            logger.error(TAG, "Failed to obtain UID/KEY tokens from account manager")
            // We only need to invalidate one token to force re-sync
            accManager.invalidateAuthToken(syncContext.context.getString(R.string.account_type), syncContext.accessToken)
            return null
        }

        var userLocale = syncContext.preferences.language()
        if (userLocale == syncContext.context.getString(R.string.pref_language_default_value)) {
            val locale = Locale.getDefault()
            userLocale = "${locale.language}_${locale.country}"
        }

        return Uri.parse("https://www.facebook.com").buildUpon()
                .path(if (uriType == ICalURIType.EVENTS) "/ical/u.php" else "/ical/b.php")
                .appendQueryParameter("uid", uid)
                .appendQueryParameter("key", key)
                .appendQueryParameter("locale", userLocale)
                .build()
    }

    private fun sanitizeICalUri(uri: Uri): String {
        return try {
            val b = URIBuilder(uri.toString())
            val params = b.queryParams
            b.clearParameters()
            for (param in params) {
                if (param.name == "uid" || param.name == "key") {
                    b.addParameter(param.name, "hidden")
                } else {
                    b.addParameter(param.name, param.value)
                }
            }
            b.toString()
        } catch (e: java.net.URISyntaxException) {
            "<URI parsing error>"
        }

    }

    private fun syncEventsViaICal(calendars: FBCalendar.Set): Boolean {
        val uri = getICalSyncURI(ICalURIType.EVENTS) ?: return false

        logger.debug(TAG, "Syncing event iCal from %s", sanitizeICalUri(uri))
        return syncICalCalendar(calendars, uri.toString())
    }

    private fun syncBirthdayCalendar(calendars: FBCalendar.Set): Boolean {
        val uri = getICalSyncURI(ICalURIType.BIRTHDAYS) ?: return false

        logger.debug(TAG, "Syncing birthday iCal from %s", sanitizeICalUri(uri))
        return syncICalCalendar(calendars, uri.toString())
    }

    private fun syncICalCalendar(calendars: FBCalendar.Set, uri: String): Boolean {
        var success = false
        Graph.fetchBirthdayICal(uri, object : DataAsyncHttpResponseHandler() {
            override fun onSuccess(statusCode: Int, headers: Array<Header>, responseBody: ByteArray?) {
                val syncContext = mSyncContext ?: return
                if (responseBody == null) {
                    logger.error(TAG, "Response body is empty!")
                    return
                }

                val cal = Biweekly.parse(String(responseBody)).first()
                cal.events.forEach {
                    val event = FBEvent.parse(it, syncContext)
                    val calendar = calendars.getCalendarForEvent(event) ?: return
                    if (calendar.isEnabled) {
                        event.setCalendar(calendar)
                        calendar.syncEvent(event)
                    }
                }
                logger.debug(TAG, "iCal sync done")
                success = true
            }

            override fun onFailure(statusCode: Int, headers: Array<Header>?, responseBody: ByteArray?, error: Throwable?) {
                val err = if (responseBody == null) "Unknown error" else String(responseBody)
                logger.error(TAG, "Error retrieving iCal file: %d, %s", statusCode, err)
                logger.error(TAG, "URI: %s", uri)
                headers?.forEach {
                    logger.error(TAG, "    %s: %s", it.name, it.value)
                }
                if (error != null) {
                    logger.error(TAG, "Throwable: %s", error.toString())
                }
            }

            override fun onProgressData(responseBody: ByteArray?) {
                // silence debug output
            }
        })

        return success
    }

    private fun removeOldBirthdayCalendar(context: SyncContext) {
        // remove old "birthday" calendar
        logger.debug(TAG, "Removing legacy birthday calendar")
        try {
            context.contentProviderClient.delete(
                    context.contentUri(CalendarContract.Calendars.CONTENT_URI),
                    "(${CalendarContract.Calendars.NAME} = ?)",
                    arrayOf("birthday") // old name for the fb_birthday_calendar calendar
            )
        } catch (e: android.os.RemoteException) {
            logger.error(TAG, "RemoteException when removing legacy calendar: %s", e.message)
        } catch (e: android.database.sqlite.SQLiteException) {
            logger.error(TAG, "SQLiteException when removing legacy calendar: %s", e.message)
        } catch (e: java.lang.IllegalArgumentException) {
            logger.error(TAG, "IllegalArgumentException when removing legacy calendar: %s", e.message)
        }

    }

    private fun checkPermissions(): Boolean {
        val missingPermissions = ArrayList<String>()
        var permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.READ_CALENDAR)
            missingPermissions.add(Manifest.permission.WRITE_CALENDAR)
        }
        permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SYNC_SETTINGS)
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.ACCOUNT_MANAGER)
            missingPermissions.add(Manifest.permission.READ_SYNC_SETTINGS)
            missingPermissions.add(Manifest.permission.WRITE_SYNC_SETTINGS)
        }

        if (!missingPermissions.isEmpty()) {
            logger.info("SYNC.PERM", "Missing permissions: " + missingPermissions.toString())
            val extras = Bundle()
            extras.putStringArrayList(PermissionRequestActivity.MISSING_PERMISSIONS, missingPermissions)

            val intent = Intent(context, PermissionRequestActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtras(extras)
            context.startActivity(intent)
            return false
        }

        logger.info("SYNC.PERM", "All permissions granted")
        return true
    }

    private fun createAuthNotification() {
        val builder = NotificationCompat.Builder(context, AuthenticatorActivity.AUTH_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.sync_ntf_needs_reauthentication_title))
                .setContentText(context.getString(R.string.sync_ntf_needs_reauthentication_description))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)

        val intent = Intent(context, AuthenticatorActivity::class.java)
        intent.putExtra(AccountManager.KEY_ACCOUNT_TYPE, context.getString(R.string.account_type))
        intent.putExtra(AuthenticatorActivity.ARG_AUTH_TOKEN_TYPE, AuthenticatorActivity.ARG_AUTH_TOKEN_TYPE)
        intent.putExtra(AuthenticatorActivity.ARG_IS_ADDING_NEW_ACCOUNT, false)

        val stackBuilder = TaskStackBuilder.create(context)
        stackBuilder.addParentStack(AuthenticatorActivity::class.java)
        stackBuilder.addNextIntent(intent)
        val resultPendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT)
        builder.setContentIntent(resultPendingIntent)

        val ntfMgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ntfMgr.notify(AuthenticatorActivity.AUTH_NOTIFICATION_ID, builder.build())
    }

    companion object {

        private const val TAG = "SYNC"

        fun requestSync(context: Context) {
            val accountType = context.resources.getString(R.string.account_type)
            val logger = Logger.getInstance(context)
            for (account in AccountManager.get(context).getAccountsByType(accountType)) {
                val extras = Bundle()
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                ContentResolver.requestSync(account, CalendarContract.AUTHORITY, extras)
                logger.info(TAG, "Explicitly requested sync for account %s", account.name)
            }
        }

        fun updateSync(context: Context) {
            val accountType = context.resources.getString(R.string.account_type)

            val pref = Preferences(context)
            val syncInterval = pref.syncFrequency()
            val logger = Logger.getInstance(context)

            for (account in AccountManager.get(context).getAccountsByType(accountType)) {
                val extras = Bundle()
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)

                ContentResolver.cancelSync(account, CalendarContract.AUTHORITY)
                if (syncInterval == 0) {
                    continue
                }

                // Schedule periodic sync based on current configuration
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    // we can enable inexact timers in our periodic sync
                    val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        SyncRequest.Builder()
                                .syncPeriodic(syncInterval.toLong(), (syncInterval / 3).toLong())
                                .setSyncAdapter(account, CalendarContract.AUTHORITY)
                                .setExtras(Bundle()).build()
                    } else {
                        null
                    }
                    ContentResolver.requestSync(request!!)
                    logger.info(TAG, "Scheduled periodic sync for account %s using requestSync, interval: %d",
                            account.name, syncInterval)
                } else {
                    ContentResolver.addPeriodicSync(account, CalendarContract.AUTHORITY, Bundle(), syncInterval.toLong())
                    logger.info(TAG, "Scheduled periodic sync for account %s using addPeriodicSync, interval: %d",
                            account.name, syncInterval)
                }
            }
        }
    }
}