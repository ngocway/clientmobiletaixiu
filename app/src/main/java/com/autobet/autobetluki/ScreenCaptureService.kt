
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
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var latestBettingJson: JSONObject? = null
    
    private val isProcessingResponse = AtomicBoolean(false)
    private var lastBettingClickTime: Long = 0
    private var consecutiveUnknownCount: Int = 0

    companion object {
        const val ACTION_START = "com.autobet.autobetluki.START"
        const val ACTION_STOP = "com.autobet.autobetluki.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        private const val NOTIFICATION_CHANNEL_ID = "ScreenCapture"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "ScreenCaptureSvc"
        private const val TEMPLATE_SCAN_TAG = "TemplateScan"
        private const val JPEG_QUALITY = 75
        private const val COOLDOWN_DURATION_MS = 25_000L // 25 seconds cooldown after clicking Đặt Cược
        private const val BASE_CAPTURE_DELAY_MS = 5_000L // 5 seconds base delay between captures
        private const val HISTORY_CLICK_DELAY_MS = 3_000L // 3 seconds delay after clicking history button
        private const val MAX_CONSECUTIVE_UNKNOWN = 5
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
                    // Reset counters when starting
                    consecutiveUnknownCount = 0
                    lastBettingClickTime = 0
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
                    Log.d(TAG, "-------------------- New Capture Cycle --------------------")
                
                // Check if we're in cooldown period
                val currentTime = System.currentTimeMillis()
                val timeSinceLastBettingClick = currentTime - lastBettingClickTime
                if (timeSinceLastBettingClick < COOLDOWN_DURATION_MS) {
                    val remainingCooldown = (COOLDOWN_DURATION_MS - timeSinceLastBettingClick) / 1000
                    Log.d(TAG, "In cooldown period. Skipping capture. Remaining: ${remainingCooldown}s")
                } else if (!isProcessingResponse.get()) {
                    captureMutex.withLock {
                        captureAndUpload()
                    }
                } else {
                    Log.d(TAG, "Processing response, skipping capture.")
                }
                
                delay(BASE_CAPTURE_DELAY_MS)
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

            for (scale in (20 downTo 17).map { it / 20.0 }) { // 100%, 95%, 90%, 85%
                val templateW = (templateImage.width() * scale).roundToInt()
                val templateH = (templateImage.height() * scale).roundToInt()

                if (templateW <= 0 || templateH <= 0 || templateW > screenGray.width() || templateH > screenGray.height()) continue

                val resizedTemplate = Mat()
                Imgproc.resize(templateImage, resizedTemplate, Size(templateW.toDouble(), templateH.toDouble()))

                val result = Mat()
                Imgproc.matchTemplate(screenGray, resizedTemplate, result, Imgproc.TM_CCOEFF_NORMED)
                val mmr = Core.minMaxLoc(result)

                if (bestMatch == null || mmr.maxVal > bestMatch.maxVal) {
                    bestMatch = mmr
                    bestScale = scale
                }
                resizedTemplate.release()
                    result.release()
                }

            val bestSimilarity = bestMatch?.maxVal ?: 0.0
            if (bestSimilarity >= 0.75) {
                val matchLoc = bestMatch!!.maxLoc
                val clickX = (matchLoc.x + (templateImage.width() * bestScale) / 2).roundToInt()
                val clickY = (matchLoc.y + (templateImage.height() * bestScale) / 2).roundToInt()
                Log.i(TEMPLATE_SCAN_TAG, "---> FOUND '$templateName' at ($clickX, $clickY) with similarity ${String.format("%.2f", bestSimilarity)}")
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

    private fun parsePointFromString(coords: String?): Point? {
        if (coords.isNullOrBlank()) return null
        return try {
            val parts = coords.split(':')
            val x = parts[0].toInt()
            val y = parts[1].toInt()
            Point(x, y)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse point coordinates: '$coords'", e)
            null
        }
    }

    private fun parsePointFromJsonObject(json: JSONObject, key: String): Point? {
        return try {
            if (!json.has(key)) return null
            val coordsObj = json.optJSONObject(key)
            if (coordsObj != null) {
                val x = coordsObj.optInt("x", -1)
                val y = coordsObj.optInt("y", -1)
                if (x >= 0 && y >= 0) {
                    Point(x, y)
                } else {
                    null
                }
            } else {
                // Fallback: try parsing as string format "x:y"
                json.optString(key, null)?.let { parsePointFromString(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse point coordinates from JSON object key '$key'", e)
            null
        }
    }

    private fun saveBettingCoordinatesToSharedPreferences(json: JSONObject) {
        try {
            val prefs = getSharedPreferences("bet_config", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            var savedCount = 0

            // Parse and save button_1k_coords
            parsePointFromJsonObject(json, "button_1k_coords")?.let { point ->
                editor.putInt("button_1k_x", point.x).putInt("button_1k_y", point.y)
                savedCount++
                Log.d(TAG, "Saved button_1k coordinates from BETTING JSON: (${point.x}, ${point.y})")
            }

            // Parse and save button_10k_coords
            parsePointFromJsonObject(json, "button_10k_coords")?.let { point ->
                editor.putInt("button_10k_x", point.x).putInt("button_10k_y", point.y)
                savedCount++
                Log.d(TAG, "Saved button_10k coordinates from BETTING JSON: (${point.x}, ${point.y})")
            }

            // Parse and save button_50k_coords
            parsePointFromJsonObject(json, "button_50k_coords")?.let { point ->
                editor.putInt("button_50k_x", point.x).putInt("button_50k_y", point.y)
                savedCount++
                Log.d(TAG, "Saved button_50k coordinates from BETTING JSON: (${point.x}, ${point.y})")
            }

            // Parse and save button_bet_coords (button_cuoc)
            parsePointFromJsonObject(json, "button_bet_coords")?.let { point ->
                editor.putInt("button_cuoc_x", point.x).putInt("button_cuoc_y", point.y)
                savedCount++
                Log.d(TAG, "Saved button_bet (cuoc) coordinates from BETTING JSON: (${point.x}, ${point.y})")
            }

            // Parse and save button_place_bet_coords
            parsePointFromJsonObject(json, "button_place_bet_coords")?.let { point ->
                editor.putInt("button_place_bet_x", point.x).putInt("button_place_bet_y", point.y)
                savedCount++
                Log.d(TAG, "Saved button_place_bet coordinates from BETTING JSON: (${point.x}, ${point.y})")
            }

            editor.apply()
            if (savedCount > 0) {
                Log.i(TAG, "Saved $savedCount button coordinate(s) from BETTING JSON to SharedPreferences")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save betting coordinates to SharedPreferences", e)
        }
    }

    private fun Bitmap.toByteArray(): ByteArray {
        val stream = ByteArrayOutputStream()
        this.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return stream.toByteArray()
    }

    private fun saveLatestHistoryJson(json: JSONObject) {
        try {
            val prefs = getSharedPreferences("bet_config", Context.MODE_PRIVATE)
            prefs.edit().putString("latest_history_json", json.toString()).apply()
            Log.d(TAG, "Saved latest history JSON")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save latest history JSON", e)
        }
    }

    private fun loadLatestHistoryJson(): JSONObject? {
        return try {
            val prefs = getSharedPreferences("bet_config", Context.MODE_PRIVATE)
            val jsonString = prefs.getString("latest_history_json", null)
            if (jsonString != null) {
                JSONObject(jsonString)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load latest history JSON", e)
            null
        }
    }

    private suspend fun captureAndUpload() {
        Log.d(TAG, "Attempting to acquire image...")
        val image = waitForImage() ?: run {
            Log.w(TAG, "No image available for capture, skipping this cycle.")
            return
        }

        var fullBitmap: Bitmap? = null
        var secondsBitmap: Bitmap? = null
        var betAmountBitmap: Bitmap? = null
        var originalBitmap: Bitmap? = null

        try {
            originalBitmap = image.toBitmap() ?: run {
                Log.e(TAG, "Failed to convert Image to Bitmap.")
                return
            }
            Log.i(TAG, "Screenshot captured successfully (${originalBitmap.width}x${originalBitmap.height}).")

            val prefs = getSharedPreferences("bet_config", Context.MODE_PRIVATE)
            val secondsRegionString = prefs.getString("secondsRegion", null)
            val betAmountRegionString = prefs.getString("betAmountRegion", null)
            val secondsRect = parseRectFromString(secondsRegionString)
            val betAmountRect = parseRectFromString(betAmountRegionString)

            // Crop regions from original bitmap before scaling
            val originalSecondsBitmap = secondsRect?.let { rect -> originalBitmap.crop(rect) }
            val originalBetAmountBitmap = betAmountRect?.let { rect -> originalBitmap.crop(rect) }

            // Scale screenshot to 60% before uploading
            val scaleFactor = 0.6f
            val scaledWidth = (originalBitmap.width * scaleFactor).toInt()
            val scaledHeight = (originalBitmap.height * scaleFactor).toInt()
            fullBitmap = Bitmap.createScaledBitmap(originalBitmap, scaledWidth, scaledHeight, true)
            Log.i(TAG, "Screenshot scaled to 60%: (${scaledWidth}x${scaledHeight})")

            // Scale region bitmaps to 60% as well
            secondsBitmap = originalSecondsBitmap?.let { 
                Bitmap.createScaledBitmap(it, (it.width * scaleFactor).toInt(), (it.height * scaleFactor).toInt(), true)
            }
            betAmountBitmap = originalBetAmountBitmap?.let { 
                Bitmap.createScaledBitmap(it, (it.width * scaleFactor).toInt(), (it.height * scaleFactor).toInt(), true)
            }
            
            // Recycle original region bitmaps (originalBitmap will be recycled in finally block after template matching)
            originalSecondsBitmap?.recycle()
            originalBetAmountBitmap?.recycle()

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
            
            // Get real device screen dimensions
            val displayMetrics = resources.displayMetrics
            val deviceRealWidth = displayMetrics.widthPixels
            val deviceRealHeight = displayMetrics.heightPixels
            
            multipartBody.addFormDataPart("device_name", deviceName)
            multipartBody.addFormDataPart("betting_method", bettingMethod)
            multipartBody.addFormDataPart("device_real_width", deviceRealWidth.toString())
            multipartBody.addFormDataPart("device_real_height", deviceRealHeight.toString())
            secondsRegionString?.let { multipartBody.addFormDataPart("seconds_region_coords", it) }
            betAmountRegionString?.let { multipartBody.addFormDataPart("bet_amount_region_coords", it) }

            Log.i(TAG, "Preparing to upload screenshot for device: '$deviceName' with method: '$bettingMethod'")
            val request = Request.Builder()
                .url("https://lukistar.space/api/mobile/analyze")
                .post(multipartBody.build())
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (response.isSuccessful && responseBody != null) {
                        Log.i(TAG, "UPLOAD SUCCESS (code=${response.code}).")
                        // Use original bitmap for template matching (not scaled)
                        handleServerResponse(responseBody, originalBitmap)
                    } else {
                        Log.e(TAG, "!!! UPLOAD FAILED (code=${response.code}). Server Response: $responseBody")
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "!!! UPLOAD FAILED due to IOException.", e)
            }

        } catch (e: Exception) {
            Log.e(TAG, "An unexpected error occurred in captureAndUpload.", e)
        } finally {
            fullBitmap?.recycle()
            secondsBitmap?.recycle()
            betAmountBitmap?.recycle()
            // Recycle originalBitmap after template matching is done
            originalBitmap?.recycle()
            image.close()
            Log.d(TAG, "All bitmaps and image are released.")
        }
    }

    private suspend fun handleServerResponse(responseBody: String, screenBitmap: Bitmap) {
        if (!isProcessingResponse.compareAndSet(false, true)) {
            Log.w(TAG, "Already processing a response, skipping this one.")
            return
        }

        try {
            val json = JSONObject(responseBody)
            val imageType = json.optString("image_type", "")
            val seconds = json.optInt("seconds", -1)

                Log.d(TAG, "Server response parsed: image_type='$imageType', seconds=$seconds")

            // Store latest BETTING JSON and save coordinates to SharedPreferences
            if (imageType == "BETTING") {
                latestBettingJson = json
                saveBettingCoordinatesToSharedPreferences(json)
            }

            if (imageType == "BETTING" && seconds in 25..59) {
                Log.i(TAG, "CLICK CONDITION MET: image_type is BETTING and seconds ($seconds) is in range [25, 59].")
                        val historyLocation = findTemplateLocation(screenBitmap, "history")
                        if (historyLocation != null) {
                            AutoClickService.requestClick(historyLocation.x, historyLocation.y)
                    Log.i(TAG, "Clicked history button at (${historyLocation.x}, ${historyLocation.y})")
                    
                    // Delay 3 seconds then capture and send screenshot
                    delay(HISTORY_CLICK_DELAY_MS)
                    Log.i(TAG, "Capturing screenshot after history click delay...")
                    // Launch in a new coroutine to avoid mutex deadlock (handleServerResponse is already called from captureAndUpload which holds the mutex)
                    serviceScope.launch {
                        try {
                            captureMutex.withLock {
                                // The mutex will ensure only one capture happens at a time
                                captureAndUpload()
                            }
                        } finally {
                            // Reset flag only after capture/upload is complete
                            isProcessingResponse.set(false)
                        }
                    }
                    return // Exit early, flag will be reset in the new coroutine
                    } else {
                    Log.w(TAG, "Could not find history button.")
                }
            } else if (imageType == "HISTORY") {
                // Check if tien_thang field exists for HISTORY JSON
                if (!json.has("tien_thang")) {
                    Log.e(TAG, "HISTORY JSON missing 'tien_thang' field. Stopping capture.")
                    captureJob?.cancel()
                    isProcessingResponse.set(false)
                    return
                }

                val winLoss = json.optString("win_loss", "")
                val tienThang = json.optDouble("tien_thang", 0.0)
                val isWinLossNull = json.isNull("win_loss")
                val isWinningsAmountNull = json.isNull("winnings_amount")

                Log.d(TAG, "HISTORY data: win_loss='$winLoss', tien_thang=$tienThang")

                // Handle win_loss = "unknown" cases
                    if (winLoss == "unknown") {
                        consecutiveUnknownCount++
                    Log.w(TAG, "win_loss is 'unknown' (count: $consecutiveUnknownCount/$MAX_CONSECUTIVE_UNKNOWN)")

                    if (consecutiveUnknownCount >= MAX_CONSECUTIVE_UNKNOWN) {
                        Log.e(TAG, "Reached $MAX_CONSECUTIVE_UNKNOWN consecutive 'unknown' results. Stopping capture.")
                            captureJob?.cancel()
                            isProcessingResponse.set(false)
                            return
                    }

                    // Case 1: win_loss = "unknown" and tien_thang = 0
                    if (tienThang == 0.0) {
                        Log.i(TAG, "win_loss='unknown' and tien_thang=0. Loading latest saved JSON.")
                        val savedJson = loadLatestHistoryJson()
                        if (savedJson == null) {
                            Log.w(TAG, "No saved JSON found. Skipping processing.")
                            // Close history popup before exiting
                            Log.i(TAG, "Closing history popup by clicking (5, 5)")
                            AutoClickService.requestClick(5, 5)
                            isProcessingResponse.set(false)
                            return
                        }
                        // Close history popup first before processing
                        Log.i(TAG, "Closing history popup by clicking (5, 5)")
                        AutoClickService.requestClick(5, 5)
                        delay(500) // Wait for popup to close completely before proceeding
                        // Process saved JSON instead
                        val savedWinLoss = savedJson.optString("win_loss", "")
                        processHistoryJson(savedJson, screenBitmap, savedWinLoss)
                    } else {
                        // Case 2: win_loss = "unknown" and tien_thang != 0
                        Log.i(TAG, "win_loss='unknown' and tien_thang != 0. Skipping all processing.")
                        // Still close history popup by clicking (5,5)
                        Log.i(TAG, "Closing history popup by clicking (5, 5)")
                        AutoClickService.requestClick(5, 5)
                        isProcessingResponse.set(false)
                        return
                    }
                } else {
                    // Reset unknown count on successful result
                    consecutiveUnknownCount = 0
                    
                    // Save this JSON as latest history JSON
                    saveLatestHistoryJson(json)

                    // Close history popup first before processing
                    Log.i(TAG, "Closing history popup by clicking (5, 5)")
                    AutoClickService.requestClick(5, 5)
                    delay(500) // Wait for popup to close completely before proceeding

                    // Process normally
                    if (isWinLossNull || isWinningsAmountNull) {
                        Log.w(TAG, "Invalid history data. Skipping processing.")
                    } else {
                        processHistoryJson(json, screenBitmap, winLoss)
                    }
                }
            } else {
                // Reset unknown count for non-HISTORY responses
                consecutiveUnknownCount = 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse or handle server response.", e)
        } finally {
            isProcessingResponse.set(false)
        }
    }

    private suspend fun processHistoryJson(json: JSONObject, screenBitmap: Bitmap, winLoss: String) {
        val betAmount = json.optInt("bet_amount", 0)
        Log.i(TAG, "Processing history JSON with bet_amount: $betAmount, win_loss: '$winLoss'")

        if (betAmount <= 0) {
            Log.w(TAG, "bet_amount is zero or less. Skipping processing.")
            return
        }

        val prefs = getSharedPreferences("bet_config", Context.MODE_PRIVATE)
        val testButtonEnabled = prefs.getBoolean("test_button_enabled", false)

        // Calculate click count based on win_loss
        val clickCount = when (winLoss.lowercase()) {
            "win" -> {
                Log.i(TAG, "win_loss is 'win', setting clickCount = 1")
                1
            }
            "loss" -> {
                val calculated = (betAmount * 2) / 1000
                Log.i(TAG, "win_loss is 'loss', calculating clickCount = bet_amount * 2 / 1000 = $betAmount * 2 / 1000 = $calculated")
                calculated
            }
            "unknown" -> {
                Log.i(TAG, "win_loss is 'unknown', setting clickCount = 0")
                0
            }
            else -> {
                Log.w(TAG, "Unknown win_loss value: '$winLoss', defaulting to clickCount = 0")
                0
            }
        }
        var adjustedClickCount = clickCount

        // Apply test button adjustment
        if (testButtonEnabled) {
            adjustedClickCount = clickCount + 270
            Log.i(TAG, "Test button enabled: adjusted click count from $clickCount to $adjustedClickCount")
        }

        // Get button coordinates from SharedPreferences or JSON
                            val button50kX = prefs.getInt("button_50k_x", -1)
                            val button50kY = prefs.getInt("button_50k_y", -1)
                            val button10kX = prefs.getInt("button_10k_x", -1)
                            val button10kY = prefs.getInt("button_10k_y", -1)
                            val button1kX = prefs.getInt("button_1k_x", -1)
                            val button1kY = prefs.getInt("button_1k_y", -1)
        val buttonCuocX = prefs.getInt("button_cuoc_x", -1)
        val buttonCuocY = prefs.getInt("button_cuoc_y", -1)

        // Helper function to get coordinates with fallback to latestBettingJson
        fun getCoordinateFromJsonOrBetting(key: String, bettingKey: String? = null): Point? {
            // First try from HISTORY JSON
            val point = parsePointFromJsonObject(json, key)
            if (point != null) return point
            
            // Fallback to latestBettingJson if available
            latestBettingJson?.let { bettingJson ->
                val bettingKeyToUse = bettingKey ?: key
                val bettingPoint = parsePointFromJsonObject(bettingJson, bettingKeyToUse)
                if (bettingPoint != null) {
                    Log.d(TAG, "Using coordinate from latest BETTING JSON for key: $bettingKeyToUse")
                    return bettingPoint
                }
            }
            return null
        }

        // Try to get coordinates from SharedPreferences, then JSON (HISTORY or BETTING)
        var button50kPoint: Point? = if (button50kX >= 0 && button50kY >= 0) {
            Point(button50kX, button50kY)
        } else {
            getCoordinateFromJsonOrBetting("button_50k_coords")
        }

        var button10kPoint: Point? = if (button10kX >= 0 && button10kY >= 0) {
            Point(button10kX, button10kY)
        } else {
            getCoordinateFromJsonOrBetting("button_10k_coords")
        }

        var button1kPoint: Point? = if (button1kX >= 0 && button1kY >= 0) {
            Point(button1kX, button1kY)
        } else {
            getCoordinateFromJsonOrBetting("button_1k_coords")
        }

        var buttonCuocPoint: Point? = if (buttonCuocX >= 0 && buttonCuocY >= 0) {
            Point(buttonCuocX, buttonCuocY)
        } else {
            // Try button_cuoc_coords from HISTORY, or button_bet_coords from BETTING
            getCoordinateFromJsonOrBetting("button_cuoc_coords", "button_bet_coords")
        }

        // Save coordinates to SharedPreferences if found in JSON
        if (button50kPoint != null && (button50kX < 0 || button50kY < 0)) {
            prefs.edit().putInt("button_50k_x", button50kPoint.x).putInt("button_50k_y", button50kPoint.y).apply()
        }
        if (button10kPoint != null && (button10kX < 0 || button10kY < 0)) {
            prefs.edit().putInt("button_10k_x", button10kPoint.x).putInt("button_10k_y", button10kPoint.y).apply()
        }
        if (button1kPoint != null && (button1kX < 0 || button1kY < 0)) {
            prefs.edit().putInt("button_1k_x", button1kPoint.x).putInt("button_1k_y", button1kPoint.y).apply()
        }
        if (buttonCuocPoint != null && (buttonCuocX < 0 || buttonCuocY < 0)) {
            prefs.edit().putInt("button_cuoc_x", buttonCuocPoint.x).putInt("button_cuoc_y", buttonCuocPoint.y).apply()
        }

        // Calculate clicks for each button
        val clicks50k = if (adjustedClickCount >= 400) adjustedClickCount / 50 else 0
        val clicks10k = if (adjustedClickCount >= 400) {
            (adjustedClickCount % 50) / 10
        } else if (adjustedClickCount > 30) {
            adjustedClickCount / 10
        } else {
            0
        }
        val clicks1k = if (adjustedClickCount >= 400) {
            (adjustedClickCount % 50) % 10
        } else if (adjustedClickCount > 30) {
            adjustedClickCount % 10
        } else {
            adjustedClickCount
        }

        // Log all button coordinates and click counts
        Log.i(TAG, "=== BUTTON COORDINATES AND CLICK COUNTS ===")
        Log.i(TAG, "Nút Cược: (${buttonCuocPoint?.x ?: "N/A"}, ${buttonCuocPoint?.y ?: "N/A"})")
        Log.i(TAG, "Nút 50K: (${button50kPoint?.x ?: "N/A"}, ${button50kPoint?.y ?: "N/A"}) - Số lần click: $clicks50k")
        Log.i(TAG, "Nút 10K: (${button10kPoint?.x ?: "N/A"}, ${button10kPoint?.y ?: "N/A"}) - Số lần click: $clicks10k")
        Log.i(TAG, "Nút 1K: (${button1kPoint?.x ?: "N/A"}, ${button1kPoint?.y ?: "N/A"}) - Số lần click: $clicks1k")

        // Click "Cược" button first before clicking amount buttons (only if clickCount > 16)
        if (clickCount > 16) {
            if (buttonCuocPoint != null) {
                Log.i(TAG, "Clicking Cược button at (${buttonCuocPoint.x}, ${buttonCuocPoint.y}) - clickCount ($clickCount) > 16")
                AutoClickService.requestClick(buttonCuocPoint.x, buttonCuocPoint.y, "Cược")
                delay(800) // Increased delay to allow UI to update after clicking Cược button
            } else {
                Log.e(TAG, "Cược button coordinates not found. Trying template matching...")
                findTemplateLocation(screenBitmap, "button_cuoc")?.let { point ->
                    buttonCuocPoint = point
                    prefs.edit().putInt("button_cuoc_x", point.x).putInt("button_cuoc_y", point.y).apply()
                    Log.i(TAG, "Clicking Cược button at (${point.x}, ${point.y}) - clickCount ($clickCount) > 16")
                    AutoClickService.requestClick(point.x, point.y, "Cược")
                    delay(800) // Increased delay to allow UI to update after clicking Cược button
                } ?: Log.e(TAG, "Could not find Cược button via template matching.")
            }
        } else {
            Log.i(TAG, "Skipping Cược button click - clickCount ($clickCount) <= 16")
        }

        // Click buttons based on conditions
        if (adjustedClickCount >= 400) {
            // Click 50K
            if (button50kPoint != null) {
                repeat(clicks50k) {
                    Log.d(TAG, "Clicking 50K button (${it + 1}/$clicks50k)")
                    AutoClickService.requestClick(button50kPoint.x, button50kPoint.y, "50K")
                    delay(400) // Increased delay to allow UI to update after each click
                                    }
                                } else {
                Log.e(TAG, "50K button coordinates not found. Trying template matching...")
                findTemplateLocation(screenBitmap, "button_50k")?.let { point ->
                    button50kPoint = point
                    prefs.edit().putInt("button_50k_x", point.x).putInt("button_50k_y", point.y).apply()
                    repeat(clicks50k) {
                        Log.d(TAG, "Clicking 50K button (${it + 1}/$clicks50k)")
                        AutoClickService.requestClick(point.x, point.y)
                        delay(400) // Increased delay to allow UI to update after each click
                    }
                } ?: Log.e(TAG, "Could not find 50K button via template matching.")
            }

            // Click 10K
            if (clicks10k > 0) {
                if (button10kPoint != null) {
                    repeat(clicks10k) {
                        Log.d(TAG, "Clicking 10K button (${it + 1}/$clicks10k)")
                        AutoClickService.requestClick(button10kPoint.x, button10kPoint.y, "10K")
                        delay(400) // Increased delay to allow UI to update after each click
                        }
                    } else {
                    Log.e(TAG, "10K button coordinates not found. Trying template matching...")
                    findTemplateLocation(screenBitmap, "button_10k")?.let { point ->
                        button10kPoint = point
                        prefs.edit().putInt("button_10k_x", point.x).putInt("button_10k_y", point.y).apply()
                        repeat(clicks10k) {
                            Log.d(TAG, "Clicking 10K button (${it + 1}/$clicks10k)")
                            AutoClickService.requestClick(point.x, point.y)
                            delay(400) // Increased delay to allow UI to update after each click
                        }
                    } ?: Log.e(TAG, "Could not find 10K button via template matching.")
                }
            }

            // Click 1K
            if (clicks1k > 0) {
                if (button1kPoint != null) {
                    repeat(clicks1k) {
                        Log.d(TAG, "Clicking 1K button (${it + 1}/$clicks1k)")
                        AutoClickService.requestClick(button1kPoint.x, button1kPoint.y, "1K")
                        delay(400) // Increased delay to allow UI to update after each click
                    }
                } else {
                    Log.e(TAG, "1K button coordinates not found. Trying template matching...")
                    findTemplateLocation(screenBitmap, "button_1k")?.let { point ->
                        button1kPoint = point
                        prefs.edit().putInt("button_1k_x", point.x).putInt("button_1k_y", point.y).apply()
                        repeat(clicks1k) {
                            Log.d(TAG, "Clicking 1K button (${it + 1}/$clicks1k)")
                            AutoClickService.requestClick(point.x, point.y)
                            delay(400) // Increased delay to allow UI to update after each click
                        }
                    } ?: Log.e(TAG, "Could not find 1K button via template matching.")
                }
            }
        } else if (adjustedClickCount > 30) {
            // Click 10K
            if (button10kPoint != null) {
                repeat(clicks10k) {
                    Log.d(TAG, "Clicking 10K button (${it + 1}/$clicks10k)")
                    AutoClickService.requestClick(button10kPoint.x, button10kPoint.y, "10K")
                    delay(400) // Increased delay to allow UI to update after each click
                }
            } else {
                Log.e(TAG, "10K button coordinates not found. Trying template matching...")
                findTemplateLocation(screenBitmap, "button_10k")?.let { point ->
                    button10kPoint = point
                    prefs.edit().putInt("button_10k_x", point.x).putInt("button_10k_y", point.y).apply()
                    repeat(clicks10k) {
                        Log.d(TAG, "Clicking 10K button (${it + 1}/$clicks10k)")
                        AutoClickService.requestClick(point.x, point.y)
                        delay(400) // Increased delay to allow UI to update after each click
                    }
                } ?: Log.e(TAG, "Could not find 10K button via template matching.")
            }

            // Click 1K
            if (clicks1k > 0) {
                if (button1kPoint != null) {
                    repeat(clicks1k) {
                        Log.d(TAG, "Clicking 1K button (${it + 1}/$clicks1k)")
                        AutoClickService.requestClick(button1kPoint.x, button1kPoint.y, "1K")
                        delay(400) // Increased delay to allow UI to update after each click
                    }
                } else {
                    Log.e(TAG, "1K button coordinates not found. Trying template matching...")
                    findTemplateLocation(screenBitmap, "button_1k")?.let { point ->
                        button1kPoint = point
                        prefs.edit().putInt("button_1k_x", point.x).putInt("button_1k_y", point.y).apply()
                        repeat(clicks1k) {
                            Log.d(TAG, "Clicking 1K button (${it + 1}/$clicks1k)")
                            AutoClickService.requestClick(point.x, point.y)
                            delay(400) // Increased delay to allow UI to update after each click
                        }
                    } ?: Log.e(TAG, "Could not find 1K button via template matching.")
                }
            }
                    } else {
            // Click 1K only
            if (button1kPoint != null) {
                repeat(clicks1k) {
                    Log.d(TAG, "Clicking 1K button (${it + 1}/$clicks1k)")
                    AutoClickService.requestClick(button1kPoint.x, button1kPoint.y, "1K")
                    delay(400) // Increased delay to allow UI to update after each click
                }
            } else {
                Log.e(TAG, "1K button coordinates not found. Trying template matching...")
                findTemplateLocation(screenBitmap, "button_1k")?.let { point ->
                    button1kPoint = point
                    prefs.edit().putInt("button_1k_x", point.x).putInt("button_1k_y", point.y).apply()
                    repeat(clicks1k) {
                        Log.d(TAG, "Clicking 1K button (${it + 1}/$clicks1k)")
                        AutoClickService.requestClick(point.x, point.y)
                        delay(400) // Increased delay to allow UI to update after each click
                    }
                } ?: Log.e(TAG, "Could not find 1K button via template matching.")
            }
        }

        // Click "Đặt Cược" button (betting_button) - skip if test button is enabled
        if (!testButtonEnabled) {
            clickPlaceBetButton(screenBitmap, prefs)
        } else {
            Log.i(TAG, "Test button enabled: Skipping Đặt Cược button click")
        }
    }

    private suspend fun clickPlaceBetButton(screenBitmap: Bitmap, prefs: android.content.SharedPreferences) {
        // Priority 1: SharedPreferences
        val buttonPlaceBetX = prefs.getInt("button_place_bet_x", -1)
        val buttonPlaceBetY = prefs.getInt("button_place_bet_y", -1)
        
        if (buttonPlaceBetX >= 0 && buttonPlaceBetY >= 0) {
            Log.i(TAG, "Using Đặt Cược button coordinates from SharedPreferences: ($buttonPlaceBetX, $buttonPlaceBetY)")
            AutoClickService.requestClick(buttonPlaceBetX, buttonPlaceBetY, "Đặt Cược")
            lastBettingClickTime = System.currentTimeMillis()
            Log.i(TAG, "Clicked Đặt Cược button. Cooldown started.")
            return
        }

        // Priority 2: Latest BETTING JSON
        latestBettingJson?.let { json ->
            parsePointFromJsonObject(json, "button_place_bet_coords")?.let { point ->
                Log.i(TAG, "Using Đặt Cược button coordinates from latest BETTING JSON: (${point.x}, ${point.y})")
                // Save to SharedPreferences for future use
                prefs.edit().putInt("button_place_bet_x", point.x).putInt("button_place_bet_y", point.y).apply()
                AutoClickService.requestClick(point.x, point.y, "Đặt Cược")
                lastBettingClickTime = System.currentTimeMillis()
                Log.i(TAG, "Clicked Đặt Cược button. Cooldown started.")
                return
            }
        }

        // Priority 3: Template matching
        Log.i(TAG, "Đặt Cược button coordinates not found in SharedPreferences or JSON. Trying template matching...")
        findTemplateLocation(screenBitmap, "betting_button")?.let { point ->
            Log.i(TAG, "Found Đặt Cược button via template matching: (${point.x}, ${point.y})")
            // Save to SharedPreferences for future use
            prefs.edit().putInt("button_place_bet_x", point.x).putInt("button_place_bet_y", point.y).apply()
            AutoClickService.requestClick(point.x, point.y, "Đặt Cược")
            lastBettingClickTime = System.currentTimeMillis()
            Log.i(TAG, "Clicked Đặt Cược button. Cooldown started.")
        } ?: Log.e(TAG, "Could not find Đặt Cược button via template matching.")
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



