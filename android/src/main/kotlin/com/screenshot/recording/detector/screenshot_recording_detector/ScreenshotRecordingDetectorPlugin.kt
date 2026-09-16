package com.screenshot.recording.detector.screenshot_recording_detector

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.util.Log
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.lifecycle.FlutterLifecycleAdapter
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.Locale
import java.util.function.Consumer

/** Android 21+. No root URI query, hidden API, display polling or storage request UI. */
class ScreenshotRecordingDetectorPlugin : FlutterPlugin, MethodChannel.MethodCallHandler,
    ActivityAware, EventChannel.StreamHandler {
    private lateinit var channel: MethodChannel
    private lateinit var events: EventChannel
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var activity: Activity? = null
    private var lifecycle: Lifecycle? = null
    private var sink: EventChannel.EventSink? = null
    private var requested = false
    private var running = false
    @Volatile private var generation = 0
    private var screenshotStop: (() -> Unit)? = null
    private var recordingStop: (() -> Unit)? = null
    private var legacy: LegacyScreenshots? = null
    private var screenshotStatus = "notInitialized"
    private var recordingStatus = "notInitialized"
    private var recording: Boolean? = null
    private var blockRequested = false
    private var secureAddedByPlugin = false

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> startIfReady()
            Lifecycle.Event.ON_STOP -> stopActive("inactive")
            Lifecycle.Event.ON_DESTROY -> stopActive("inactive")
            else -> Unit
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "screenshot_recording_detector")
        events = EventChannel(binding.binaryMessenger, "screenshot_recording_events")
        channel.setMethodCallHandler(this)
        events.setStreamHandler(this)
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        sink = events
        // Replay current recording state if initialize happened before listen.
        recording?.let { emitRecording(it) }
    }
    override fun onCancel(arguments: Any?) { sink = null }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "initialize" -> {
                    requested = true
                    // A repeated initialize can recover after permission changes/failure.
                    if (running && (screenshotStatus == "permissionDenied" ||
                            screenshotStatus == "unavailable" || recordingStatus == "unavailable")) {
                        stopActive("inactive")
                    }
                    startIfReady()
                    result.success(null)
                }
                "isScreenRecording" -> result.success(recording == true)
                "getDetectionStatus" -> result.success(mapOf(
                    "platform" to "android",
                    "androidApi" to Build.VERSION.SDK_INT,
                    "initialized" to requested,
                    "active" to running,
                    "screenshot" to screenshotStatus,
                    "recording" to if (Build.VERSION.SDK_INT < 35) "unsupported" else recordingStatus,
                    "recordingSupported" to (Build.VERSION.SDK_INT >= 35),
                    "isRecording" to recording,
                    "requiredPermission" to if (Build.VERSION.SDK_INT < 34) legacyPermission() else null
                ))
                "setBlockScreenshots" -> {
                    val enable = call.arguments as? Boolean
                    if (enable == null) {
                        result.error("INVALID_ARGUMENT", "Expected a Boolean", null)
                    } else {
                        // Stored if Activity is not attached yet; applied on attach.
                        blockRequested = enable
                        applySecureFlag()
                        result.success(null)
                    }
                }
                "dispose" -> {
                    requested = false
                    stopActive("notInitialized")
                    // Security preference is intentionally NOT cleared by dispose.
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Native method failed: ${call.method}", e)
            result.error("DETECTOR_ERROR", e.javaClass.simpleName, null)
        }
    }

    private fun legacyPermission(): String = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun startIfReady() {
        if (!requested || running) return
        val a = activity
        if (a == null || lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != true) {
            screenshotStatus = "inactive"
            recordingStatus = "inactive"
            return
        }
        val c = context ?: return
        running = true
        val token = ++generation
        screenshotStatus = "unavailable"
        recordingStatus = if (Build.VERSION.SDK_INT >= 35) "unavailable" else "unsupported"
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                screenshotStop = Api34.register(a) {
                    if (running && generation == token) emit("screenshot")
                }
                screenshotStatus = "native"
            } catch (e: RuntimeException) {
                Log.w(TAG, "Screenshot callback registration failed", e)
            }
        } else if (c.checkCallingOrSelfPermission(legacyPermission()) != PackageManager.PERMISSION_GRANTED) {
            screenshotStatus = "permissionDenied"
        } else {
            val detector = LegacyScreenshots(c, main, {
                if (running && generation == token) emit("screenshot")
            }, { denied ->
                if (running && generation == token) {
                    screenshotStatus = if (denied) "permissionDenied" else "unavailable"
                }
            })
            legacy = detector
            try {
                detector.start()
                screenshotStatus = "heuristic"
            } catch (e: RuntimeException) {
                detector.stop()
                legacy = null
                screenshotStatus = if (e is SecurityException) "permissionDenied" else "unavailable"
                Log.w(TAG, "Legacy observer registration failed", e)
            }
        }
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                recordingStop = Api35.register(a) { visible ->
                    if (running && generation == token) {
                        recordingStatus = if (visible) "visible" else "notVisible"
                        if (recording != visible) {
                            recording = visible
                            emitRecording(visible)
                        }
                    }
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Recording callback registration failed", e)
            }
        }
    }

    private fun stopActive(status: String) {
        running = false
        ++generation // Reject already queued callbacks from earlier registrations.
        val screenshotCleanup = screenshotStop
        val recordingCleanup = recordingStop
        screenshotStop = null
        recordingStop = null
        safeCleanup { screenshotCleanup?.invoke() }
        safeCleanup { recordingCleanup?.invoke() }
        legacy?.stop()
        legacy = null
        recording = null // Inactive is unknown, not a synthetic recording-stop event.
        screenshotStatus = status
        recordingStatus = status
    }

    private fun safeCleanup(action: () -> Unit) {
        try { action() } catch (e: RuntimeException) {
            Log.w(TAG, "Cleanup failed", e)
        }
    }

    private fun emit(type: String, extra: Map<String, Any> = emptyMap()) {
        sink?.success(mapOf<String, Any>(
            "type" to type, "timestamp" to System.currentTimeMillis(), "platform" to "android"
        ) + extra)
    }
    private fun emitRecording(value: Boolean) = emit("recording", mapOf("isRecording" to value))

    private fun applySecureFlag() {
        val window = activity?.window ?: return
        if (blockRequested) {
            if (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                secureAddedByPlugin = true
            }
        } else if (secureAddedByPlugin) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            secureAddedByPlugin = false
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        lifecycle = FlutterLifecycleAdapter.getActivityLifecycle(binding)
        lifecycle?.addObserver(lifecycleObserver)
        applySecureFlag()
        startIfReady()
    }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) =
        onAttachedToActivity(binding)
    override fun onDetachedFromActivityForConfigChanges() = detachActivity()
    override fun onDetachedFromActivity() = detachActivity()
    private fun detachActivity() {
        stopActive(if (requested) "inactive" else "notInitialized")
        lifecycle?.removeObserver(lifecycleObserver)
        lifecycle = null
        // Remove only our own flag from the outgoing window.
        if (secureAddedByPlugin) safeCleanup {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        secureAddedByPlugin = false
        activity = null
    }
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        requested = false
        detachActivity()
        blockRequested = false
        channel.setMethodCallHandler(null)
        events.setStreamHandler(null)
        sink = null
        context = null
        main.removeCallbacksAndMessages(null)
    }

    companion object { private const val TAG = "ScreenshotDetector" }
}

