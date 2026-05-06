package com.manegow.data.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.manegow.domain.repository.IdentityRepository
import com.manegow.model.chat.Message
import kotlinx.coroutines.flow.firstOrNull

class NotificationHandler(
    private val context: Context,
    private val identityRepository: IdentityRepository
) {
    companion object {
        private const val CHANNEL_ID = "chat_messages"
        private const val CHANNEL_NAME = "Mensajes de Chat"
        private const val CHANNEL_DESCRIPTION = "Notificaciones de nuevos mensajes recibidos por la red mesh"
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESCRIPTION
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    suspend fun showMessageNotification(message: Message, senderName: String) {
        val settings = identityRepository.observeSettings().firstOrNull() ?: return
        
        // Respetar los ajustes del usuario
        if (!settings.notificationsEnabled) return

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_chat_id", message.chatId.value)
            putExtra("open_chat_name", senderName)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 
            0, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat) // Icono temporal
            .setContentTitle(senderName)
            .setContentText(message.body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (settings.soundsEnabled) {
            builder.setDefaults(NotificationCompat.DEFAULT_SOUND)
        }
        
        if (settings.vibrationEnabled) {
            builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE)
        }

        notificationManager.notify(message.chatId.value.hashCode(), builder.build())
    }
}
