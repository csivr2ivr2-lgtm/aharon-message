package com.aharon.message.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aharon.message.AharonMessageApp
import com.aharon.message.MainActivity
import com.aharon.message.R
import com.aharon.message.acoustic.AudioTransceiver
import com.aharon.message.engine.MessagingEngine
import com.aharon.message.protocol.ProtocolCodec
import com.aharon.message.protocol.ProtocolPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

class AcousticReceiverService : Service() {
    companion object {
        private const val ACTION_START = "com.aharon.message.action.START"
        private const val ACTION_STOP = "com.aharon.message.action.STOP"
        private const val ACTION_SEND = "com.aharon.message.action.SEND"
        private const val EXTRA_FRAME = "frame"
        private const val RECEIVER_CHANNEL = "ultrasonic_receiver"
        private const val MESSAGE_CHANNEL = "messages"
        private const val FOREGROUND_ID = 1107

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _receivedFrames = MutableStateFlow(0L)
        val receivedFrames: StateFlow<Long> = _receivedFrames.asStateFlow()

        private val _crcErrors = MutableStateFlow(0L)
        val crcErrors: StateFlow<Long> = _crcErrors.asStateFlow()

        private val _lastSignalDb = MutableStateFlow<Double?>(null)
        val lastSignalDb: StateFlow<Double?> = _lastSignalDb.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, AcousticReceiverService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AcousticReceiverService::class.java).setAction(ACTION_STOP))
        }

        fun enqueue(context: Context, packet: ProtocolPacket) {
            val frame = ProtocolCodec.encode(packet)
            val intent = Intent(context, AcousticReceiverService::class.java)
                .setAction(ACTION_SEND)
                .putExtra(EXTRA_FRAME, frame)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outbound = ConcurrentLinkedQueue<ByteArray>()
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val container get() = (application as AharonMessageApp).container
    private val engine get() = container.engine

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                container.settings.receiverEnabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SEND -> {
                val frame = intent.getByteArrayExtra(EXTRA_FRAME)
                if (frame != null) outbound.offer(frame)
                startForegroundIfAllowed()
                startWorkerIfNeeded()
            }
            ACTION_START -> {
                container.settings.receiverEnabled = true
                startForegroundIfAllowed()
                startWorkerIfNeeded()
                engine.resumeRetries()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker?.cancel()
        worker = null
        scope.cancel()
        runCatching { wakeLock?.release() }
        wakeLock = null
        _running.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundIfAllowed() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        startForeground(FOREGROUND_ID, receiverNotification())
        if (wakeLock == null) {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AharonMessage:Receiver").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        _running.value = true
    }

    private fun startWorkerIfNeeded() {
        if (!_running.value || worker?.isActive == true) return
        worker = scope.launch {
            var profileId: String? = null
            var transceiver: AudioTransceiver? = null
            try {
                while (isActive) {
                    val desired = container.settings.profile()
                    if (profileId != desired.id || transceiver == null) {
                        transceiver?.close()
                        transceiver = AudioTransceiver(desired)
                        profileId = desired.id
                    }
                    val activeTransceiver = checkNotNull(transceiver)

                    val outgoing = outbound.poll()
                    if (outgoing != null) {
                        val packet = ProtocolCodec.decode(outgoing)
                        activeTransceiver.send(outgoing)
                        if (packet != null) engine.onPacketTransmitted(packet)
                        delay(90L)
                        continue
                    }

                    val received = activeTransceiver.receiveFrame(350L)
                    if (received != null) {
                        _lastSignalDb.value = received.signalDb
                        val packet = ProtocolCodec.decode(received.bytes)
                        if (packet == null) {
                            _crcErrors.value += 1
                            continue
                        }
                        _receivedFrames.value += 1
                        val result = engine.handleIncoming(packet)
                        result.replies.forEach { outbound.offer(ProtocolCodec.encode(it)) }
                        result.notification?.let(::showMessageNotification)
                    }
                }
            } catch (_: SecurityException) {
                stopSelf()
            } catch (_: Throwable) {
                stopSelf()
            } finally {
                transceiver?.close()
            }
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                RECEIVER_CHANNEL,
                getString(R.string.receiver_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.receiver_channel_description)
                setSound(null, null)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(MESSAGE_CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun receiverNotification() = NotificationCompat.Builder(this, RECEIVER_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Aharon Message")
        .setContentText("Ultrasonic receiver is active")
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(mainPendingIntent())
        .build()

    private fun showMessageNotification(event: MessagingEngine.NotificationEvent) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(event.title)
            .setContentText(event.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
            .setAutoCancel(true)
            .setContentIntent(mainPendingIntent())
            .build()
        getSystemService(NotificationManager::class.java)
            .notify((System.nanoTime() and 0x7fffffff).toInt(), notification)
    }

    private fun mainPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
