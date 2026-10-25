package belowevolve.airremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build

/** Keeps the HID client's UID foreground while its Bluetooth connection is in use. */
class PcConnectionService : Service() {
    override fun onCreate() {
        super.onCreate()
        val channel = "pc_connection"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Подключение к ПК", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 20, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
        )
        val notification = Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_remote_monochrome)
            .setContentTitle("Пульт для ПК")
            .setContentText("Bluetooth")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(2, notification)
    }

    // The ViewModel owns the session; don't restart a service without its HID client.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?) = null
}