// Isolated helpers keep newer Android types out of fields used on old devices.
@RequiresApi(34)
private object Api34 {
    fun register(activity: Activity, emit: () -> Unit): () -> Unit {
        val callback = Activity.ScreenCaptureCallback { emit() }
        activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
        return { activity.unregisterScreenCaptureCallback(callback) }
    }
}

@RequiresApi(35)
private object Api35 {
    fun register(activity: Activity, emit: (Boolean) -> Unit): () -> Unit {
        val manager = activity.windowManager
        val callback = Consumer<Int> { state ->
            emit(state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE)
        }
        val initial = manager.addScreenRecordingCallback(activity.mainExecutor, callback)
        callback.accept(initial)
        return { manager.removeScreenRecordingCallback(callback) }
    }
}

/** Only this fixed public image collection is queried, NEVER a notification URI. */
internal object LegacyMediaQuery {
    val collection: Uri get() = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    fun looksLikeScreenshot(metadata: String): Boolean {
        val text = metadata.lowercase(Locale.ROOT)
        return listOf("screenshot", "screen_shot", "screen-shot", "screen shot")
            .any { text.contains(it) }
    }

    fun <T> safely(onFailure: (RuntimeException) -> Unit, query: () -> T): T? = try {
        query()
    } catch (e: RuntimeException) {
        onFailure(e)
        null
    }
}

