package com.isvisoft.flutter_screen_recording

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.IOException
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import java.io.File

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding


class FlutterScreenRecordingPlugin : 
    MethodCallHandler, 
    PluginRegistry.ActivityResultListener,
    FlutterPlugin, 
    ActivityAware {

    private var mScreenDensity: Int = 0
    var mMediaRecorder: MediaRecorder? = null
    val mProjectionManager: MediaProjectionManager by lazy {
        ContextCompat.getSystemService(
            pluginBinding!!.applicationContext,
            MediaProjectionManager::class.java
        ) ?: throw Exception("MediaProjectionManager not found")
    }
    var mMediaProjection: MediaProjection? = null
    var mMediaProjectionCallback: MediaProjectionCallback? = null
    var mVirtualDisplay: VirtualDisplay? = null
    private var mDisplayWidth: Int = 1280
    private var mDisplayHeight: Int = 800
    private var videoName: String? = ""
    private var mFileName: String? = ""
    private var mTitle = "Your screen is being recorded"
    private var mMessage = "Your screen is being recorded"
    private var recordAudio: Boolean? = false
    private val SCREEN_RECORD_REQUEST_CODE = 333

    private lateinit var _result: Result

    private var pluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var activityBinding: ActivityPluginBinding? = null

    private var serviceConnection: ServiceConnection? = null
    
    // Permission caching variables
    private var cachedResultCode: Int? = null
    private var cachedData: Intent? = null
    private var isRecordingActive = false

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val context = pluginBinding!!.applicationContext
        
        if (requestCode == SCREEN_RECORD_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // Cache the permission grant
                cachedResultCode = resultCode
                cachedData = data
                startRecordingWithPermission(resultCode, data)
            } else {
                ForegroundService.stopService(context)
                _result.success(false)
            }
            return true
        }
        return false
    }

    private fun startRecordingWithPermission(resultCode: Int, data: Intent) {
        val context = pluginBinding!!.applicationContext
        ForegroundService.startService(context, mTitle, mMessage)
        val intentConnection = Intent(context, ForegroundService::class.java)

        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    startRecordScreen()
                    mMediaProjectionCallback = MediaProjectionCallback()
                    mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data)
                    mMediaProjection?.registerCallback(mMediaProjectionCallback!!, null)
                    mVirtualDisplay = createVirtualDisplay()
                    isRecordingActive = true
                    _result.success(true)
                } catch (e: Throwable) {
                    Log.e("ScreenRecordingPlugin", e.message ?: "Unknown error")
                    _result.success(false)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }

        context.bindService(intentConnection, serviceConnection!!, Activity.BIND_AUTO_CREATE)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        val appContext = pluginBinding!!.applicationContext

        when (call.method) {
            "startRecordScreen" -> {
                try {
                    _result = result
                    val title = call.argument<String?>("title")
                    val message = call.argument<String?>("message")

                    if (!title.isNullOrEmpty()) mTitle = title
                    if (!message.isNullOrEmpty()) mMessage = message

                    val metrics = DisplayMetrics()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val display = activityBinding!!.activity.display
                        display?.getRealMetrics(metrics)
                    } else {
                        @SuppressLint("NewApi")
                        val defaultDisplay = appContext.display
                        defaultDisplay?.getMetrics(metrics)
                    }
                    mScreenDensity = metrics.densityDpi
                    calculateResolution(metrics)
                    videoName = call.argument<String?>("name")
                    recordAudio = call.argument<Boolean?>("audio")

                    // Check if we already have permission and not currently recording
                    if (cachedResultCode != null && cachedData != null && !isRecordingActive) {
                        startRecordingWithPermission(cachedResultCode!!, cachedData!!)
                        return
                    }

                    // Otherwise request new permission
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
                    isRecordingActive = false
                    if (mMediaRecorder != null) {
                        stopRecordScreen()
                        result.success(mFileName)
                    } else {
                        result.success("")
                    }
                } catch (e: Exception) {
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

        var maxRes = 1280.0
        if (metrics.scaledDensity >= 3.0f) {
            maxRes = 1920.0
        }
        
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

    private var videoCounter = 1  

    private fun startRecordScreen() {
        try {
            mMediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(pluginBinding!!.applicationContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            if (!moviesDir.exists()) {
                moviesDir.mkdirs()
            }

            mFileName = generateUniqueFileName(moviesDir)
            
            mMediaRecorder?.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                if (recordAudio == true) {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                } else {
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                }
                setOutputFile(mFileName)
                setVideoSize(mDisplayWidth, mDisplayHeight)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(5 * mDisplayWidth * mDisplayHeight)
                setVideoFrameRate(30)
                
                prepare()
                start()
            }

            mFileName?.let { notifyGallery(it) }

        } catch (e: Exception) {
            Log.e("ScreenRecording", "Recording setup failed", e)
        }
    }

    private fun generateUniqueFileName(directory: File): String {
        var fileName: String
        var file: File
        var counter = videoCounter
        
        do {
            fileName = "${directory.absolutePath}/${videoName}_${counter}.mp4"
            file = File(fileName)
            counter++
        } while (file.exists())
        
        videoCounter = counter  
        return fileName
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
            mMediaRecorder?.stop()
            mMediaRecorder?.reset()
        } catch (e: Exception) {
            Log.e("ScreenRecording", "Error stopping recording", e)
        } finally {
            stopScreenSharing()
        }
    }

    private fun createVirtualDisplay(): VirtualDisplay? {
        try {
            return mMediaProjection?.createVirtualDisplay(
                "MainActivity", mDisplayWidth, mDisplayHeight, mScreenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mMediaRecorder?.surface, null, null
            )
        } catch (e: Exception) {
            Log.e("VirtualDisplay", "Creation failed", e)
            return null
        }
    }

    private fun stopScreenSharing() {
        if (mVirtualDisplay != null) {
            mVirtualDisplay?.release()
            if (mMediaProjection != null && mMediaProjectionCallback != null) {
                mMediaProjection?.unregisterCallback(mMediaProjectionCallback!!)
                mMediaProjection?.stop()
                mMediaProjection = null
            }
            Log.d("ScreenSharing", "Stopped screen sharing")
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = binding
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        val channel = MethodChannel(pluginBinding!!.binaryMessenger, "flutter_screen_recording")
        channel.setMethodCallHandler(this)
        activityBinding!!.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activityBinding = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activityBinding = binding
    }

    override fun onDetachedFromActivity() {
        activityBinding = null
    }

    inner class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            mMediaRecorder?.reset()
            mMediaProjection = null
            stopScreenSharing()
            isRecordingActive = false
        }
    }
}
