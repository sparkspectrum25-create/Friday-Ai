package com.friday.launcher

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

class StarkNotifService : NotificationListenerService() {
    companion object {
        @Volatile var sink: ((JSONObject) -> Unit)? = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (n.category == Notification.CATEGORY_CALL) {
            // Incoming-call notifications carry answer + decline actions; ongoing calls usually only hang-up.
            if ((n.actions?.size ?: 0) >= 2) {
                sink?.invoke(JSONObject().put("type", "call").put("key", sbn.key).put("caller", title))
            }
            return
        }
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) { sbn.packageName }
        sink?.invoke(JSONObject().put("type", "notif").put("app", app).put("title", title).put("text", text))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.notification.category == Notification.CATEGORY_CALL) {
            sink?.invoke(JSONObject().put("type", "callEnd").put("key", sbn.key))
        }
    }
}