/** Foreground-only, permission-gated heuristic for API 21..33. */
private class LegacyScreenshots(
    private val context: Context,
    private val main: Handler,
    private val detected: () -> Unit,
    private val failed: (Boolean) -> Unit
) {
    private val thread = HandlerThread("ScreenshotMediaQuery")
    private lateinit var worker: Handler
    private var observer: ContentObserver? = null
    @Volatile private var active = false
    private var startedSeconds = 0L
    private val seen = LinkedHashSet<Long>() // One fixed collection => ID namespace is stable.
    private var scanPending = false
    private val scanTask = Runnable { scanPending = false; scan() }
    private val retryTask = Runnable { scan() }
    private val finalRetryTask = Runnable { scan() }

    fun start() {
        if (active) return
        startedSeconds = System.currentTimeMillis() / 1000
        active = true
        thread.start()
        worker = Handler(thread.looper)
        val observerInstance = object : ContentObserver(worker) {
            override fun onChange(selfChange: Boolean) { schedule() }
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // uri may be null, a root, collection, item, or OEM-specific URI.
                // Notification is a signal only: never send it to ContentResolver.query.
                schedule()
            }
        }
        observer = observerInstance
        context.contentResolver.registerContentObserver(LegacyMediaQuery.collection, true, observerInstance)
    }

    private fun schedule() {
        if (!active) return
        // Coalesce bursts without indefinitely postponing the first scan.
        if (!scanPending) {
            scanPending = true
            worker.postDelayed(scanTask, 150)
        }
        worker.removeCallbacks(retryTask)
        worker.removeCallbacks(finalRetryTask)
        worker.postDelayed(retryTask, 650)
        worker.postDelayed(finalRetryTask, 1600)
    }

    private fun scan() {
        if (!active) return
        val now = System.currentTimeMillis() / 1000
        val cutoff = maxOf(startedSeconds, now - 10)
        val columns = mutableListOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )
        if (Build.VERSION.SDK_INT >= 29) {
            columns.add(MediaStore.Images.Media.RELATIVE_PATH)
            columns.add(MediaStore.Images.Media.IS_PENDING)
        } else {
            @Suppress("DEPRECATION")
            columns.add(MediaStore.Images.Media.DATA)
        }
        LegacyMediaQuery.safely({ error ->
            Log.w("ScreenshotDetector", "Image collection query failed", error)
            main.post { if (active) failed(error is SecurityException) }
        }) {
            context.contentResolver.query(
                LegacyMediaQuery.collection, columns.toTypedArray(),
                "${MediaStore.Images.Media.DATE_ADDED} >= ?", arrayOf(cutoff.toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
            )?.use { cursor ->
                var count = 0
                while (active && count++ < 100 && cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    if (id in seen) continue
                    val added = cursor.getLong(2)
                    if (added < cutoff || added > now + 1) continue
                    if (Build.VERSION.SDK_INT >= 29 && cursor.getInt(4) != 0) continue
                    val metadata = "${cursor.getString(1).orEmpty()} ${cursor.getString(3).orEmpty()}"
                    if (!LegacyMediaQuery.looksLikeScreenshot(metadata)) continue
                    seen.add(id)
                    if (seen.size > 512) seen.iterator().let { it.next(); it.remove() }
                    main.post { if (active) detected() }
                }
            } ?: run { main.post { if (active) failed(false) } }
        }
    }

    fun stop() {
        active = false
        observer?.let {
            try { context.contentResolver.unregisterContentObserver(it) }
            catch (e: RuntimeException) { Log.w("ScreenshotDetector", "Observer cleanup failed", e) }
        }
        observer = null
        if (::worker.isInitialized) worker.removeCallbacksAndMessages(null)
        thread.quitSafely()
        // Do not join the worker on the UI thread: an OEM query may be blocked.
    }
}
