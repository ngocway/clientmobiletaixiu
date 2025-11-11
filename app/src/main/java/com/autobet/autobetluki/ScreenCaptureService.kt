
package com.autobet.autobetluki

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
                Log.d(TAG, "-------------------- New Capture Cycle --------------------")
                captureMutex.withLock {
                    captureAndUpload()
                }
                delay(1_000L) // Capture every 1 second
            }
            Log.i(TAG, "Capture loop has ended.")
        }
    }

    private fun scanScreenForAllTemplates(bitmap: Bitmap) {
        Log.i(TEMPLATE_SCAN_TAG, "--- Starting Screen Scan for All Templates (Grayscale) ---")
        val screenGray = Mat()
        Utils.bitmapToMat(bitmap, screenGray)
        Imgproc.cvtColor(screenGray, screenGray, Imgproc.COLOR_RGBA2GRAY)

        val drawableFields = R.drawable::class.java.fields
        var templatesFound = 0
        var templatesProcessed = 0
        val typedValue = TypedValue()

        for (field in drawableFields) {
            try {
                val resId = field.getInt(null)
                resources.getValue(resId, typedValue, true)
                val resPath = typedValue.string.toString()
                val resName = field.name

                if (resName.startsWith("abc_") || resName.startsWith("ic_") || resName.endsWith("_background") || resName.endsWith("_foreground")) {
                    continue
                }

                if (!resPath.endsWith(".jpg") && !resPath.endsWith(".jpeg") && !resPath.endsWith(".png")) {
                    Log.d(TEMPLATE_SCAN_TAG, "Skipping non-image file: $resPath")
                    continue
                }

                Log.d(TEMPLATE_SCAN_TAG, "Processing image: $resPath")
                templatesProcessed++

                var templateImage: Mat? = null
                try {
                    templateImage = Utils.loadResource(this, resId, Imgcodecs.IMREAD_GRAYSCALE)
                    if (templateImage.empty()) {
                        Log.w(TEMPLATE_SCAN_TAG, "Could not load resource as image: $resName")
                        continue
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
                        val logMessage = String.format("---> FOUND: '%s' with similarity: %.2f at scale: %d%%", resName, bestSimilarity, (bestScale * 100).roundToInt())
                        Log.i(TEMPLATE_SCAN_TAG, logMessage)
                        templatesFound++
                    } else {
                        val logMessage = String.format("     NOT FOUND: '%s'. Best similarity was %.2f", resName, bestSimilarity)
                        Log.i(TEMPLATE_SCAN_TAG, logMessage)
                    }

                } catch (e: Exception) {
                    Log.e(TEMPLATE_SCAN_TAG, "Error processing resource '$resName'.", e)
                } finally {
                    templateImage?.release()
                }
            } catch (e: Exception) {
                // Ignore errors from R.drawable fields that are not resources
            }
        }
        Log.i(TEMPLATE_SCAN_TAG, "--- Screen Scan Finished. Processed $templatesProcessed images. Found $templatesFound matches. ---")
        screenGray.release()
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
            Log.w(TAG, "Failed to acquire image, skipping this cycle.")
            return
        }

        var fullBitmap: Bitmap? = null
        var secondsBitmap: Bitmap? = null
        var betAmountBitmap: Bitmap? = null

        try {
            fullBitmap = image.toBitmap() ?: run {
                Log.e(TAG, "Failed to convert Image to Bitmap.")
                return
            }
            Log.i(TAG, "Screenshot captured successfully (${fullBitmap.width}x${fullBitmap.height}).")

            // Perform template matching on the full screenshot
            scanScreenForAllTemplates(fullBitmap)

            // --- Existing upload logic --- 
            val prefs = getSharedPreferences("bet_config", Context.MODE_PRIVATE)
            val secondsRegionString = prefs.getString("secondsRegion", null)
            val betAmountRegionString = prefs.getString("betAmountRegion", null)
            val secondsRect = parseRectFromString(secondsRegionString)
            val betAmountRect = parseRectFromString(betAmountRegionString)

            secondsBitmap = secondsRect?.let { rect -> fullBitmap.crop(rect) }
            betAmountBitmap = betAmountRect?.let { rect -> fullBitmap.crop(rect) }

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
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (response.isSuccessful) {
                        Log.i(TAG, "UPLOAD SUCCESS (code=${response.code}). Server Response: $responseBody")
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
            image.close()
            Log.d(TAG, "All bitmaps and image are released.")
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
