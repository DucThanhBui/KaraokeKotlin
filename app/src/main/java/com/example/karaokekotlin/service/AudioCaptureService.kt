package com.example.karaokekotlin.service

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.example.karaokekotlin.util.Constants
import com.example.karaokekotlin.util.Constants.Companion.LOG_TAG
import com.example.karaokekotlin.util.Constants.Companion.ACTION_START
import com.example.karaokekotlin.util.Constants.Companion.ACTION_STOP
import com.example.karaokekotlin.util.Constants.Companion.AUDIO_CAPTURE_SAMPLE_RATE
import com.example.karaokekotlin.util.Constants.Companion.BUFFER_SIZE_IN_BYTES
import com.example.karaokekotlin.util.Constants.Companion.EXTRA_RESULT_DATA
import com.example.karaokekotlin.util.Constants.Companion.NOTIFICATION_CHANNEL_ID
import com.example.karaokekotlin.util.Constants.Companion.NUM_SAMPLES_PER_READ
import com.example.karaokekotlin.util.Constants.Companion.SERVICE_ID
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.experimental.and

class AudioCaptureService : Service() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null

    private lateinit var audioCaptureThread: Thread
    private var audioRecord: AudioRecord? = null
    private var getPath: String = ""

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            SERVICE_ID,
            NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID).build()
        )

        // use applicationContext to avoid memory leak on Android 10.
        // see: https://partnerissuetracker.corp.google.com/issues/139732252
        mediaProjectionManager =
            applicationContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Audio Capture Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )

        val manager = getSystemService(NotificationManager::class.java) as NotificationManager
        manager.createNotificationChannel(serviceChannel)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStart(intent, startId)
        return if (intent != null) {
            when (intent.action) {
                ACTION_START -> {
                    mediaProjection =
                        mediaProjectionManager.getMediaProjection(
                            Activity.RESULT_OK,
                            intent.getParcelableExtra(EXTRA_RESULT_DATA)!!
                        ) as MediaProjection
                    getPath = intent.getStringExtra(Constants.DES_PATH_KEY)!!
                    Log.d("TAGggg onStartCapture", getPath)
                    startAudioCapture()
                    START_STICKY
                }
                ACTION_STOP -> {
                    stopAudioCapture()
                    START_NOT_STICKY
                }
                else -> throw IllegalArgumentException("Unexpected action received: ${intent.action}")
            }
        } else {
            START_NOT_STICKY
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startAudioCapture() {
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA) // TODO provide UI options for inclusion/exclusion
            .build()

        /**
         * Using hardcoded values for the audio format, Mono PCM samples with a sample rate of 8000Hz
         * These can be changed according to your application's needs
         */
        //val audioManager = this.getSystemService(AUDIO_SERVICE) as AudioManager
        //val sampleRateString = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "48000"
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AUDIO_CAPTURE_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            // For optimal performance, the buffer size
            // can be optionally specified to store audio samples.
            // If the value is not specified,
            // uses a single frame and lets the
            // native code figure out the minimum buffer size.
            .setBufferSizeInBytes(BUFFER_SIZE_IN_BYTES)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        audioRecord!!.startRecording()
        audioCaptureThread = thread(start = true) {
            Log.d(LOG_TAG, "Created file for capture target: $getPath")
            writeAudioToFile(File(getPath))
        }
    }

//    private fun createAudioFile(): File {
//        val audioCapturesDirectory = File(getExternalFilesDir(null), "/AudioCaptures")
//        if (!audioCapturesDirectory.exists()) {
//            audioCapturesDirectory.mkdirs()
//        }
//        val timestamp = SimpleDateFormat("dd-MM-yyyy-hh-mm-ss", Locale.US).format(Date())
//        val fileName = "Capture-$timestamp.mp3"
//        return File(audioCapturesDirectory.absolutePath + "/" + fileName)
//    }

    private fun writeAudioToFile(outputFile: File) {
        val fileOutputStream = FileOutputStream(outputFile)
        val capturedAudioSamples = ShortArray(NUM_SAMPLES_PER_READ)

        while (!audioCaptureThread.isInterrupted) {
            audioRecord?.read(capturedAudioSamples, 0, NUM_SAMPLES_PER_READ)

            // This loop should be as fast as possible to avoid artifacts in the captured audio
            // You can uncomment the following line to see the capture samples but
            // that will incur a performance hit due to logging I/O.
            // Log.v(LOG_TAG, "Audio samples captured: ${capturedAudioSamples.toList()}")

            fileOutputStream.write(
                capturedAudioSamples.toByteArray(),
                0,
                BUFFER_SIZE_IN_BYTES
            )
        }
        fileOutputStream.close()
        Log.d(LOG_TAG, "Audio capture finished for ${outputFile.absolutePath}. File size is ${outputFile.length()} bytes.")
    }

    private fun stopAudioCapture() {
        requireNotNull(mediaProjection) { "Tried to stop audio capture, but there was no ongoing capture in place!" }

        audioCaptureThread.interrupt()
        audioCaptureThread.join()

        audioRecord!!.stop()
        audioRecord!!.release()
        audioRecord = null
        mediaProjection!!.stop()
        stopSelf()
    }

    override fun onBind(p0: Intent?): IBinder? = null

    private fun ShortArray.toByteArray(): ByteArray {
        // Samples get translated into bytes following little-endianness:
        // least significant byte first and the most significant byte last
        val bytes = ByteArray(size * 2)
        for (i in indices) {
            bytes[i * 2] = (this[i] and 0x00FF).toByte()
            bytes[i * 2 + 1] = (this[i].toInt() shr 8).toByte()
            this[i] = 0
        }
        return bytes
    }
}