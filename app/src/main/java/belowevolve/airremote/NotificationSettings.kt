package belowevolve.airremote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Android owns notification visibility; hiding a channel must not stop its foreground service. */
@Composable
internal fun NotificationSettingsRow(channel: String, name: String) {
    val context = LocalContext.current
    DialogActionRow("Уведомления") {
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(channel, name, NotificationManager.IMPORTANCE_LOW))
        val appSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        val channelSettings = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
        openSystemSettings(context,
            if (notifications.areNotificationsEnabled()) channelSettings else appSettings,
            appSettings)
    }
}

internal fun openSystemSettings(context: Context, vararg intents: Intent) {
    for (intent in intents.toList() + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"))) {
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}
