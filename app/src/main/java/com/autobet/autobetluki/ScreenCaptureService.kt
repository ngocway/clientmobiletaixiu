
package com.autobet.autobetluki

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private lateinit var imageReader: ImageReader
    private lateinit var handler: Handler
    private val handlerThread = HandlerThread("ScreenCapture")
    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val captureMutex = Mutex()
    private var captureJob: Job? = null
    private val isProcessingResponse = AtomicBoolean(false) // Flag to skip capture during response handling/auto click
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        const val ACTION_START = "com.autobet.autobetluki.START"
        const val ACTION_STOP = "com.autobet.autobetluki.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        private const val NOTIFICATION_CHANNEL_ID = "ScreenCapture"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "ScreenCaptureSvc"
        private const val TEMPLATE_SCAN_TAG = "TemplateScan"
        private const val JPEG_QUALITY = 60
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "!!! OpenCV FAILED to load. Template scanning will not work.")
        } else {
            Log.i(TAG, "OpenCV loaded successfully.")
        }
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        Log.i(TAG, "Service created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Autobet Service")
            .setContentText("Running...")
            .setSmallIcon(applicationInfo.icon)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (resultCode != 0 && data != null) {
                    Log.i(TAG, "Permission granted, starting screen capture.")
                    val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                    ensureServiceScope()
                    startScreenCapture()
                } else {
                    Log.e(TAG, "Missing or invalid permission data. Cannot start capture.")
                }
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stop action received. Shutting down.")
                stopScreenCapture()
            }
            else -> {
                Log.w(TAG, "Unhandled action: ${intent?.action}")
            }
        }

        return START_NOT_STICKY
    }

    private fun startScreenCapture() {
        val projection = mediaProjection ?: return
        Log.i(TAG, "Configuring screen capture pipeline...")

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        mediaProjectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.e(TAG, "MediaProjection stopped by system! Forcing service shutdown.")
                handler.post { stopScreenCapture() }
            }
        }.also { callback ->
            projection.registerCallback(callback, handler)
        }

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, handler
        )
        Log.i(TAG, "Virtual display created at ${screenWidth}x${screenHeight}. Starting capture loop.")

        captureJob?.cancel()
        captureJob = serviceScope.launch {
            while (isActive) {
                // Skip capture if currently processing response or auto clicking
                if (!isProcessingResponse.get()) {
                    Log.d(TAG, "-------------------- New Capture Cycle --------------------")
                    captureMutex.withLock {
                        captureAndUpload()
                    }
                } else {
                    Log.d(TAG, "Skipping capture - currently processing response or auto clicking")
                }
                delay(5_000L) // Capture every 5 seconds
            }
            Log.i(TAG, "Capture loop has ended.")
        }
    }

    private fun findTemplateLocation(bitmap: Bitmap, templateName: String): Point? {
        Log.d(TEMPLATE_SCAN_TAG, "Scanning for template: '$templateName'")
        val screenGray = Mat()
        Utils.bitmapToMat(bitmap, screenGray)
        Imgproc.cvtColor(screenGray, screenGray, Imgproc.COLOR_RGBA2GRAY)

        val resId = resources.getIdentifier(templateName, "drawable", packageName)
        if (resId == 0) {
            Log.e(TEMPLATE_SCAN_TAG, "Template resource not found: $templateName")
            return null
        }

        var templateImage: Mat? = null
        try {
            templateImage = Utils.loadResource(this, resId, Imgcodecs.IMREAD_GRAYSCALE)
            if (templateImage.empty()) {
                Log.w(TEMPLATE_SCAN_TAG, "Could not load resource as image: $templateName")
                return null
            }

            var bestMatch: Core.MinMaxLocResult? = null
            var bestScale = 0.0
            var bestMethod = Imgproc.TM_CCOEFF_NORMED
            var bestSimilarity = 0.0
            var bestMatchLoc: org.opencv.core.Point? = null

            // For "ten" template, try multiple matching methods and use the best result
            val matchMethods = if (templateName == "ten") {
                listOf(
                    Imgproc.TM_CCOEFF_NORMED,
                    Imgproc.TM_CCORR_NORMED,
                    Imgproc.TM_SQDIFF_NORMED
                )
            } else {
                listOf(Imgproc.TM_CCOEFF_NORMED)
            }

            // Expand scale range for better matching (100% down to 70%)
            for (scale in (20 downTo 14).map { it / 20.0 }) { // 100%, 95%, 90%, 85%, 80%, 75%, 70%
                val templateW = (templateImage.width() * scale).roundToInt()
                val templateH = (templateImage.height() * scale).roundToInt()

                if (templateW <= 0 || templateH <= 0 || templateW > screenGray.width() || templateH > screenGray.height()) continue

                val resizedTemplate = Mat()
                Imgproc.resize(templateImage, resizedTemplate, Size(templateW.toDouble(), templateH.toDouble()))

                // Try each matching method
                for (matchMethod in matchMethods) {
                    val result = Mat()
                    Imgproc.matchTemplate(screenGray, resizedTemplate, result, matchMethod)
                    val mmr = Core.minMaxLoc(result)
                    
                    // For TM_SQDIFF_NORMED, lower values are better, so we need to invert
                    val similarity = if (matchMethod == Imgproc.TM_SQDIFF_NORMED) {
                        1.0 - mmr.minVal
                    } else {
                        mmr.maxVal
                    }

                    if (bestMatch == null || similarity > bestSimilarity) {
                        bestMatch = mmr
                        bestScale = scale
                        bestMethod = matchMethod
                        bestSimilarity = similarity
                        bestMatchLoc = if (matchMethod == Imgproc.TM_SQDIFF_NORMED) mmr.minLoc else mmr.maxLoc
                    }
                    result.release()
                }
                resizedTemplate.release()
            }
            // Lower threshold for "ten" template (0.50) vs others (0.75)
            val threshold = if (templateName == "ten") 0.50 else 0.75
            if (bestSimilarity >= threshold && bestMatchLoc != null) {
                val clickX = (bestMatchLoc.x + (templateImage.width() * bestScale) / 2).roundToInt()
                val clickY = (bestMatchLoc.y + (templateImage.height() * bestScale) / 2).roundToInt()
                Log.i(TEMPLATE_SCAN_TAG, "---> FOUND '$templateName' at ($clickX, $clickY) with similarity ${String.format("%.2f", bestSimilarity)} (method=$bestMethod)")
                return Point(clickX, clickY)
            } else {
                Log.i(TEMPLATE_SCAN_TAG, "     NOT FOUND: '$templateName'. Best similarity was ${String.format("%.2f", bestSimilarity)}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TEMPLATE_SCAN_TAG, "Error processing resource '$templateName'.", e)
            return null
        } finally {
            templateImage?.release()
            screenGray.release()
        }
    }

    private fun parseRectFromString(coords: String?): Rect? {
        if (coords.isNullOrBlank()) return null
        return try {
            val points = coords.split(';')
            val p1 = points[0].split(':')
            val p2 = points[1].split(':')
            val x1 = p1[0].toInt()
            val y1 = p1[1].toInt()
            val x2 = p2[0].toInt()
            val y2 = p2[1].toInt()
            Rect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse coordinates: '$coords'", e)
            null
        }
    }

    private fun Bitmap.toByteArray(): ByteArray {
        val stream = ByteArrayOutputStream()
        this.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return stream.toByteArray()
    }

    private suspend fun captureAndUpload() {
        Log.d(TAG, "Attempting to acquire image...")
        val image = waitForImage() ?: run {
            Log.w(TAG, "No image available for capture, skipping this cycle.")
            return
        }

        var fullBitmap: Bitmap? = null
        var originalFullBitmap: Bitmap? = null
        var secondsBitmap: Bitmap? = null
        var betAmountBitmap: Bitmap? = null

        try {
            fullBitmap = image.toBitmap() ?: run {
                Log.e(TAG, "Failed to convert Image to Bitmap.")
                return
            }
            Log.i(TAG, "Screenshot captured successfully (${fullBitmap.width}x${fullBitmap.height}).")

            val prefs = getSharedPreferences("bet_config", Context.MODE_PRIVATE)
            val secondsRegionString = prefs.getString("secondsRegion", null)
            val betAmountRegionString = prefs.getString("betAmountRegion", null)
            val secondsRect = parseRectFromString(secondsRegionString)
            val betAmountRect = parseRectFromString(betAmountRegionString)

            // Crop regions before resizing (to maintain accuracy)
            secondsBitmap = secondsRect?.let { rect -> fullBitmap.crop(rect) }
            betAmountBitmap = betAmountRect?.let { rect -> fullBitmap.crop(rect) }

            // Resize full screenshot to 50% before compression to reduce upload size
            originalFullBitmap = fullBitmap
            val resizedFullBitmap = fullBitmap.resize(0.5f)
            fullBitmap = resizedFullBitmap
            Log.i(TAG, "Resized full screenshot to 50%: ${resizedFullBitmap.width}x${resizedFullBitmap.height}")

            val multipartBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            multipartBody.addFormDataPart("file", "screenshot.jpg", fullBitmap.toByteArray().toRequestBody("image/jpeg".toMediaTypeOrNull()))
            secondsBitmap?.let { multipartBody.addFormDataPart("seconds_image", "seconds.jpg", it.toByteArray().toRequestBody("image/jpeg".toMediaTypeOrNull())) }
            betAmountBitmap?.let { multipartBody.addFormDataPart("bet_amount_image", "bet_amount.jpg", it.toByteArray().toRequestBody("image/jpeg".toMediaTypeOrNull())) }
            
            val deviceName = prefs.getString("deviceName", "")?.trim().orEmpty()
            val betOptionRaw = prefs.getString("betOption", "cược Tài")?.trim().orEmpty()
            val bettingMethod = when {
                betOptionRaw.contains("Tài", ignoreCase = true) -> "Tài"
                betOptionRaw.contains("Xỉu", ignoreCase = true) -> "Xỉu"
                else -> betOptionRaw
            }
            multipartBody.addFormDataPart("device_name", deviceName)
            multipartBody.addFormDataPart("betting_method", bettingMethod)
            secondsRegionString?.let { multipartBody.addFormDataPart("seconds_region_coords", it) }
            betAmountRegionString?.let { multipartBody.addFormDataPart("bet_amount_region_coords", it) }

            Log.i(TAG, "Preparing to upload screenshot for device: '$deviceName' with method: '$bettingMethod'")
            val request = Request.Builder()
                .url("https://lukistar.space/api/mobile/analyze")
                .post(multipartBody.build())
                .build()

            try {
                isProcessingResponse.set(true) // Set flag to skip captures during processing
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (response.isSuccessful && responseBody != null) {
                        Log.i(TAG, "UPLOAD SUCCESS (code=${response.code}).")
                        handleServerResponse(responseBody, originalFullBitmap ?: fullBitmap)
                    } else {
                        Log.e(TAG, "!!! UPLOAD FAILED (code=${response.code}). Server Response: $responseBody")
                        isProcessingResponse.set(false) // Reset flag on failure
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "!!! UPLOAD FAILED due to IOException.", e)
                isProcessingResponse.set(false) // Reset flag on exception
            }

        } catch (e: Exception) {
            Log.e(TAG, "An unexpected error occurred in captureAndUpload.", e)
            isProcessingResponse.set(false) // Reset flag on exception
        } finally {
            fullBitmap?.recycle()
            originalFullBitmap?.recycle()
            secondsBitmap?.recycle()
            betAmountBitmap?.recycle()
            image.close()
            Log.d(TAG, "All bitmaps and image are released.")
        }
    }

    private suspend fun handleServerResponse(responseBody: String, screenBitmap: Bitmap) {
        try {
            val json = JSONObject(responseBody)
            val imageType = json.optString("image_type", "")
            val secondsValue = json.opt("seconds")
            val seconds = (secondsValue as? Number)?.toInt()

            if (seconds != null) {
                Log.d(TAG, "Server response parsed: image_type='$imageType', seconds=$seconds")
            } else {
                Log.d(TAG, "Server response parsed: image_type='$imageType'")
            }

            when (imageType) {
                "BETTING" -> {
                    if (seconds == null) {
                        Log.w(TAG, "Server response for BETTING did not include 'seconds'. Skipping history click.")
                        isProcessingResponse.set(false) // Reset flag when no action needed
                    } else if (seconds in 35..59) {
                        Log.i(TAG, "CLICK CONDITION MET: image_type is BETTING and seconds ($seconds) is in range [35, 59].")
                        val historyLocation = findTemplateLocation(screenBitmap, "history")
                        if (historyLocation != null) {
                            AutoClickService.requestClick(historyLocation.x, historyLocation.y)
                            delay(500) // Small delay for click to register
                        } else {
                            Log.w(TAG, "Click condition met, but 'history' template was not found on screen.")
                        }
                        isProcessingResponse.set(false) // Reset flag after click
                    } else {
                        Log.d(TAG, "Seconds=$seconds outside [35, 59], skipping history click.")
                        isProcessingResponse.set(false) // Reset flag when no action needed
                    }
                }
                "HISTORY" -> {
                    Log.i(TAG, "HISTORY screen detected. Clicking fixed top-left position to close.")
                    AutoClickService.requestClick(5, 5)
                    delay(500) // Small delay for click to register

                    val winLossRaw = json.optString("win_loss", "")
                    val winLoss = winLossRaw.lowercase()
                    val shouldPerformFollowUp = winLoss == "win" || winLoss == "loss"

                    if (!shouldPerformFollowUp) {
                        Log.i(TAG, "win_loss '$winLossRaw' not actionable; skipping follow-up clicks.")
                        isProcessingResponse.set(false) // Reset flag when no follow-up needed
                        return
                    }

                    // Calculate click count based on win_loss and bet_amount
                    val betAmount = json.optDouble("bet_amount", 0.0)
                    val clickCount = when (winLoss) {
                        "win" -> 1
                        "loss" -> ((betAmount * 2) / 1000.0).toInt().coerceAtLeast(0)
                        else -> 0
                    }
                    Log.i(TAG, "Calculated click count: $clickCount (win_loss='$winLossRaw', bet_amount=$betAmount)")

                    Log.i(
                        TAG,
                        "Scheduling follow-up clicks with win_loss='$winLossRaw', bet_amount=${json.optDouble("bet_amount", 0.0)}, clickCount=$clickCount"
                    )
                    scheduleHistoryFollowUpClicks(clickCount)
                }
                else -> {
                    isProcessingResponse.set(false) // Reset flag for unknown image types
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse or handle server response.", e)
            isProcessingResponse.set(false) // Reset flag on error
        }
    }

    private fun scheduleHistoryFollowUpClicks(clickCount: Int) {
        serviceScope.launch {
            try {
                delay(200L) // Initial delay 0.2 seconds
                
                // If clickCount >= 15, click cuoc.jpg first
                if (clickCount >= 15) {
                    val cuocBitmap = captureSingleBitmap()
                    val cuocLocation = cuocBitmap?.let { findTemplateLocation(it, "cuoc") }
                    cuocBitmap?.recycle()
                    
                    if (cuocLocation != null) {
                        Log.i(TAG, "Click count is $clickCount (>= 15). Found 'cuoc' template at $cuocLocation. Clicking first.")
                        AutoClickService.requestClick(cuocLocation.x, cuocLocation.y)
                        delay(500) // Small delay for click to register
                    } else {
                        Log.w(TAG, "Click count is $clickCount (>= 15) but 'cuoc' template not found. Proceeding with ten/one clicks.")
                    }
                }
                
                // Calculate clicks first to determine if we need to scan for ten.jpg
                val tenClickCount = clickCount / 10
                val oneClickCount = clickCount % 10

                if (clickCount <= 0) {
                    Log.i(TAG, "Click count is $clickCount, skipping clicks.")
                    return@launch
                }

                val screenBitmap = captureSingleBitmap()
                
                // Only scan for ten.jpg if we need to click it (tenClickCount > 0)
                val tenLocation = if (tenClickCount > 0) {
                    screenBitmap?.let { findTemplateLocation(it, "ten") }
                } else {
                    null
                }
                // Always scan for one.jpg as we might need it
                val oneLocation = screenBitmap?.let { findTemplateLocation(it, "one") }
                screenBitmap?.recycle()

                if (tenLocation == null && oneLocation == null) {
                    Log.w(TAG, "Neither 'ten' nor 'one' template found after history close.")
                } else {
                    
                    Log.i(TAG, "Click plan: $clickCount total = $tenClickCount x ten.jpg + $oneClickCount x one.jpg")
                    
                    var usedFallback = false
                    
                    // Click ten.jpg first (if needed and found)
                    if (tenClickCount > 0) {
                        if (tenLocation != null) {
                            Log.i(TAG, "Found 'ten' template at $tenLocation. Clicking $tenClickCount time(s).")
                            repeat(tenClickCount) { index ->
                                AutoClickService.requestClick(tenLocation.x, tenLocation.y)
                                if (tenClickCount > 1 && index < tenClickCount - 1) {
                                    val delayMillis = (200..400).random().toLong()
                                    delay(delayMillis)
                                }
                            }
                        } else {
                            Log.w(TAG, "'ten' template not found but needed ($tenClickCount clicks). Falling back to 'one'.")
                            // Fallback: if ten not found but needed, use one instead
                            if (oneLocation != null) {
                                val fallbackCount = tenClickCount * 10 + oneClickCount
                                Log.i(TAG, "Clicking 'one' template $fallbackCount time(s) as fallback.")
                                repeat(fallbackCount) { index ->
                                    AutoClickService.requestClick(oneLocation.x, oneLocation.y)
                                    if (fallbackCount > 1 && index < fallbackCount - 1) {
                                        val delayMillis = (100..300).random().toLong()
                                        delay(delayMillis)
                                    }
                                }
                                usedFallback = true
                            }
                        }
                    }
                    
                    // Click one.jpg for remaining clicks (if needed and found, and fallback wasn't used)
                    if (!usedFallback && oneClickCount > 0 && oneLocation != null) {
                        Log.i(TAG, "Found 'one' template at $oneLocation. Clicking $oneClickCount time(s).")
                        repeat(oneClickCount) { index ->
                            AutoClickService.requestClick(oneLocation.x, oneLocation.y)
                            if (oneClickCount > 1 && index < oneClickCount - 1) {
                                val delayMillis = (100..300).random().toLong()
                                delay(delayMillis)
                            }
                        }
                    } else if (!usedFallback && oneClickCount > 0 && oneLocation == null) {
                        Log.w(TAG, "'one' template not found but needed ($oneClickCount clicks).")
                    }
                }

                delay(100L) // Wait 0.1 seconds before clicking betting button
                val bettingBitmap = captureSingleBitmap()
                val bettingLocation = bettingBitmap?.let { findTemplateLocation(it, "betting_button") }
                bettingBitmap?.recycle()
                if (bettingLocation != null) {
                    Log.i(TAG, "Found 'betting_button' template at $bettingLocation. Clicking.")
                    AutoClickService.requestClick(bettingLocation.x, bettingLocation.y)
                    delay(500) // Small delay for click to register
                } else {
                    Log.w(TAG, "'betting_button' template not found after clicks.")
                }
            } finally {
                isProcessingResponse.set(false) // Reset flag after all auto clicks complete
            }
        }
    }

    private suspend fun captureSingleBitmap(): Bitmap? {
        return captureMutex.withLock {
            val image = waitForImage() ?: return@withLock null
            try {
                image.toBitmap()
            } finally {
                image.close()
            }
        }
    }

    private suspend fun waitForImage(): Image? {
        repeat(10) { attempt ->
            val image = imageReader.acquireLatestImage()
            if (image != null) {
                return image
            }
            delay(50)
        }
        Log.w(TAG, "Timed out waiting for image after 500ms.")
        return null
    }

    private fun Image.toBitmap(): Bitmap? {
        val planes = this.planes
        if (planes.isEmpty()) return null
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * this.width

        val bitmapWidth = this.width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, this.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        return if (bitmapWidth != this.width) {
            Bitmap.createBitmap(bitmap, 0, 0, this.width, this.height).also { bitmap.recycle() }
        } else {
            bitmap
        }
    }

    private fun Bitmap.crop(rect: Rect): Bitmap? {
        if (!Rect(0, 0, this.width, this.height).contains(rect)) {
            Log.w(TAG, "Crop rect $rect is outside the bitmap bounds (${this.width}x${this.height})")
            return null
        }
        return Bitmap.createBitmap(this, rect.left, rect.top, rect.width(), rect.height())
    }

    private fun Bitmap.resize(scale: Float): Bitmap {
        val newWidth = (this.width * scale).toInt()
        val newHeight = (this.height * scale).toInt()
        return Bitmap.createScaledBitmap(this, newWidth, newHeight, true)
    }

    private fun stopScreenCapture() {
        Log.i(TAG, "Stopping screen capture service...")
        serviceJob.cancel()
        captureJob?.cancel()
        mediaProjectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        mediaProjectionCallback = null
        if (this::imageReader.isInitialized) imageReader.close()
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        handlerThread.quitSafely()
        Log.i(TAG, "Service destroyed.")
    }

    private fun ensureServiceScope() {
        if (!serviceJob.isActive) {
            serviceJob = SupervisorJob()
            serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
            Log.d(TAG, "Service scope was inactive, recreated.")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Screen Capture", NotificationManager.IMPORTANCE_DEFAULT)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
