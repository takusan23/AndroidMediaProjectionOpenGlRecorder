package io.github.takusan23.androidmediaprojectionopenglrecorder

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ScreenRecordService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val notificationManager by lazy { NotificationManagerCompat.from(this) }

    /** 画面録画を司るクラス */
    private var screenRecorder: ScreenRecorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // すぐ通知を出す
        notifyForegroundServiceNotification()

        // MediaProjection 開始に必要
        val startOrStop = intent?.extras?.getString(EXTRA_KEY_SERVICE_START_OR_STOP)
            ?.let { ServiceStartOrStopType.resolve(it) }

        when (startOrStop) {
            // 開始
            ServiceStartOrStopType.START -> scope.launch {
                screenRecorder = ScreenRecorder(
                    context = this@ScreenRecordService,
                    resultCode = intent.extras?.getInt(EXTRA_KEY_RESULT_CODE)!!,
                    resultData = intent.extras?.getParcelable(EXTRA_KEY_RESULT_INTENT)!!
                )
                screenRecorder?.startRecord()
            }

            // 終了
            ServiceStartOrStopType.STOP, null -> scope.launch {
                screenRecorder?.stopRecord()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun notifyForegroundServiceNotification() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW).apply {
                setName("画面録画サービス起動中通知")
            }.build()
            notificationManager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID).apply {
            setContentTitle("画面録画")
            setContentText("録画中です")
            setSmallIcon(R.drawable.ic_launcher_foreground)
        }.build()

        // ForegroundServiceType は必須です
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    companion object {

        private const val CHANNEL_ID = "screen_recorder_service"
        private const val NOTIFICATION_ID = 4545

        private const val EXTRA_KEY_SERVICE_START_OR_STOP = "service_start_or_stop"
        private const val EXTRA_KEY_RESULT_CODE = "result_code"
        private const val EXTRA_KEY_RESULT_INTENT = "result_intent"

        fun startService(
            context: Context,
            mediaProjectionResultCode: Int,
            mediaProjectionResultIntent: Intent
        ) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                putExtra(EXTRA_KEY_SERVICE_START_OR_STOP, ServiceStartOrStopType.START.code)
                putExtra(EXTRA_KEY_RESULT_CODE, mediaProjectionResultCode)
                putExtra(EXTRA_KEY_RESULT_INTENT, mediaProjectionResultIntent)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                putExtra(EXTRA_KEY_SERVICE_START_OR_STOP, ServiceStartOrStopType.STOP.code)
            }
            // start するけどすぐ stopSelf で終了させます
            ContextCompat.startForegroundService(context, intent)
        }

    }

    private enum class ServiceStartOrStopType(val code: String) {
        START("start"),
        STOP("stop");

        companion object {
            fun resolve(code: String) = entries.first { it.code == code }
        }
    }
}