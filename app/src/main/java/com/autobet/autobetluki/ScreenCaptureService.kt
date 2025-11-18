
package com.autobet.autobetluki

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
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
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.os.Looper
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
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    private val clickOverlays = mutableListOf<View>() // Store overlay views to remove them later
    @Volatile private var lastBettingButtonClickTime: Long = 0L // Timestamp when betting_button was successfully clicked
    @Volatile private var consecutiveUnknownCount: Int = 0 // Count consecutive HISTORY JSONs with win_loss="unknown"
    @Volatile private var consecutiveLossCount: Int = 0 // Count consecutive losses
    @Volatile private var skipCount: Int = 0 // Number of rounds to skip (when consecutiveLossCount >= 4)
    @Volatile private var consecutivePlaceBetSuccessCount: Int = 0 // Count consecutive successful "Đặt Cược" button clicks
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
        private const val HISTORY_JSON_FILE_NAME = "latest_history_response.json"
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
                    // Reset consecutive unknown count when restarting
                    consecutiveUnknownCount = 0
                    consecutiveLossCount = 0
                    skipCount = 0
                    consecutivePlaceBetSuccessCount = 0
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

        // Get real physical screen dimensions (not affected by orientation)
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val realSize = android.graphics.Point()
        display.getRealSize(realSize)
        val screenWidth = realSize.x
        val screenHeight = realSize.y
        
        val displayMetrics = resources.displayMetrics

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
                // Skip capture if currently:
                // - Waiting for server response
                // - Matching templates
                // - Clicking buttons (Cược, 1K, 10K, 50K, Đặt Cược)
                // - Within 20 seconds after successfully clicking betting_button
                val currentTime = System.currentTimeMillis()
                val timeSinceBettingClick = currentTime - lastBettingButtonClickTime
                val isWithinBettingCooldown = lastBettingButtonClickTime > 0 && timeSinceBettingClick < 20_000L
                
                if (!isProcessingResponse.get() && !isWithinBettingCooldown) {
                    Log.d(TAG, "-------------------- New Capture Cycle --------------------")
                    captureMutex.withLock {
                        captureAndUpload()
                    }
                } else {
                    if (isProcessingResponse.get()) {
                        Log.d(TAG, "Skipping capture - currently processing (waiting response/template matching/clicking buttons)")
                    } else if (isWithinBettingCooldown) {
                        val remainingSeconds = (20_000L - timeSinceBettingClick) / 1000
                        Log.d(TAG, "Skipping capture - within 20s cooldown after betting_button click (${remainingSeconds}s remaining)")
                    }
                }
                delay(7_000L) // Capture every 7 seconds
            }
            Log.i(TAG, "Capture loop has ended.")
        }
    }

    private fun findTemplateLocation(bitmap: Bitmap, templateName: String): Point? {
        Log.d(TEMPLATE_SCAN_TAG, "Scanning for template: '$templateName'")
        
        val screenMat = Mat()
        Utils.bitmapToMat(bitmap, screenMat)
        // Convert to grayscale for template matching
        val screenGray = Mat()
        Imgproc.cvtColor(screenMat, screenGray, Imgproc.COLOR_RGBA2GRAY)
            screenMat.release()

        val resId = resources.getIdentifier(templateName, "drawable", packageName)
        if (resId == 0) {
            Log.e(TEMPLATE_SCAN_TAG, "Template resource not found: $templateName")
            screenGray.release()
            return null
        }

        // For "history" template, limit search area to top-left quarter (50% left and 50% top)
        val screenForMatching: Mat
        val searchAreaOffsetX: Int
        val searchAreaOffsetY: Int
        if (templateName == "history") {
            val fullWidth = screenGray.width()
            val fullHeight = screenGray.height()
            val leftHalfWidth = fullWidth / 2
            val topHalfHeight = fullHeight / 2
            searchAreaOffsetX = 0 // Offset X is 0 since we start from left edge
            searchAreaOffsetY = 0 // Offset Y is 0 since we start from top edge
            Log.d(TEMPLATE_SCAN_TAG, "Limiting search area for 'history': top-left quarter (0-$leftHalfWidth x 0-$topHalfHeight) of full screen ($fullWidth x $fullHeight)")
            
            // Crop to top-left quarter: Rect(x, y, width, height)
            val roi = org.opencv.core.Rect(0, 0, leftHalfWidth, topHalfHeight)
            screenForMatching = Mat(screenGray, roi)
            } else {
            screenForMatching = screenGray
            searchAreaOffsetX = 0
            searchAreaOffsetY = 0
        }

        var templateImage: Mat? = null
        try {
            // Load template in grayscale
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
            val matchMethods = when (templateName) {
                "ten" -> listOf(
                    Imgproc.TM_CCOEFF_NORMED,
                    Imgproc.TM_CCORR_NORMED,
                    Imgproc.TM_SQDIFF_NORMED
                )
                else -> listOf(Imgproc.TM_CCOEFF_NORMED)
            }

            // Determine scale range based on template type
            // For "history", "one", "betting_button": scan from 95% to 80% with 5% step (95%, 90%, 85%, 80%)
            // For others: scan from 100% to 70% (7 levels)
            val scaleRange = when (templateName) {
                "history", "one", "betting_button" -> (19 downTo 16).map { it / 20.0 } // 95%, 90%, 85%, 80% - 4 levels
                else -> (20 downTo 14).map { it / 20.0 } // 100%, 95%, 90%, 85%, 80%, 75%, 70% - 7 levels
            }
            Log.d(TEMPLATE_SCAN_TAG, "Scale levels to scan for '$templateName': ${scaleRange.map { "${(it * 100).toInt()}%" }.joinToString(", ")}")
            Log.d(TEMPLATE_SCAN_TAG, "Search area size: ${screenForMatching.width()}x${screenForMatching.height()}")
            
            var scaleIndex = 0
            for (scale in scaleRange) {
                scaleIndex++
                val templateW = (templateImage.width() * scale).roundToInt()
                val templateH = (templateImage.height() * scale).roundToInt()
                val scalePercent = (scale * 100).toInt()

                if (templateW <= 0 || templateH <= 0 || templateW > screenForMatching.width() || templateH > screenForMatching.height()) {
                    Log.d(TEMPLATE_SCAN_TAG, "  Scale $scaleIndex/${scaleRange.size} (${scalePercent}%): SKIPPED - template size ${templateW}x${templateH} invalid or too large")
                    continue
                }

                Log.d(TEMPLATE_SCAN_TAG, "  Scale $scaleIndex/${scaleRange.size} (${scalePercent}%): template size ${templateW}x${templateH}")

                val resizedTemplate = Mat()
                Imgproc.resize(templateImage, resizedTemplate, Size(templateW.toDouble(), templateH.toDouble()))

                // Try each matching method
                for (matchMethod in matchMethods) {
                    val result = Mat()
                    Imgproc.matchTemplate(screenForMatching, resizedTemplate, result, matchMethod)
                    val mmr = Core.minMaxLoc(result)

                    // For TM_SQDIFF_NORMED, lower values are better, so we need to invert
                    val similarity = if (matchMethod == Imgproc.TM_SQDIFF_NORMED) {
                        1.0 - mmr.minVal
                    } else {
                        mmr.maxVal
                    }

                    val shouldUpdate = bestMatch == null || similarity > bestSimilarity
                    
                    if (shouldUpdate) {
                        bestMatch = mmr
                        bestScale = scale
                        bestMethod = matchMethod
                        bestSimilarity = similarity
                        bestMatchLoc = if (matchMethod == Imgproc.TM_SQDIFF_NORMED) mmr.minLoc else mmr.maxLoc
                        Log.d(TEMPLATE_SCAN_TAG, "    Scale ${scalePercent}%: NEW BEST match - similarity=${String.format("%.3f", similarity)} at (${bestMatchLoc.x.toInt()}, ${bestMatchLoc.y.toInt()})")
                    } else {
                        Log.d(TEMPLATE_SCAN_TAG, "    Scale ${scalePercent}%: similarity=${String.format("%.3f", similarity)} (current best=${String.format("%.3f", bestSimilarity)})")
                    }
                    result.release()
                }
                resizedTemplate.release()
            }
            // Lower threshold for "ten" template vs others
            val threshold = when (templateName) {
                "ten" -> 0.50
                else -> 0.75
            }
            if (bestSimilarity >= threshold && bestMatchLoc != null) {
                val templateW = (templateImage.width() * bestScale).roundToInt()
                val templateH = (templateImage.height() * bestScale).roundToInt()
                
                // bestMatchLoc is the top-left corner of the matched template (relative to search area)
                // Adjust coordinates if we searched in a cropped area (e.g., top-left quarter for history)
                val matchX = bestMatchLoc.x.toInt() + searchAreaOffsetX
                val matchY = bestMatchLoc.y.toInt() + searchAreaOffsetY
                val clickX = matchX + templateW / 2
                val clickY = matchY + templateH / 2
                
                // Log detailed information for debugging
                Log.i(TEMPLATE_SCAN_TAG, "=== TEMPLATE MATCH DETAILS for '$templateName' ===")
                Log.i(TEMPLATE_SCAN_TAG, "Original template size: ${templateImage.width()}x${templateImage.height()}")
                Log.i(TEMPLATE_SCAN_TAG, "Matched template size (scaled): ${templateW}x${templateH}")
                Log.i(TEMPLATE_SCAN_TAG, "Scale used: ${String.format("%.2f", bestScale)} (${(bestScale * 100).toInt()}%)")
                Log.i(TEMPLATE_SCAN_TAG, "Match location (top-left): ($matchX, $matchY)")
                Log.i(TEMPLATE_SCAN_TAG, "Template bounds: [($matchX, $matchY) to (${matchX + templateW}, ${matchY + templateH})]")
                Log.i(TEMPLATE_SCAN_TAG, "Calculated click position (center): ($clickX, $clickY)")
                Log.i(TEMPLATE_SCAN_TAG, "Similarity: ${String.format("%.2f", bestSimilarity)} (threshold: ${String.format("%.2f", threshold)})")
                Log.i(TEMPLATE_SCAN_TAG, "Matching method: $bestMethod")
                Log.i(TEMPLATE_SCAN_TAG, "Search area size: ${screenForMatching.width()}x${screenForMatching.height()}")
                if (templateName == "history") {
                    Log.i(TEMPLATE_SCAN_TAG, "Search area offset: X=$searchAreaOffsetX, Y=$searchAreaOffsetY (top-left quarter of screen)")
                }
                Log.i(TEMPLATE_SCAN_TAG, "=== END TEMPLATE MATCH DETAILS ===")
                
                return Point(clickX, clickY)
            } else {
                Log.i(TEMPLATE_SCAN_TAG, "     NOT FOUND: '$templateName'. Best similarity was ${String.format("%.2f", bestSimilarity)} (threshold: ${String.format("%.2f", threshold)})")
                return null
            }
        } catch (e: Exception) {
            Log.e(TEMPLATE_SCAN_TAG, "Error processing resource '$templateName'.", e)
            return null
        } finally {
            templateImage?.release()
            // Only release screenForMatching if it's not a submatrix (cropped area)
            if (templateName != "history") {
                screenForMatching.release()
            } else {
                // For history, screenForMatching is a submatrix, release the parent
                screenGray.release()
            }
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
            val bitmap = fullBitmap ?: run {
                Log.e(TAG, "Bitmap is null after conversion.")
                return
            }
            Log.i(TAG, "Screenshot captured successfully (${bitmap.width}x${bitmap.height}).")

            val prefs = getSharedPreferences("bet_config", Context.MODE_PRIVATE)
            val secondsRegionString = prefs.getString("secondsRegion", null)
            val betAmountRegionString = prefs.getString("betAmountRegion", null)
            val secondsRect = parseRectFromString(secondsRegionString)
            val betAmountRect = parseRectFromString(betAmountRegionString)

            // Crop regions before resizing (to maintain accuracy)
            secondsBitmap = secondsRect?.let { rect -> bitmap.crop(rect) }
            betAmountBitmap = betAmountRect?.let { rect -> bitmap.crop(rect) }

            // Resize full screenshot based on screen width:
            // > 2000 → 80%, <= 2000 → 100% (no scaling)
            originalFullBitmap = bitmap
            val screenWidth = bitmap.width
            val scaleFactor = when {
                screenWidth > 2000 -> 0.8f
                else -> 1.0f
            }
            val resizedFullBitmap = bitmap.resize(scaleFactor)
            fullBitmap = resizedFullBitmap
            val scalePercent = (scaleFactor * 100).toInt()
            Log.i(TAG, "Screen width: $screenWidth. Resized full screenshot to $scalePercent%: ${resizedFullBitmap.width}x${resizedFullBitmap.height}")

            val multipartBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            multipartBody.addFormDataPart("file", "screenshot.jpg", resizedFullBitmap.toByteArray().toRequestBody("image/jpeg".toMediaTypeOrNull()))
            secondsBitmap?.let { multipartBody.addFormDataPart("seconds_image", "seconds.jpg", it.toByteArray().toRequestBody("image/jpeg".toMediaTypeOrNull())) }
            betAmountBitmap?.let { multipartBody.addFormDataPart("bet_amount_image", "bet_amount.jpg", it.toByteArray().toRequestBody("image/jpeg".toMediaTypeOrNull())) }
            
            // Get device real physical screen dimensions (not affected by orientation)
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
            val realSize = android.graphics.Point()
            display.getRealSize(realSize)
            val deviceRealWidth = realSize.x
            val deviceRealHeight = realSize.y
            
            val deviceName = prefs.getString("deviceName", "")?.trim().orEmpty()
            val betOptionRaw = prefs.getString("betOption", "cược Tài")?.trim().orEmpty()
            val bettingMethod = when {
                betOptionRaw.contains("Tài", ignoreCase = true) -> "Tài"
                betOptionRaw.contains("Xỉu", ignoreCase = true) -> "Xỉu"
                else -> betOptionRaw
            }
            multipartBody.addFormDataPart("device_name", deviceName)
            multipartBody.addFormDataPart("betting_method", bettingMethod)
            multipartBody.addFormDataPart("device_real_width", deviceRealWidth.toString())
            multipartBody.addFormDataPart("device_real_height", deviceRealHeight.toString())
            secondsRegionString?.let { multipartBody.addFormDataPart("seconds_region_coords", it) }
            betAmountRegionString?.let { multipartBody.addFormDataPart("bet_amount_region_coords", it) }

            Log.i(TAG, "Preparing to upload screenshot for device: '$deviceName' with method: '$bettingMethod' (device_real_width=$deviceRealWidth, device_real_height=$deviceRealHeight)")
            val request = Request.Builder()
                .url("https://lukistar.space/api/mobile/analyze")
                .post(multipartBody.build())
                .build()

            try {
                // Set flag to skip captures during upload, waiting for response, template matching, and clicking
                isProcessingResponse.set(true)
                Log.d(TAG, "Set isProcessingResponse=true: Starting upload and will skip captures during processing")
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (response.isSuccessful && responseBody != null) {
                        Log.i(TAG, "UPLOAD SUCCESS (code=${response.code}).")
                        handleServerResponse(responseBody, originalFullBitmap ?: resizedFullBitmap)
                    } else {
                        // Handle specific error codes
                        when (response.code) {
                            429 -> {
                                Log.e(TAG, "!!! UPLOAD FAILED: Server quota exceeded (code=429)")
                                Log.e(TAG, "Server Response: $responseBody")
                                Log.w(TAG, "The server has exceeded its quota. Please check server billing/plan.")
                                // Optionally: could add logic to pause captures for a period
                            }
                            500, 502, 503, 504 -> {
                                Log.e(TAG, "!!! UPLOAD FAILED: Server error (code=${response.code})")
                                Log.e(TAG, "Server Response: $responseBody")
                                Log.w(TAG, "Server is experiencing issues. Will retry on next capture cycle.")
                            }
                            else -> {
                        Log.e(TAG, "!!! UPLOAD FAILED (code=${response.code}). Server Response: $responseBody")
                            }
                        }
                        isProcessingResponse.set(false) // Reset flag on failure
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "!!! UPLOAD FAILED due to IOException.", e)
                Log.w(TAG, "Network error occurred. Will retry on next capture cycle.")
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
                    // Save button coordinates from JSON response to SharedPreferences
                    saveButtonCoordinates(json)
                    
                    if (seconds == null) {
                        Log.w(TAG, "Server response for BETTING did not include 'seconds'. Skipping history click.")
                        isProcessingResponse.set(false) // Reset flag when no action needed
                    } else if (seconds in 33..59) {
                        Log.i(TAG, "CLICK CONDITION MET: image_type is BETTING and seconds ($seconds) is in range [33, 59].")
                        val historyLocation = findTemplateLocation(screenBitmap, "history")
                        if (historyLocation != null) {
                            AutoClickService.requestClick(historyLocation.x, historyLocation.y)
                            delay(500) // Small delay for click to register
                        } else {
                            Log.w(TAG, "Click condition met, but 'history' template was not found on screen.")
                        }
                        isProcessingResponse.set(false) // Reset flag after click
                    } else {
                        Log.d(TAG, "Seconds=$seconds outside [33, 59], skipping history click.")
                        isProcessingResponse.set(false) // Reset flag when no action needed
                    }
                }
                "HISTORY" -> {
                    Log.i(TAG, "HISTORY screen detected. Clicking fixed top-left position to close.")
                    AutoClickService.requestClick(5, 5)
                    delay(500) // Small delay for click to register

                    val winLossRaw = json.optString("win_loss", "")
                    val winLoss = winLossRaw.lowercase()
                    val tienThang = json.optDouble("tien_thang", 0.0)
                    
                    // Track consecutive "unknown" win_loss values
                    if (winLoss == "unknown") {
                        consecutiveUnknownCount++
                        Log.w(TAG, "Received HISTORY JSON with win_loss='unknown', tien_thang=$tienThang. Consecutive count: $consecutiveUnknownCount/5")
                        
                        // If 5 consecutive "unknown" values, stop capture job
                        if (consecutiveUnknownCount >= 5) {
                            Log.e(TAG, "!!! STOPPING CAPTURE: Received 5 consecutive HISTORY JSONs with win_loss='unknown'")
                            Log.e(TAG, "User must press 'Bắt đầu' button on main screen to restart capture.")
                            captureJob?.cancel()
                            captureJob = null
                            isProcessingResponse.set(false)
                            return
                        }
                        
                        // Handle win_loss = "unknown" based on tien_thang and column_5
                        val column5 = json.optString("column_5", "").trim()
                        val winningsColor = json.opt("winnings_color")
                        val isWinningsColorNull = winningsColor == null || winningsColor == org.json.JSONObject.NULL
                        Log.d(TAG, "Checking conditions: tien_thang=$tienThang, column_5='$column5', winnings_color=$winningsColor")
                        
                        // Skip if win_loss="unknown" AND winnings_color=null AND column_5="unknown"
                        if (isWinningsColorNull && column5.equals("unknown", ignoreCase = true)) {
                            Log.i(TAG, "win_loss='unknown', winnings_color=null, and column_5='unknown'. Skipping all processing (no clicks).")
                            isProcessingResponse.set(false)
                            return
                        }
                        
                        if (tienThang != 0.0 && column5.contains("noi dung", ignoreCase = true)) {
                            // win_loss = "unknown" and tien_thang != 0 and column_5 contains "noi dung": Skip all processing, wait for next JSON
                            Log.i(TAG, "win_loss='unknown', tien_thang != 0 ($tienThang), and column_5 contains 'noi dung'. Skipping all processing and waiting for next HISTORY JSON.")
                            isProcessingResponse.set(false)
                            return
                        }
                        
                        // If tien_thang == 0 or column_5 does not contain "noi dung", continue to use saved JSON (handled below)
                        // win_loss = "unknown" and tien_thang = 0: Use saved JSON
                        Log.d(TAG, "Condition not met for skip. tien_thang=$tienThang, column_5='$column5'. Will use saved JSON if available.")
                        val savedJson = loadLatestHistoryJson()
                        if (savedJson != null) {
                            Log.i(TAG, "win_loss is 'unknown' and tien_thang=$tienThang, using saved JSON with win_loss='${savedJson.optString("win_loss", "")}'")
                            // Process with saved JSON
                            val processedWinLoss = savedJson.optString("win_loss", "").lowercase()
                            val shouldPerformFollowUp = processedWinLoss == "win" || processedWinLoss == "loss"
                            
                            if (shouldPerformFollowUp) {
                                // Handle skip logic: if 4 consecutive losses, skip next 3 rounds
                                if (processedWinLoss == "win") {
                                    // Handle win
                                    if (skipCount > 0) {
                                        // Currently skipping rounds - results in skipped rounds are IGNORED
                                        skipCount--
                                        Log.i(TAG, "WIN detected (from saved JSON) but SKIPPING (skip count remaining: $skipCount). Result is IGNORED - not counted, skip continues.")
                                        if (skipCount == 0) {
                                            // Skip period completed - reset counter and start counting from scratch
                                            Log.i(TAG, "Skip period completed. Resetting consecutive loss count from $consecutiveLossCount to 0. Will start counting from scratch.")
                                            consecutiveLossCount = 0
                                        }
                                        isProcessingResponse.set(false)
                                    } else {
                                        // Not skipping, reset counters on win
                                        if (consecutiveLossCount > 0) {
                                            Log.i(TAG, "WIN detected (from saved JSON). Resetting consecutive loss count from $consecutiveLossCount to 0.")
                                            consecutiveLossCount = 0
                                        }
                                        // Perform normal clicks for win
                                        val betAmount = savedJson.optDouble("bet_amount", 0.0)
                                        val clickCount = 1
                                        Log.i(TAG, "Processing saved JSON: win_loss='$processedWinLoss', bet_amount=$betAmount, clickCount=$clickCount")
                                        scheduleHistoryFollowUpClicks(clickCount)
                                    }
                                } else if (processedWinLoss == "loss") {
                                    // Handle loss
                                    if (skipCount > 0) {
                                        // Currently skipping rounds - results in skipped rounds are IGNORED
                                        skipCount--
                                        Log.i(TAG, "LOSS detected (from saved JSON) but SKIPPING (skip count remaining: $skipCount). Result is IGNORED - not counted in consecutive loss count.")
                                        if (skipCount == 0) {
                                            // Skip period completed - reset counter and start counting from scratch
                                            Log.i(TAG, "Skip period completed. Resetting consecutive loss count from $consecutiveLossCount to 0. Will start counting from scratch.")
                                            consecutiveLossCount = 0
                                        }
                                        isProcessingResponse.set(false)
                                    } else {
                                        // Not skipping, check if we need to start skipping
                                        consecutiveLossCount++
                                        Log.i(TAG, "LOSS detected (from saved JSON). Consecutive loss count: $consecutiveLossCount/4")
                                        
                                        if (consecutiveLossCount >= 4) {
                                            // After 4 consecutive losses, skip next 3 rounds
                                            // But this round (the 4th loss) still needs to click normally
                                            skipCount = 3
                                            Log.w(TAG, "!!! 4 CONSECUTIVE LOSSES DETECTED (from saved JSON). Will skip next 3 rounds. Results in those 3 rounds will be IGNORED. Skip count set to $skipCount.")
                                            // Still perform normal clicks for this 4th loss
                                            val betAmount = savedJson.optDouble("bet_amount", 0.0)
                                            val clickCount = ((betAmount * 2) / 1000.0).toInt().coerceAtLeast(0)
                                            Log.i(TAG, "Processing saved JSON: win_loss='$processedWinLoss', bet_amount=$betAmount, clickCount=$clickCount")
                                            scheduleHistoryFollowUpClicks(clickCount)
                                        } else {
                                            // Perform normal clicks for loss
                                            val betAmount = savedJson.optDouble("bet_amount", 0.0)
                                            val clickCount = ((betAmount * 2) / 1000.0).toInt().coerceAtLeast(0)
                                            Log.i(TAG, "Processing saved JSON: win_loss='$processedWinLoss', bet_amount=$betAmount, clickCount=$clickCount")
                                            scheduleHistoryFollowUpClicks(clickCount)
                                        }
                                    }
                                } else {
                                    Log.i(TAG, "Saved JSON win_loss '$processedWinLoss' not actionable; skipping follow-up clicks.")
                                    isProcessingResponse.set(false)
                                }
                            }
                        } else {
                            Log.w(TAG, "win_loss is 'unknown', tien_thang=0, but no saved JSON found. Skipping follow-up clicks.")
                            isProcessingResponse.set(false)
                        }
                        return // Exit early for win_loss = "unknown"
                    } else {
                        // Reset counter when win_loss is not "unknown"
                        if (consecutiveUnknownCount > 0) {
                            Log.i(TAG, "Received HISTORY JSON with win_loss='$winLoss'. Resetting consecutive unknown count from $consecutiveUnknownCount to 0.")
                            consecutiveUnknownCount = 0
                        }
                        
                        // If win_loss is not unknown, save this JSON and use it
                        saveHistoryJson(json)
                        val processedWinLoss = winLoss
                        val shouldPerformFollowUp = processedWinLoss == "win" || processedWinLoss == "loss"
                        
                        if (shouldPerformFollowUp) {
                            // Handle skip logic: if 4 consecutive losses, skip next 3 rounds
                            if (processedWinLoss == "win") {
                                // Handle win
                                if (skipCount > 0) {
                                    // Currently skipping rounds - results in skipped rounds are IGNORED
                                    skipCount--
                                    Log.i(TAG, "WIN detected but SKIPPING (skip count remaining: $skipCount). Result is IGNORED - not counted, skip continues.")
                                    if (skipCount == 0) {
                                        // Skip period completed - reset counter and start counting from scratch
                                        Log.i(TAG, "Skip period completed. Resetting consecutive loss count from $consecutiveLossCount to 0. Will start counting from scratch.")
                                        consecutiveLossCount = 0
                                    }
                                    isProcessingResponse.set(false)
                                } else {
                                    // Not skipping, reset counters on win
                                    if (consecutiveLossCount > 0) {
                                        Log.i(TAG, "WIN detected. Resetting consecutive loss count from $consecutiveLossCount to 0.")
                                        consecutiveLossCount = 0
                                    }
                                    // Perform normal clicks for win
                                    val betAmount = json.optDouble("bet_amount", 0.0)
                                    val clickCount = 1
                                    Log.i(TAG, "Processing JSON: win_loss='$processedWinLoss', bet_amount=$betAmount, clickCount=$clickCount")
                                    scheduleHistoryFollowUpClicks(clickCount)
                                }
                            } else if (processedWinLoss == "loss") {
                                // Handle loss
                                if (skipCount > 0) {
                                    // Currently skipping rounds - results in skipped rounds are IGNORED
                                    skipCount--
                                    Log.i(TAG, "LOSS detected but SKIPPING (skip count remaining: $skipCount). Result is IGNORED - not counted in consecutive loss count.")
                                    if (skipCount == 0) {
                                        // Skip period completed - reset counter and start counting from scratch
                                        Log.i(TAG, "Skip period completed. Resetting consecutive loss count from $consecutiveLossCount to 0. Will start counting from scratch.")
                                        consecutiveLossCount = 0
                                    }
                                    isProcessingResponse.set(false)
                                } else {
                                    // Not skipping, check if we need to start skipping
                                    consecutiveLossCount++
                                    Log.i(TAG, "LOSS detected. Consecutive loss count: $consecutiveLossCount/4")
                                    
                                    if (consecutiveLossCount >= 4) {
                                        // After 4 consecutive losses, skip next 3 rounds
                                        // But this round (the 4th loss) still needs to click normally
                                        skipCount = 3
                                        Log.w(TAG, "!!! 4 CONSECUTIVE LOSSES DETECTED. Will skip next 3 rounds. Results in those 3 rounds will be IGNORED. Skip count set to $skipCount.")
                                        // Still perform normal clicks for this 4th loss
                                        val betAmount = json.optDouble("bet_amount", 0.0)
                                        val clickCount = ((betAmount * 2) / 1000.0).toInt().coerceAtLeast(0)
                                        Log.i(TAG, "Processing JSON: win_loss='$processedWinLoss', bet_amount=$betAmount, clickCount=$clickCount")
                                        scheduleHistoryFollowUpClicks(clickCount)
                                    } else {
                                        // Perform normal clicks for loss
                                        val betAmount = json.optDouble("bet_amount", 0.0)
                                        val clickCount = ((betAmount * 2) / 1000.0).toInt().coerceAtLeast(0)
                                        Log.i(TAG, "Processing JSON: win_loss='$processedWinLoss', bet_amount=$betAmount, clickCount=$clickCount")
                                        scheduleHistoryFollowUpClicks(clickCount)
                                    }
                                }
                            } else {
                                Log.i(TAG, "win_loss '$processedWinLoss' not actionable; skipping follow-up clicks.")
                                isProcessingResponse.set(false)
                            }
                        } else {
                            Log.i(TAG, "win_loss '$processedWinLoss' not actionable; skipping follow-up clicks.")
                            isProcessingResponse.set(false)
                        }
                        return // Exit early after processing
                    }
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
                delay(1L) // Initial delay 1ms
                
                if (clickCount <= 0) {
                    Log.i(TAG, "Click count is $clickCount, skipping clicks.")
                    return@launch
                }
                
                val prefs = getSharedPreferences("bet_config", Context.MODE_PRIVATE)
                val buttonBetX = prefs.getInt("button_bet_x", -1)
                val buttonBetY = prefs.getInt("button_bet_y", -1)
                
                // Click "Cược" button if 20 consecutive successful "Đặt Cược" clicks
                val shouldClickBetButton = consecutivePlaceBetSuccessCount >= 20
                
                if (shouldClickBetButton && buttonBetX >= 0 && buttonBetY >= 0) {
                    // Step 1: Click "Cược" button (button_bet_coords)
                    Log.i(TAG, "Step 1: Clicking 'Cược' button at ($buttonBetX, $buttonBetY) - triggered by 20 consecutive successful 'Đặt Cược' clicks")
                    showClickOverlay(buttonBetX, buttonBetY)
                    AutoClickService.requestClick(buttonBetX, buttonBetY)
                    delay(100L) // Wait for UI to update
                    
                    // Reset counter after clicking "Cược" button
                    consecutivePlaceBetSuccessCount = 0
                    Log.i(TAG, "Reset consecutive 'Đặt Cược' success count to 0 after clicking 'Cược' button.")
                }
                
                // If clickCount >= 7, use stored button coordinates instead of template matching
                if (clickCount >= 7) {
                    // If clickCount > 249, use 50K/10K/1K buttons
                    if (clickCount > 249) {
                        val button50kX = prefs.getInt("button_50k_x", -1)
                        val button50kY = prefs.getInt("button_50k_y", -1)
                        val button10kX = prefs.getInt("button_10k_x", -1)
                        val button10kY = prefs.getInt("button_10k_y", -1)
                        val button1kX = prefs.getInt("button_1k_x", -1)
                        val button1kY = prefs.getInt("button_1k_y", -1)
                        
                        // Check if all button coordinates are available
                        if (button50kX >= 0 && button50kY >= 0 &&
                                button10kX >= 0 && button10kY >= 0 &&
                                button1kX >= 0 && button1kY >= 0) {
                                
                                // Calculate number of clicks: 1 click 50K = 50 clicks 1K, 1 click 10K = 10 clicks 1K
                                // Formula: clicks50k = clickCount / 50, remainder = clickCount % 50
                                //          clicks10k = remainder / 10, clicks1k = remainder % 10
                                // Examples: 283 = 4*50 + 8*10 + 3 = 200 + 80 + 3 = 283
                                //           435 = 8*50 + 3*10 + 5 = 400 + 30 + 5 = 435
                                val clicks50k = clickCount / 50
                                val remainder = clickCount % 50
                                val clicks10k = remainder / 10
                                val clicks1k = remainder % 10
                                
                                Log.i(TAG, "Click count is $clickCount (> 249). Using 50K/10K/1K buttons.")
                                Log.i(TAG, "Calculated clicks: 50K=$clicks50k times, 10K=$clicks10k times, 1K=$clicks1k times")
                                
                                // Show click information overlay
                                showClickInfoOverlay(clickCount, clicks50k, clicks10k, clicks1k)
                                
                                // Step 2: Click 50K button the calculated number of times
                                if (clicks50k > 0) {
                                    Log.i(TAG, "Step 2: Clicking 50K button $clicks50k time(s) at ($button50kX, $button50kY)")
                                    delay(500L) // Delay 0.5 seconds before clicking 50K button
                                    repeat(clicks50k) { index ->
                                        showClickOverlay(button50kX, button50kY)
                                        AutoClickService.requestClick(button50kX, button50kY)
                                        if (index < clicks50k - 1) {
                                            delay(300L) // Delay between clicks
                                        }
                                    }
                                    delay(500L) // Small delay after 50K clicks
                                }
                                
                                // Step 3: Click 10K button the calculated number of times
                                if (clicks10k > 0) {
                                    Log.i(TAG, "Step 3: Clicking 10K button $clicks10k time(s) at ($button10kX, $button10kY)")
                                    delay(500L) // Delay 0.5 seconds before clicking 10K button
                                    repeat(clicks10k) { index ->
                                        showClickOverlay(button10kX, button10kY)
                                        AutoClickService.requestClick(button10kX, button10kY)
                                        if (index < clicks10k - 1) {
                                            delay(300L) // Delay between clicks
                                        }
                                    }
                                    delay(500L) // Small delay after 10K clicks
                                }
                                
                                // Step 4: Click 1K button the calculated number of times
                                if (clicks1k > 0) {
                                    Log.i(TAG, "Step 4: Clicking 1K button $clicks1k time(s) at ($button1kX, $button1kY)")
                                    delay(500L) // Delay 0.5 seconds before clicking 1K button
                                    repeat(clicks1k) { index ->
                                        showClickOverlay(button1kX, button1kY)
                                        AutoClickService.requestClick(button1kX, button1kY)
                                        if (index < clicks1k - 1) {
                                            delay(300L) // Delay between clicks
                                        }
                                    }
                                }
                                
                                Log.i(TAG, "Completed click sequence using 50K/10K/1K buttons.")
                                
                                // Step 5: Click "Đặt Cược" button after completing all amount clicks
                                clickPlaceBetButton()
                            } else {
                                // Fallback to template matching if 50K coordinates not available
                                Log.w(TAG, "50K button coordinates not available. Falling back to template matching.")
                                
                                // Find and click one.jpg
                                val screenBitmap = captureSingleBitmap()
                                val oneLocation = screenBitmap?.let { findTemplateLocation(it, "one") }
                                screenBitmap?.recycle()

                                if (oneLocation != null) {
                                    Log.i(TAG, "Click plan: $clickCount total clicks on one.jpg")
                                    repeat(clickCount) { index ->
                                        showClickOverlay(oneLocation.x, oneLocation.y)
                                        AutoClickService.requestClick(oneLocation.x, oneLocation.y)
                                        if (clickCount > 1 && index < clickCount - 1) {
                                            delay(300L) // Delay between clicks
                                        }
                                    }
                                } else {
                                    Log.w(TAG, "'one' template not found but needed ($clickCount clicks).")
                                }
                                
                                // Click "Đặt Cược" button after fallback clicks
                                clickPlaceBetButton()
                            }
                        } else {
                            // For 7 <= clickCount <= 249, use 10K/1K buttons
                            val button10kX = prefs.getInt("button_10k_x", -1)
                            val button10kY = prefs.getInt("button_10k_y", -1)
                            val button1kX = prefs.getInt("button_1k_x", -1)
                            val button1kY = prefs.getInt("button_1k_y", -1)
                            
                            // Check if all button coordinates are available
                            if (button10kX >= 0 && button10kY >= 0 && 
                                button1kX >= 0 && button1kY >= 0) {
                                
                                // Calculate number of clicks for 10K and 1K buttons
                                val clicks10k = clickCount / 10
                                val clicks1k = clickCount % 10
                                
                                Log.i(TAG, "Click count is $clickCount (7-249). Using 10K/1K buttons.")
                                Log.i(TAG, "Calculated clicks: 10K=$clicks10k times, 1K=$clicks1k times")
                                
                                // Show click information overlay
                                showClickInfoOverlay(clickCount, 0, clicks10k, clicks1k)
                                
                                // Step 2: Click 10K button the calculated number of times
                                if (clicks10k > 0) {
                                    Log.i(TAG, "Step 2: Clicking 10K button $clicks10k time(s) at ($button10kX, $button10kY)")
                                    delay(500L) // Delay 0.5 seconds before clicking 10K button
                                    repeat(clicks10k) { index ->
                                        showClickOverlay(button10kX, button10kY)
                                        AutoClickService.requestClick(button10kX, button10kY)
                                        if (index < clicks10k - 1) {
                                            delay(300L) // Delay between clicks
                                        }
                                    }
                                    delay(500L) // Small delay after 10K clicks
                                }
                                
                                // Step 3: Click 1K button the calculated number of times
                                if (clicks1k > 0) {
                                    Log.i(TAG, "Step 3: Clicking 1K button $clicks1k time(s) at ($button1kX, $button1kY)")
                                    delay(500L) // Delay 0.5 seconds before clicking 1K button
                                    repeat(clicks1k) { index ->
                                        showClickOverlay(button1kX, button1kY)
                                        AutoClickService.requestClick(button1kX, button1kY)
                                        if (index < clicks1k - 1) {
                                            delay(300L) // Delay between clicks
                                        }
                                    }
                                }
                                
                                Log.i(TAG, "Completed click sequence using 10K/1K buttons.")
                                
                                // Step 4: Click "Đặt Cược" button after completing all amount clicks
                                clickPlaceBetButton()
                            } else {
                                // Fallback to template matching if coordinates not available
                                Log.w(TAG, "Stored button coordinates not available. Falling back to template matching.")
                                
                                // Find and click one.jpg
                                val screenBitmap = captureSingleBitmap()
                                val oneLocation = screenBitmap?.let { findTemplateLocation(it, "one") }
                                screenBitmap?.recycle()

                                if (oneLocation != null) {
                                    Log.i(TAG, "Click plan: $clickCount total clicks on one.jpg")
                                    repeat(clickCount) { index ->
                                        showClickOverlay(oneLocation.x, oneLocation.y)
                                        AutoClickService.requestClick(oneLocation.x, oneLocation.y)
                                        if (clickCount > 1 && index < clickCount - 1) {
                                            delay(300L) // Delay between clicks
                                        }
                                    }
                                } else {
                                    Log.w(TAG, "'one' template not found but needed ($clickCount clicks).")
                                }
                                
                                // Click "Đặt Cược" button after fallback clicks
                                clickPlaceBetButton()
                            }
                        }
                } else {
                    // For clickCount < 7, use button_1k_coords from SharedPreferences
                    val prefs = getSharedPreferences("bet_config", Context.MODE_PRIVATE)
                    val button1kX = prefs.getInt("button_1k_x", -1)
                    val button1kY = prefs.getInt("button_1k_y", -1)
                    
                    if (button1kX >= 0 && button1kY >= 0) {
                        Log.i(TAG, "Click count is $clickCount (< 7). Using 1K button coordinates from SharedPreferences.")
                        Log.i(TAG, "Clicking 1K button $clickCount time(s) at ($button1kX, $button1kY)")
                        
                        // Show click information overlay
                        showClickInfoOverlay(clickCount, 0, 0, clickCount)
                        
                        delay(500L) // Delay 0.5 seconds before clicking 1K button
                        
                        repeat(clickCount) { index ->
                            showClickOverlay(button1kX, button1kY)
                            AutoClickService.requestClick(button1kX, button1kY)
                            if (clickCount > 1 && index < clickCount - 1) {
                                delay(1000L) // Increased delay for cloud phone compatibility
                            }
                        }
                        
                        // Click "Đặt Cược" button after completing all clicks
                        clickPlaceBetButton()
                    } else {
                        Log.w(TAG, "1K button coordinates not available in SharedPreferences for clickCount=$clickCount. Skipping clicks.")
                    }
                }
            } finally {
                // Reset flag after all operations complete (template matching, clicking buttons)
                isProcessingResponse.set(false)
                Log.d(TAG, "Set isProcessingResponse=false: All processing complete (template matching and clicking finished)")
            }
        }
    }
    
    /**
     * Save HISTORY JSON response to file if win_loss is not "unknown"
     */
    private fun saveHistoryJson(json: JSONObject) {
        try {
            val file = File(filesDir, HISTORY_JSON_FILE_NAME)
            FileWriter(file).use { writer ->
                writer.write(json.toString())
            }
            Log.i(TAG, "Saved HISTORY JSON to file: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save HISTORY JSON to file", e)
        }
    }
    
    /**
     * Load the latest saved HISTORY JSON from file
     */
    private fun loadLatestHistoryJson(): JSONObject? {
        return try {
            val file = File(filesDir, HISTORY_JSON_FILE_NAME)
            if (file.exists() && file.length() > 0) {
                FileReader(file).use { reader ->
                    val content = reader.readText()
                    val json = JSONObject(content)
                    val winLoss = json.optString("win_loss", "").lowercase()
                    if (winLoss != "unknown") {
                        Log.i(TAG, "Loaded saved HISTORY JSON with win_loss='$winLoss'")
                        json
                    } else {
                        Log.w(TAG, "Saved HISTORY JSON has win_loss='unknown', ignoring it.")
                        null
                    }
                }
            } else {
                Log.w(TAG, "No saved HISTORY JSON file found.")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load HISTORY JSON from file", e)
            null
        }
    }
    
    /**
     * Click "Đặt Cược" button using coordinates from JSON Betting (button_place_bet_coords)
     */
    private suspend fun clickPlaceBetButton() {
        delay(50L) // Small delay after amount clicks
        
        val prefs = getSharedPreferences("bet_config", Context.MODE_PRIVATE)
        val buttonPlaceBetX = prefs.getInt("button_place_bet_x", -1)
        val buttonPlaceBetY = prefs.getInt("button_place_bet_y", -1)
        
        if (buttonPlaceBetX >= 0 && buttonPlaceBetY >= 0) {
            Log.i(TAG, "Using 'Đặt Cược' button coordinates from SharedPreferences: ($buttonPlaceBetX, $buttonPlaceBetY)")
            showClickOverlay(buttonPlaceBetX, buttonPlaceBetY)
            AutoClickService.requestClick(buttonPlaceBetX, buttonPlaceBetY)
            delay(50L) // Small delay for click to register
            
            // Record successful betting_button click time for 20-second cooldown
            lastBettingButtonClickTime = System.currentTimeMillis()
            
            // Increment consecutive successful "Đặt Cược" clicks
            consecutivePlaceBetSuccessCount++
            Log.i(TAG, "betting_button clicked successfully. Consecutive success count: $consecutivePlaceBetSuccessCount/20. Starting 20-second cooldown period.")
        } else {
            // Reset counter if coordinates not found (click failed)
            consecutivePlaceBetSuccessCount = 0
            Log.w(TAG, "'Đặt Cược' button coordinates not found in SharedPreferences. Cannot click 'Đặt Cược'. Resetting consecutive success count to 0.")
            Log.w(TAG, "Please ensure JSON Betting with button_place_bet_coords has been received and saved.")
        }
    }
    
    /**
     * Save button coordinates from BETTING JSON response to SharedPreferences
     */
    private fun saveButtonCoordinates(json: JSONObject) {
        try {
            val prefs = getSharedPreferences("bet_config", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            
            // Extract button_1k_coords
            val button1kCoords = json.optJSONObject("button_1k_coords")
            if (button1kCoords != null) {
                val x1k = button1kCoords.optInt("x", -1)
                val y1k = button1kCoords.optInt("y", -1)
                if (x1k >= 0 && y1k >= 0) {
                    editor.putInt("button_1k_x", x1k)
                    editor.putInt("button_1k_y", y1k)
                    Log.i(TAG, "Saved button_1k_coords: ($x1k, $y1k)")
                }
            }
            
            // Extract button_10k_coords
            val button10kCoords = json.optJSONObject("button_10k_coords")
            if (button10kCoords != null) {
                val x10k = button10kCoords.optInt("x", -1)
                val y10k = button10kCoords.optInt("y", -1)
                if (x10k >= 0 && y10k >= 0) {
                    editor.putInt("button_10k_x", x10k)
                    editor.putInt("button_10k_y", y10k)
                    Log.i(TAG, "Saved button_10k_coords: ($x10k, $y10k)")
                }
            }
            
            // Extract button_bet_coords (Cược button)
            val buttonBetCoords = json.optJSONObject("button_bet_coords")
            if (buttonBetCoords != null) {
                val xBet = buttonBetCoords.optInt("x", -1)
                val yBet = buttonBetCoords.optInt("y", -1)
                if (xBet >= 0 && yBet >= 0) {
                    editor.putInt("button_bet_x", xBet)
                    editor.putInt("button_bet_y", yBet)
                    Log.i(TAG, "Saved button_bet_coords: ($xBet, $yBet)")
                }
            }
            
            // Extract button_50k_coords
            val button50kCoords = json.optJSONObject("button_50k_coords")
            if (button50kCoords != null) {
                val x50k = button50kCoords.optInt("x", -1)
                val y50k = button50kCoords.optInt("y", -1)
                if (x50k >= 0 && y50k >= 0) {
                    editor.putInt("button_50k_x", x50k)
                    editor.putInt("button_50k_y", y50k)
                    Log.i(TAG, "Saved button_50k_coords: ($x50k, $y50k)")
                }
            }
            
            // Extract button_place_bet_coords (Đặt Cược button) - saved for potential future use
            val buttonPlaceBetCoords = json.optJSONObject("button_place_bet_coords")
            if (buttonPlaceBetCoords != null) {
                val xPlaceBet = buttonPlaceBetCoords.optInt("x", -1)
                val yPlaceBet = buttonPlaceBetCoords.optInt("y", -1)
                if (xPlaceBet >= 0 && yPlaceBet >= 0) {
                    editor.putInt("button_place_bet_x", xPlaceBet)
                    editor.putInt("button_place_bet_y", yPlaceBet)
                    Log.i(TAG, "Saved button_place_bet_coords: ($xPlaceBet, $yPlaceBet)")
                }
            }
            
            editor.apply()
            Log.i(TAG, "Button coordinates saved to SharedPreferences")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save button coordinates from JSON", e)
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
        // Remove all click overlays
        removeAllClickOverlays()
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
    
    /**
     * Show a green overlay circle at the click position for visual debugging
     * Must be called from main thread (UI thread)
     */
    private fun showClickOverlay(x: Int, y: Int) {
        // Check if SYSTEM_ALERT_WINDOW permission is granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted. Cannot show overlay at ($x, $y)")
                return
            }
        }
        
        // Ensure we're on the main thread for UI operations
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Already on main thread, execute directly
            showClickOverlayInternal(x, y)
        } else {
            // Switch to main thread using Handler
            Handler(Looper.getMainLooper()).post {
                showClickOverlayInternal(x, y)
            }
        }
    }
    
    private fun showClickOverlayInternal(x: Int, y: Int) {
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            // Create a custom view with a bright green circle and crosshair
            val size = 80 // Increased size to 80x80 pixels for better visibility
            val overlayView = object : View(this) {
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val paint = Paint().apply {
                        color = Color.GREEN
                        style = Paint.Style.FILL
                        isAntiAlias = true
                    }
                    val strokePaint = Paint().apply {
                        color = Color.RED
                        style = Paint.Style.STROKE
                        strokeWidth = 4f
                        isAntiAlias = true
                    }
                    
                    val centerX = width / 2f
                    val centerY = height / 2f
                    val radius = (size / 2 - 5).toFloat()
                    
                    // Draw bright green filled circle
                    canvas.drawCircle(centerX, centerY, radius, paint)
                    
                    // Draw red border circle for better visibility
                    canvas.drawCircle(centerX, centerY, radius, strokePaint)
                    
                    // Draw crosshair lines
                    strokePaint.color = Color.RED
                    strokePaint.strokeWidth = 3f
                    canvas.drawLine(centerX, 0f, centerX, height.toFloat(), strokePaint)
                    canvas.drawLine(0f, centerY, width.toFloat(), centerY, strokePaint)
                }
            }.apply {
                setBackgroundColor(Color.TRANSPARENT)
            }
            
            val layoutParams = WindowManager.LayoutParams(
                size,
                size,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // Center the overlay on the click position
                this.x = x - size / 2
                this.y = y - size / 2
            }
            
            windowManager.addView(overlayView, layoutParams)
            clickOverlays.add(overlayView)
            
            Log.i(TAG, "Showing green overlay at ($x, $y) with size $size x $size")
            
            // Remove overlay after 5 seconds (increased from 3 seconds)
            Handler(Looper.getMainLooper()).postDelayed({
                removeClickOverlay(overlayView, windowManager)
            }, 5_000L)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show click overlay at ($x, $y)", e)
        }
    }
    
    /**
     * Show click information overlay (total clicks and breakdown by button)
     * Must be called from main thread (UI thread)
     */
    private fun showClickInfoOverlay(totalClicks: Int, clicks50k: Int, clicks10k: Int, clicks1k: Int) {
        // Check if SYSTEM_ALERT_WINDOW permission is granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted. Cannot show click info overlay")
                return
            }
        }
        
        // Ensure we're on the main thread for UI operations
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showClickInfoOverlayInternal(totalClicks, clicks50k, clicks10k, clicks1k)
        } else {
            Handler(Looper.getMainLooper()).post {
                showClickInfoOverlayInternal(totalClicks, clicks50k, clicks10k, clicks1k)
            }
        }
    }
    
    private fun showClickInfoOverlayInternal(totalClicks: Int, clicks50k: Int, clicks10k: Int, clicks1k: Int) {
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            // Create text view with click information
            val textView = android.widget.TextView(this).apply {
                val infoText = buildString {
                    appendLine("Tổng số click: $totalClicks")
                    if (clicks50k > 0) appendLine("50K: $clicks50k lần")
                    if (clicks10k > 0) appendLine("10K: $clicks10k lần")
                    if (clicks1k > 0) appendLine("1K: $clicks1k lần")
                }
                text = infoText.trim()
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setBackgroundColor(Color.parseColor("#CC2196F3")) // Blue background with transparency
                setPadding(20, 15, 20, 15)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            
            // Measure text to get proper size
            textView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val width = textView.measuredWidth + 40 // Add padding
            val height = textView.measuredHeight + 30 // Add padding
            
            val layoutParams = WindowManager.LayoutParams(
                width,
                height,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = 100 // Position at top center, 100px from top
            }
            
            windowManager.addView(textView, layoutParams)
            clickOverlays.add(textView)
            
            Log.i(TAG, "Showing click info overlay: Total=$totalClicks, 50K=$clicks50k, 10K=$clicks10k, 1K=$clicks1k")
            
            // Remove overlay after 10 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                removeClickOverlay(textView, windowManager)
            }, 10_000L)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show click info overlay", e)
        }
    }
    
    /**
     * Remove a specific click overlay
     * Must be called from main thread
     */
    private fun removeClickOverlay(view: View, windowManager: WindowManager) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                // Already on main thread
                windowManager.removeView(view)
                clickOverlays.remove(view)
            } else {
                // Switch to main thread
                Handler(Looper.getMainLooper()).post {
                    try {
                        windowManager.removeView(view)
                        clickOverlays.remove(view)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to remove click overlay", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove click overlay", e)
        }
    }
    
    /**
     * Remove all click overlays
     * Must be called from main thread
     */
    private fun removeAllClickOverlays() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Already on main thread
            removeAllClickOverlaysInternal()
        } else {
            // Switch to main thread
            Handler(Looper.getMainLooper()).post {
                removeAllClickOverlaysInternal()
            }
        }
    }
    
    private fun removeAllClickOverlaysInternal() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        clickOverlays.forEach { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                // View might already be removed
            }
        }
        clickOverlays.clear()
    }
}

