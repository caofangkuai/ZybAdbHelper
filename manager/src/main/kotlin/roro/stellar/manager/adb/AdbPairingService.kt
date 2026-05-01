package roro.stellar.manager.adb

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Loeder
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.lifecycle.Observer
import com.cfks.utils.ScreenCaptureHelper
import com.cfks.utils.TesseractHelper
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import roro.stellar.manager.R
import roro.stellar.manager.StellarSettings
import java.io.File
import java.util.regex.Pattern

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {

        const val notificationChannel = "adb_pairing"
        const val alertNotificationChannel = "adb_pairing_alert"

        private const val tag = "AdbPairingService"

        private const val notificationId = 1
        private const val alertNotificationId = 2
        private const val screenshotNotifyId = 3
        private const val replyRequestId = 1
        private const val stopRequestId = 2
        private const val retryRequestId = 3
        private const val stopAndRetryRequestId = 4
        private const val startAction = "start"
        private const val stopAction = "stop"
        private const val stopAndRetryAction = "stop_and_retry"
        private const val replyAction = "reply"
        private const val remoteInputResultKey = "paring_code"
        private const val portKey = "paring_code"

        @Volatile
        private var isRunning = false

        fun startIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(startAction)

        private fun stopIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(stopAction)

        private fun stopAndRetryIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(stopAndRetryAction)

        private fun replyIntent(context: Context, port: Int): Intent =
            Intent(context, AdbPairingService::class.java).setAction(replyAction).putExtra(portKey, port)
    }

    private var adbMdns: AdbMdns? = null
    private val retryHandler = Handler(Looper.getMainLooper())
    private var discoveredPort: Int = -1

    // 媒体内容观察者（替代 FileObserver，更可靠）
    private var mediaContentObserver: ContentObserver? = null
    private var lastProcessedPath = ""
    private var lastProcessTime = 0L

    // OCR处理中的标志
    private var isOcrProcessing = false

    /**
     * 观察者：监听配对服务端口
     */
    private val observer = Observer<Int> { port ->
        Log.i(tag, "配对服务端口: $port")
        if (port <= 0) return@Observer

        discoveredPort = port

        val notification = createInputNotification(port)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(notificationId, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(notificationId, notification)
            }
            Log.i(tag, "已更新通知为输入配对码")
            startScreenshotMonitor()
        } catch (e: Exception) {
            Log.e(tag, "更新通知失败", e)
            getSystemService(NotificationManager::class.java).notify(notificationId, notification)

            val alertNotification = Notification.Builder(this, alertNotificationChannel)
                .setSmallIcon(R.drawable.ic_stellar)
                .setContentTitle(getString(R.string.pairing_service_found))
                .setContentText(getString(R.string.enter_pairing_code))
                .addAction(replyNotificationAction(port))
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(alertNotificationId, alertNotification)
            startScreenshotMonitor()
        }
    }

    private var started = false

    override fun onCreate() {
        super.onCreate()

        val notificationManager = getSystemService(NotificationManager::class.java)

        notificationManager.createNotificationChannel(
            NotificationChannel(
                notificationChannel,
                getString(R.string.wireless_debugging_pairing_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                setShowBadge(false)
                setAllowBubbles(false)
            })

        notificationManager.createNotificationChannel(
            NotificationChannel(
                alertNotificationChannel,
                getString(R.string.pairing_alert_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setShowBadge(true)
                enableVibration(true)
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = when (intent?.action) {
            startAction -> {
                onStart()
            }
            replyAction -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(remoteInputResultKey) ?: ""
                val port = intent.getIntExtra(portKey, -1)
                if (port != -1) {
                    onInput(code.toString(), port)
                } else {
                    onStart()
                }
            }
            stopAction -> {
                onStopSearch()
            }
            stopAndRetryAction -> {
                onStopAndRetry()
            }
            else -> {
                return START_NOT_STICKY
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(notificationId, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Throwable) {
            Log.e(tag, "启动前台服务失败", e)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && e is ForegroundServiceStartNotAllowedException) {
                getSystemService(NotificationManager::class.java).notify(notificationId, notification)
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startSearch() {
        if (started) return
        started = true
        adbMdns = AdbMdns(
            this,
            AdbMdns.TLS_PAIRING,
            observer,
            onMaxRefresh = { onSearchMaxRefresh() }
        ).apply { start() }
    }

    private fun stopSearch() {
        if (!started) return
        started = false
        try {
            adbMdns?.stop()
        } catch (e: Exception) {
            Log.e(tag, "停止搜索失败", e)
        }
    }

    /**
     * 启动截图监听（使用 MediaStore ContentObserver，更可靠）
     */
    private fun startScreenshotMonitor() {
        if (mediaContentObserver != null) {
            Log.d(tag, "截图监听已存在，跳过重复启动")
            return
        }

        Log.i(tag, "启动截图监听，等待无线调试配对码截图...")
        
        showNotification("📸 截图监听已启动", "正在等待无线调试配对码截图...")

        // 使用 ContentObserver 监听 MediaStore 的变化
        mediaContentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                if (uri == null) return
                
                Log.d(tag, "MediaStore 变化: $uri")
                
                // 检查是否是图片插入事件
                if (uri.toString().contains(MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString())) {
                    // 延迟一点等待文件写入完成
                    retryHandler.postDelayed({
                        checkLatestScreenshot()
                    }, 500)
                }
            }
        }
        
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaContentObserver!!
        )
        
        // 也检查一下现有的截图目录
        checkExistingScreenshots()
    }

    /**
     * 检查最新截图
     */
    private fun checkLatestScreenshot() {
        try {
            val screenshots = getLatestScreenshots()
            if (screenshots.isNotEmpty()) {
                val latest = screenshots.first()
                val filePath = latest.path
                val modifiedTime = latest.dateModified
                
                // 避免重复处理同一张截图
                if (filePath == lastProcessedPath || 
                    (System.currentTimeMillis() - lastProcessTime < 3000)) {
                    Log.d(tag, "跳过重复处理: $filePath")
                    return
                }
                
                Log.i(tag, "发现最新截图: $filePath")
                lastProcessedPath = filePath
                lastProcessTime = System.currentTimeMillis()
                
                processScreenshotForPairingCode(filePath)
            }
        } catch (e: Exception) {
            Log.e(tag, "检查最新截图失败", e)
        }
    }

    /**
     * 检查已存在的截图（启动时检查一次）
     */
    private fun checkExistingScreenshots() {
        try {
            val screenshots = getLatestScreenshots()
            if (screenshots.isNotEmpty()) {
                val latest = screenshots.first()
                Log.i(tag, "发现已存在的截图: ${latest.path}")
                showNotification("📸 检测到已有截图", "正在处理，请稍候...")
                processScreenshotForPairingCode(latest.path)
            }
        } catch (e: Exception) {
            Log.e(tag, "检查现有截图失败", e)
        }
    }

    /**
     * 获取最新的截图列表
     */
    private data class ScreenshotInfo(val path: String, val dateModified: Long)

    private fun getLatestScreenshots(): List<ScreenshotInfo> {
        val screenshots = mutableListOf<ScreenshotInfo>()
        
        val projection = arrayOf(
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.DATA} LIKE ?"
        val selectionArgs = arrayOf(
            "%Screenshots%",
            "%Screenshots%"
        )
        
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )
        
        cursor?.use {
            while (it.moveToNext()) {
                val path = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                val dateModified = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)) * 1000
                
                // 只处理最近30秒内的截图
                if (System.currentTimeMillis() - dateModified < 30000) {
                    screenshots.add(ScreenshotInfo(path, dateModified))
                }
            }
        }
        
        return screenshots
    }

    /**
     * 停止截图监听
     */
    private fun stopScreenshotMonitor() {
        mediaContentObserver?.let {
            contentResolver.unregisterContentObserver(it)
            mediaContentObserver = null
        }
        lastProcessedPath = ""
        Log.d(tag, "截图监听已停止")
    }

    /**
     * 显示普通通知
     */
    private fun showNotification(title: String, content: String) {
        val notification = Notification.Builder(this, alertNotificationChannel)
            .setSmallIcon(R.drawable.ic_stellar)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .build()
        
        try {
            getSystemService(NotificationManager::class.java).notify(screenshotNotifyId, notification)
        } catch (e: Exception) {
            Log.e(tag, "显示通知失败", e)
        }
    }

    /**
     * 处理截图，通过OCR识别配对码
     */
    private fun processScreenshotForPairingCode(filePath: String) {
        if (isOcrProcessing) {
            Log.d(tag, "OCR处理中，跳过本次截图")
            showNotification("⚠️ 处理中", "已有截图在处理中，请稍候...")
            return
        }

        Log.i(tag, "开始处理截图: $filePath")
        showNotification("🔍 正在识别", "正在分析截图中的配对码...")

        GlobalScope.launch(Dispatchers.IO) {
            isOcrProcessing = true

            try {
                // 1. 检查文件是否存在
                val file = File(filePath)
                if (!file.exists()) {
                    Log.e(tag, "截图文件不存在: $filePath")
                    showNotification("❌ 识别失败", "截图文件不存在")
                    return@launch
                }
                
                Log.d(tag, "截图文件大小: ${file.length()} bytes")

                // 2. 加载原始截图Bitmap
                val options = android.graphics.BitmapFactory.Options()
                options.inPreferredConfig = Bitmap.Config.ARGB_8888
                
                val fullBitmap = try {
                    android.graphics.BitmapFactory.decodeFile(filePath, options)
                } catch (e: OutOfMemoryError) {
                    Log.e(tag, "内存不足", e)
                    showNotification("❌ 内存不足", "请关闭其他应用后重试")
                    return@launch
                }

                if (fullBitmap == null) {
                    Log.e(tag, "无法加载截图: $filePath")
                    showNotification("❌ 加载失败", "无法加载截图文件")
                    return@launch
                }
                
                Log.d(tag, "截图尺寸: ${fullBitmap.width}x${fullBitmap.height}")

                // 3. 获取裁剪区域
                val captureRect = try {
                    ScreenCaptureHelper.getAdaptedCaptureRect()
                } catch (e: Exception) {
                    Log.e(tag, "获取裁剪区域失败", e)
                    fullBitmap.recycle()
                    showNotification("❌ 裁剪失败", "获取裁剪区域失败")
                    return@launch
                }
                Log.d(tag, "裁剪区域: ${captureRect.toShortString()}")

                // 4. 裁剪 Bitmap
                val croppedBitmap = try {
                    ScreenCaptureHelper.cropBitmap(fullBitmap, captureRect)
                } catch (e: Exception) {
                    Log.e(tag, "裁剪失败", e)
                    fullBitmap.recycle()
                    showNotification("❌ 裁剪失败", e.message ?: "未知错误")
                    return@launch
                } finally {
                    fullBitmap.recycle()
                }

                if (croppedBitmap == null) {
                    Log.e(tag, "裁剪结果为空")
                    showNotification("❌ 裁剪失败", "裁剪区域无效")
                    return@launch
                }
                
                Log.d(tag, "裁剪后尺寸: ${croppedBitmap.width}x${croppedBitmap.height}")

                // 5. OCR 识别
                Log.d(tag, "开始OCR识别...")
                showNotification("🔍 OCR识别中", "正在识别截图中的文字...")
                
                val recognizedText = try {
                    TesseractHelper.recognizeText(this@AdbPairingService, croppedBitmap)
                } catch (e: Exception) {
                    Log.e(tag, "OCR识别异常", e)
                    croppedBitmap.recycle()
                    showNotification("❌ OCR错误", e.message ?: "识别失败")
                    return@launch
                } finally {
                    croppedBitmap.recycle()
                }

                if (recognizedText.isNullOrEmpty()) {
                    Log.w(tag, "OCR识别结果为空")
                    showNotification("❌ 未识别到文字", "请确保截图清晰且包含配对码")
                    return@launch
                }

                Log.d(tag, "OCR原始结果: $recognizedText")

                // 6. 提取配对码
                val pairCode = extractPairCode(recognizedText)

                if (pairCode == null) {
                    Log.w(tag, "未找到配对码: $recognizedText")
                    showNotification("❌ 未找到配对码", "识别内容: ${recognizedText.take(30)}...")
                    return@launch
                }

                Log.i(tag, "成功识别配对码: $pairCode")
                showNotification("✅ 识别成功", "配对码: $pairCode，正在配对...")

                // 7. 执行配对
                retryHandler.post {
                    performPairingWithCode(pairCode, discoveredPort)
                }

            } catch (e: Exception) {
                Log.e(tag, "处理异常", e)
                showNotification("❌ 处理异常", e.message ?: "未知错误")
            } finally {
                isOcrProcessing = false
            }
        }
    }

    /**
     * 提取6位数字配对码
     */
    private fun extractPairCode(text: String): String? {
        val pattern = Pattern.compile("\\b(\\d{6})\\b")
        val matcher = pattern.matcher(text)
        if (matcher.find()) {
            return matcher.group(1)
        }
        val pattern2 = Pattern.compile("(\\d{6})")
        val matcher2 = pattern2.matcher(text.replace(" ", ""))
        if (matcher2.find()) {
            return matcher2.group(1)
        }
        return null
    }

    /**
     * 执行配对
     */
    private fun performPairingWithCode(code: String, port: Int) {
        if (port <= 0) {
            Log.e(tag, "无效端口: $port")
            showNotification("❌ 配对失败", "无效的服务端口")
            return
        }
        Log.i(tag, "执行配对: code=$code, port=$port")
        onInput(code, port)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        retryHandler.removeCallbacksAndMessages(null)
        stopScreenshotMonitor()
        stopSearch()
        adbMdns?.destroy()
        adbMdns = null
        connectMdns?.destroy()
        connectMdns = null
    }

    private fun onStart(): Notification {
        if (isRunning && started) {
            Log.i(tag, "服务已在运行，忽略重复启动")
            return searchingNotification
        }
        isRunning = true
        startSearch()
        return searchingNotification
    }

    private fun onStopSearch(): Notification {
        stopSearch()
        stopScreenshotMonitor()
        return createManualInputNotification(discoveredPort)
    }

    private fun onStopAndRetry(): Notification {
        stopSearch()
        stopScreenshotMonitor()
        adbMdns?.destroy()
        adbMdns = null
        return onStart()
    }

    private fun onSearchMaxRefresh() {
        Log.i(tag, "搜索次数已达上限")
        stopSearch()
        stopScreenshotMonitor()
        val notification = createMaxRefreshNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(notificationId, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            Log.e(tag, "更新前台通知失败", e)
            getSystemService(NotificationManager::class.java).notify(notificationId, notification)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun onInput(code: String, port: Int): Notification {
        if (port == -1) {
            return createManualInputNotification(-1)
        }

        GlobalScope.launch(Dispatchers.IO) {
            val host = "127.0.0.1"

            val key = try {
                AdbKey(PreferenceAdbKeyStore(StellarSettings.getPreferences()), "Stellar")
            } catch (e: Throwable) {
                e.printStackTrace()
                return@launch
            }

            AdbPairingClient(host, port, code, key).runCatching {
                start()
            }.onFailure {
                handleResult(false, it)
            }.onSuccess {
                handleResult(it, null)
            }
        }

        return workingNotification
    }

    private var connectMdns: AdbMdns? = null

    private fun handleResult(success: Boolean, exception: Throwable?) {
        retryHandler.post {
            if (success) {
                Log.i(tag, "配对成功，开始搜索连接服务")
                stopSearch()
                stopScreenshotMonitor()

                val successNotification = Notification.Builder(this, notificationChannel)
                    .setSmallIcon(R.drawable.ic_stellar)
                    .setContentTitle(getString(R.string.pairing_success))
                    .setContentText(getString(R.string.searching_connect_service))
                    .setOngoing(true)
                    .build()

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(notificationId, successNotification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } else {
                        startForeground(notificationId, successNotification)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "更新前台通知失败", e)
                }

                searchConnectService()
            } else {
                val title = getString(R.string.pairing_failed_retrying)
                val errorDetail = if (exception != null) {
                    "${exception.javaClass.simpleName}: ${exception.message}"
                } else {
                    "未知错误"
                }
                val text = getString(R.string.please_wait_auto_return)

                Log.i(tag, "配对失败，正在重试: $errorDetail")

                val failureNotification = Notification.Builder(this, alertNotificationChannel)
                    .setSmallIcon(R.drawable.ic_stellar)
                    .setContentTitle(title)
                    .setContentText(errorDetail.take(50))
                    .setStyle(Notification.BigTextStyle().bigText("$text\n\n错误详情: $errorDetail"))
                    .setOngoing(true)
                    .build()

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(notificationId, failureNotification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } else {
                        startForeground(notificationId, failureNotification)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "更新前台通知失败", e)
                }

                retryHandler.postDelayed({
                    val retryNotification = createManualInputNotification(discoveredPort)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            startForeground(notificationId, retryNotification,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                        } else {
                            startForeground(notificationId, retryNotification)
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "更新前台通知失败", e)
                    }
                }, 2000)
            }
        }
    }

    private fun searchConnectService() {
        val connectObserver = Observer<Int> { port ->
            Log.i(tag, "连接服务端口: $port")
            if (port <= 0) return@Observer

            connectMdns?.destroy()
            connectMdns = null

            onConnectServiceFound(port)
        }

        connectMdns = AdbMdns(
            this,
            AdbMdns.TLS_CONNECT,
            connectObserver,
            onMaxRefresh = {
                Log.w(tag, "搜索连接服务次数已达上限")
                onConnectServiceMaxRefresh()
            }
        ).apply { start() }
    }

    private fun onConnectServiceFound(port: Int) {
        retryHandler.post {
            Log.i(tag, "找到连接服务端口: $port")

            val preferences = StellarSettings.getPreferences()
            val tcpipPortEnabled = preferences.getBoolean(StellarSettings.TCPIP_PORT_ENABLED, true)
            val currentPort = preferences.getString(StellarSettings.TCPIP_PORT, "")

            if (tcpipPortEnabled && currentPort.isNullOrEmpty()) {
                preferences.edit {
                    putString(StellarSettings.TCPIP_PORT, port.toString())
                }
                Log.i(tag, "自动设置 TCP 端口: $port")
            }

            grantSecureSettingsPermission(port)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun grantSecureSettingsPermission(port: Int) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val key = AdbKey(PreferenceAdbKeyStore(StellarSettings.getPreferences()), "stellar")

                val maxWait = 5000L
                val interval = 200L
                var elapsed = 0L
                while (elapsed < maxWait) {
                    try {
                        java.net.Socket("127.0.0.1", port).close()
                        break
                    } catch (_: Exception) {
                        kotlinx.coroutines.delay(interval)
                        elapsed += interval
                    }
                }

                AdbClient("127.0.0.1", port, key).use { client ->
                    client.connect()
                    val command = "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
                    client.shellCommand(command) { output ->
                        Log.d(tag, "授权命令输出: ${String(output)}")
                    }
                }
                Log.i(tag, "WRITE_SECURE_SETTINGS 权限授权成功")
            } catch (e: Exception) {
                Log.e(tag, "自动授权 WRITE_SECURE_SETTINGS 失败", e)
            }

            retryHandler.post {
                navigateToStarter(port)
            }
        }
    }

    private fun navigateToStarter(port: Int) {
        val intent = roro.stellar.manager.ui.features.manager.ManagerActivity.createStarterIntent(
            this,
            isRoot = false,
            host = "127.0.0.1",
            port = port
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)

        stopForeground(STOP_FOREGROUND_REMOVE)
        isRunning = false
        stopSelf()
    }

    private fun onConnectServiceMaxRefresh() {
        retryHandler.post {
            Log.w(tag, "连接服务搜索次数已达上限，尝试使用系统端口")

            val systemPort = roro.stellar.manager.util.EnvironmentUtils.getAdbTcpPort()
            if (systemPort in 1..65535) {
                grantSecureSettingsPermission(systemPort)
            } else {
                val notification = Notification.Builder(this, notificationChannel)
                    .setSmallIcon(R.drawable.ic_stellar)
                    .setContentTitle(getString(R.string.connect_service_not_found))
                    .setContentText(getString(R.string.please_open_app_manually))
                    .setAutoCancel(true)
                    .build()

                stopForeground(STOP_FOREGROUND_REMOVE)
                getSystemService(NotificationManager::class.java).notify(notificationId, notification)
                isRunning = false
                stopSelf()
            }
        }
    }

    // ==================== 通知构建方法 ====================

    private val stopNotificationAction by lazy {
        val pendingIntent = PendingIntent.getService(
            this,
            stopRequestId,
            stopIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            getString(R.string.stop_search),
            pendingIntent
        )
            .build()
    }

    private val retryNotificationAction by lazy {
        val pendingIntent = PendingIntent.getService(
            this,
            retryRequestId,
            startIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            getString(R.string.retry),
            pendingIntent
        )
            .build()
    }

    private val stopAndRetryNotificationAction by lazy {
        val pendingIntent = PendingIntent.getService(
            this,
            stopAndRetryRequestId,
            stopAndRetryIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            getString(R.string.cannot_find_pairing),
            pendingIntent
        )
            .build()
    }

    private val replyNotificationAction by lazy {
        val remoteInput = RemoteInput.Builder(remoteInputResultKey).run {
            setLabel(getString(R.string.pairing_code))
            build()
        }

        val pendingIntent = PendingIntent.getForegroundService(
            this,
            replyRequestId,
            replyIntent(this, -1),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        Notification.Action.Builder(
            null,
            getString(R.string.enter_pairing_code_action),
            pendingIntent
        )
            .addRemoteInput(remoteInput)
            .build()
    }

    private fun replyNotificationAction(port: Int): Notification.Action {
        val action = replyNotificationAction

        PendingIntent.getForegroundService(
            this,
            replyRequestId,
            replyIntent(this, port),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        return action
    }

    private val searchingNotification by lazy {
        Notification.Builder(this, notificationChannel)
            .setSmallIcon(R.drawable.ic_stellar)
            .setContentTitle(getString(R.string.searching_pairing_service))
            .addAction(stopNotificationAction)
            .addAction(stopAndRetryNotificationAction)
            .build()
    }

    private fun createInputNotification(port: Int): Notification =
        Notification.Builder(this, notificationChannel)
            .setContentTitle(getString(R.string.pairing_service_found))
            .setSmallIcon(R.drawable.ic_stellar)
            .addAction(replyNotificationAction(port))
            .build()

    private fun createMaxRefreshNotification(): Notification =
        Notification.Builder(this, notificationChannel)
            .setSmallIcon(R.drawable.ic_stellar)
            .setContentTitle(getString(R.string.pairing_service_not_found))
            .setContentText(getString(R.string.ensure_wireless_debugging_open))
            .addAction(retryNotificationAction)
            .build()

    private val workingNotification by lazy {
        Notification.Builder(this, notificationChannel)
            .setContentTitle(getString(R.string.pairing_in_progress))
            .setSmallIcon(R.drawable.ic_stellar)
            .build()
    }

    private fun createManualInputNotification(port: Int): Notification =
        Notification.Builder(this, notificationChannel)
            .setSmallIcon(R.drawable.ic_stellar)
            .setContentTitle(getString(R.string.search_stopped))
            .setContentText(if (port > 0) getString(R.string.enter_pairing_code) else getString(R.string.pairing_service_not_found_retry))
            .addAction(if (port > 0) replyNotificationAction(port) else retryNotificationAction)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null
}