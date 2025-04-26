package com.isvisoft.flutter_screen_recording

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FlutterScreenRecordingPlugin : 
    MethodCallHandler, 
    PluginRegistry.ActivityResultListener,
    FlutterPlugin, 
    ActivityAware {

    // Constants
    private val PREFS_NAME = "ScreenRecordingPrefs"
    private val PREF_PERMISSION_GRANTED = "permission_granted"
    private val SCREEN_RECORD_REQUEST_CODE = 333
    private val RECORDINGS_SUBDIR = "ScreenRecordings"

    // Screen recording variables
    private var mScreenDensity: Int = 0
    private var mMediaRecorder: MediaRecorder? = null
    private var mMediaProjection: MediaProjection? = null
    private var mMediaProjectionCallback: MediaProjectionCallback? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mDisplayWidth: Int = 1280
    private var mDisplayHeight: Int = 800
    private var videoName: String? = ""
    private var mFileName: String? = ""
    private var mTitle = "Your screen is being recorded"
    private var mMessage = "Your screen is being recorded"
    private var recordAudio: Boolean = false

    // Flutter plugin bindings
    private lateinit var _result: Result
    private var pluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var serviceConnection: ServiceConnection? = null

    // Lazy initialization of MediaProjectionManager
    private val mProjectionManager: MediaProjectionManager by lazy {
        ContextCompat.getSystemService(
            pluginBinding!!.applicationContext,
            MediaProjectionManager::class.java
        ) ?: throw Exception("MediaProjectionManager not found")
    }

    // Permission handling methods
    private fun hasPermission(): Boolean {
        val prefs = pluginBinding!!.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_PERMISSION_GRANTED, false)
    }

    private fun setPermissionGranted(granted: Boolean) {
        val prefs = pluginBinding!!.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_PERMISSION_GRANTED, granted).apply()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val context = pluginBinding!!.applicationContext
        
        if (requestCode == SCREEN_RECORD_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                // Save that permission was granted
                setPermissionGranted(true)
                
                ForegroundService.startService(context, mTitle, mMessage)
                val intentConnection = Intent(context, ForegroundService::class.java)

                serviceConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        try {
                            startRecordScreen()
                            mMediaProjectionCallback = MediaProjectionCallback()
                            mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data!!)
                            mMediaProjectionCallback?.let { callback ->
                            mMediaProjection?.registerCallback(callback, null)
                            }
                            mVirtualDisplay = createVirtualDisplay()
                            _result.success(true)
                        } catch (e: Throwable) {
                            e.message?.let { Log.e("ScreenRecordingPlugin", it) }
                            _result.success(false)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {}
                }

                context.bindService(intentConnection, serviceConnection!!, Activity.BIND_AUTO_CREATE)
            } else {
                ForegroundService.stopService(context)
                _result.success(false)
            }
            return true
        }
        return false
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        _result = result
        val appContext = pluginBinding!!.applicationContext

        when (call.method) {
            "startRecordScreen" -> {
                try {
                    // Get parameters from Flutter
                    mTitle = call.argument<String?>("title") ?: mTitle
                    mMessage = call.argument<String?>("message") ?: mMessage
                    videoName = call.argument<String?>("name")
                    recordAudio = call.argument<Boolean?>("audio") ?: false

                    // Check if we already have permission
                    if (hasPermission() && mMediaProjection != null) {
                        // If we have permission, start recording directly
                        ForegroundService.startService(appContext, mTitle, mMessage)
                        val intentConnection = Intent(appContext, ForegroundService::class.java)

                        serviceConnection = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                                try {
                                    startRecordScreen()
                                    mVirtualDisplay = createVirtualDisplay()
                                    _result.success(true)
                                } catch (e: Throwable) {
                                    e.message?.let { Log.e("ScreenRecordingPlugin", it) }
                                    _result.success(false)
                                }
                            }

                            override fun onServiceDisconnected(name: ComponentName?) {}
                        }

                        appContext.bindService(intentConnection, serviceConnection!!, Activity.BIND_AUTO_CREATE)
                        return
                    }

                    // If no permission, request it
                    val metrics = DisplayMetrics()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        activityBinding!!.activity.display?.getRealMetrics(metrics)
                    } else {
                        @SuppressLint("NewApi")
                        val defaultDisplay = appContext.display
                        defaultDisplay?.getMetrics(metrics)
                    }
                    mScreenDensity = metrics.densityDpi
                    calculateResolution(metrics)

                    val permissionIntent = mProjectionManager.createScreenCaptureIntent()
                    ActivityCompat.startActivityForResult(
                        activityBinding!!.activity,
                        permissionIntent,
                        SCREEN_RECORD_REQUEST_CODE,
                        null
                    )

                } catch (e: Exception) {
                    Log.e("ScreenRecording", "Error starting recording", e)
                    result.success(false)
                }
            }
            "stopRecordScreen" -> {
                try {
                    serviceConnection?.let {
                        appContext.unbindService(it)
                    }
                    ForegroundService.stopService(appContext)
                    if (mMediaRecorder != null) {
                        stopRecordScreen()
                        result.success(mFileName)
                    } else {
                        result.success("")
                    }
                } catch (e: Exception) {
                    Log.e("ScreenRecording", "Error stopping recording", e)
                    result.success("")
                }
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    private fun calculateResolution(metrics: DisplayMetrics) {
        mDisplayHeight = metrics.heightPixels
        mDisplayWidth = metrics.widthPixels

        val maxRes = if (metrics.scaledDensity >= 3.0f) 1920.0 else 1280.0
        
        if (metrics.widthPixels > metrics.heightPixels) {
            var rate = metrics.widthPixels / maxRes
            if (rate > 1.5) rate = 1.5
            mDisplayWidth = maxRes.toInt()
            mDisplayHeight = (metrics.heightPixels / rate).toInt()
        } else {
            var rate = metrics.heightPixels / maxRes
            if (rate > 1.5) rate = 1.5
            mDisplayHeight = maxRes.toInt()
            mDisplayWidth = (metrics.widthPixels / rate).toInt()
        }

        Log.d("ScreenResolution", "Original: ${metrics.widthPixels}x${metrics.heightPixels}")
        Log.d("ScreenResolution", "Calculated: ${mDisplayWidth}x${mDisplayHeight}")
    }

    private fun startRecordScreen() {
        try {
            // Initialize MediaRecorder
            mMediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(pluginBinding!!.applicationContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            // Create output directory in Movies folder
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val recordingsDir = File(moviesDir, RECORDINGS_SUBDIR)
            if (!recordingsDir.exists()) {
                recordingsDir.mkdirs()
            }

            // Generate filename with timestamp
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val baseName = videoName ?: "Recording"
            mFileName = File(recordingsDir, "${baseName}_$timestamp.mp4").absolutePath
            
            // Configure MediaRecorder
            mMediaRecorder?.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                if (recordAudio) {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                }
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                if (recordAudio) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setOutputFile(mFileName)
                setVideoSize(mDisplayWidth, mDisplayHeight)
                setVideoEncodingBitRate(5 * mDisplayWidth * mDisplayHeight)
                setVideoFrameRate(30)
                
                prepare()
                start()
            }

            // Notify gallery about the new file
            notifyGallery(mFileName!!)

        } catch (e: Exception) {
            Log.e("ScreenRecording", "Recording failed", e)
            throw e
        }
    }

    private fun notifyGallery(filePath: String) {
        try {
            val file = File(filePath)
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = Uri.fromFile(file)
            }
            pluginBinding?.applicationContext?.sendBroadcast(mediaScanIntent)
        } catch (e: Exception) {
            Log.e("GalleryUpdate", "Failed to update gallery", e)
        }
    }

    private fun stopRecordScreen() {
        try {
            mMediaRecorder?.apply {
                stop()
                reset()
            }
        } catch (e: Exception) {
            Log.e("ScreenRecording", "Error stopping recorder", e)
        } finally {
            stopScreenSharing()
        }
    }

    private fun createVirtualDisplay(): VirtualDisplay? {
        return try {
            mMediaProjection?.createVirtualDisplay(
                "ScreenRecording",
                mDisplayWidth,
                mDisplayHeight,
                mScreenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mMediaRecorder?.surface,
                null,
                null
            )
        } catch (e: Exception) {
            Log.e("VirtualDisplay", "Error creating virtual display", e)
            null
        }
    }

    private fun stopScreenSharing() {
        mVirtualDisplay?.release()
        mVirtualDisplay = null
        
        mMediaProjectionCallback?.let {
            mMediaProjection?.unregisterCallback(it)
        }
        mMediaProjection?.stop()
        mMediaProjection = null
        
        mMediaRecorder?.release()
        mMediaRecorder = null
    }

    // FlutterPlugin implementation
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = binding
        val channel = MethodChannel(binding.binaryMessenger, "flutter_screen_recording")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = null
    }

    // ActivityAware implementation
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activityBinding = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activityBinding = binding
    }

    override fun onDetachedFromActivity() {
        // Clean up resources when activity is detached
        stopScreenSharing()
        serviceConnection?.let {
            pluginBinding?.applicationContext?.unbindService(it)
        }
        ForegroundService.stopService(pluginBinding!!.applicationContext)
        activityBinding = null
    }

    // MediaProjection callback
    inner class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            stopScreenSharing()
        }
    }
}
