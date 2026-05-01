package roro.stellar.manager.adb

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.lifecycle.Observer
import com.cfks.utils.ScreenCaptureHelper
import com.cfks.utils.ScreenshotListener
import com.cfks.utils.TesseractHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import roro.stellar.manager.R
import roro.stellar.manager.StellarSettings
import java.util.regex.Pattern

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {

        const val notificationChannel = "adb_pairing"
        const val alertNotificationChannel = "adb_pairing_alert"

        private const val tag = "AdbPairingService"

        private const val notificationId = 1
        private const val alertNotificationId = 2
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

    // 截图监听器实例
    private var screenshotListener: ScreenshotListener? = null

    // OCR处理中的标志，避免重复处理同一张截图
    private var isOcrProcessing = false

    // 协程作用域，用于管理后台任务
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 观察者：监听配对服务端口
     * 当发现无线调试配对服务时触发
     */
    private val observer = Observer<Int> { port ->
        Log.i(tag, "配对服务端口: $port")
        if (port <= 0) {
            return@Observer
        }

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
            // 发现配对服务后，启动截图监听以自动获取配对码
            startScreenshotListener()
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
            // 发现配对服务后，启动截图监听以自动获取配对码
            startScreenshotListener()
        }
    }

    private var started = false

    override fun onCreate() {
        super.onCreate()

        val notificationManager = getSystemService(NotificationManager::class.java)

        // 创建普通通知渠道
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

        // 创建提醒通知渠道（带振动）
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

    /**
     * 开始搜索配对服务
     */
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

    /**
     * 停止搜索配对服务
     */
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
     * 启动截图监听器
     * 监听系统截图事件，用于自动获取无线调试配对码
     */
    private fun startScreenshotListener() {
        if (screenshotListener != null) {
            Log.d(tag, "截图监听器已存在，跳过重复启动")
            return
        }

        // Android 12 及以下检查存储权限
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(tag, "缺少存储权限，无法启动截图监听")
                showOcrErrorNotification(getString(R.string.missing_storage_permission))
                showManualInputGuide()
                return
            }
        }

        Log.i(tag, "启动截图监听器，等待无线调试配对码截图...")

        screenshotListener = ScreenshotListener(this, object : ScreenshotListener.Callback {
            override fun onScreenshotTaken(filePath: String) {
                Log.i(tag, "检测到截图文件: $filePath")
                processScreenshotForPairingCode(filePath)
            }
            
            override fun onError(error: String) {
                Log.e(tag, "截图监听错误: $error")
                // 显示引导用户手动输入的通知
                showManualInputGuide()
            }
        })
        screenshotListener?.start()
    }

    /**
     * 停止截图监听器
     */
    private fun stopScreenshotListener() {
        screenshotListener?.stop()
        screenshotListener = null
        Log.d(tag, "截图监听器已停止")
    }

    /**
     * 显示手动输入引导通知
     */
    private fun showManualInputGuide() {
        val notification = Notification.Builder(this, alertNotificationChannel)
            .setSmallIcon(R.drawable.ic_stellar)
            .setContentTitle(getString(R.string.auto_detect_unavailable))
            .setContentText(getString(R.string.please_enter_code_manually))
            .addAction(replyNotificationAction(discoveredPort))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(alertNotificationId, notification)
    }

    /**
     * 处理截图，通过OCR识别配对码
     * @param filePath 截图文件路径
     */
    private fun processScreenshotForPairingCode(filePath: String) {
        if (isOcrProcessing) {
            Log.d(tag, "OCR处理中，跳过本次截图")
            return
        }

        // 显示正在识别状态
        showOcrProcessingNotification()

        serviceScope.launch {
            isOcrProcessing = true

            try {
                val pairCode = withContext(Dispatchers.Default) {
                    performOcrAndExtractCode(filePath)
                }
                
                if (pairCode != null) {
                    withContext(Dispatchers.Main) {
                        onCodeExtracted(pairCode)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showOcrErrorNotification(getString(R.string.ocr_error_no_code))
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "OCR处理截图异常", e)
                withContext(Dispatchers.Main) {
                    showOcrErrorNotification(getString(R.string.ocr_error_exception, e.message ?: "未知错误"))
                }
            } finally {
                isOcrProcessing = false
            }
        }
    }

    /**
     * 执行OCR识别并提取配对码
     * @param filePath 截图文件路径
     * @return 6位配对码，未找到则返回null
     */
    private fun performOcrAndExtractCode(filePath: String): String? {
        // 1. 加载原始截图Bitmap
        val options = android.graphics.BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val fullBitmap = android.graphics.BitmapFactory.decodeFile(filePath, options)

        if (fullBitmap == null) {
            Log.e(tag, "无法加载截图文件: $filePath")
            return null
        }

        // 2. 获取适配当前设备分辨率的裁剪区域
        val captureRect = ScreenCaptureHelper.getAdaptedCaptureRect()
        Log.d(tag, "裁剪区域: ${captureRect.toShortString()}")

        // 3. 根据预设区域裁剪Bitmap
        val croppedBitmap = ScreenCaptureHelper.cropBitmap(fullBitmap, captureRect)
        fullBitmap.recycle() // 释放原始bitmap内存

        if (croppedBitmap == null) {
            Log.e(tag, "截图裁剪失败")
            return null
        }

        // 4. 使用Tesseract OCR识别裁剪后的图片中的文字
        val recognizedText = TesseractHelper.recognizeText(this@AdbPairingService, croppedBitmap)
        croppedBitmap.recycle() // 释放裁剪后bitmap内存

        if (recognizedText.isNullOrEmpty()) {
            Log.w(tag, "OCR识别结果为空")
            return null
        }

        Log.d(tag, "OCR识别原始结果: $recognizedText")

        // 5. 从识别结果中提取6位数配对码
        return extractPairCode(recognizedText)
    }

    /**
     * 从OCR识别结果文本中提取6位数字配对码
     * @param text OCR识别出的原始文本
     * @return 6位数字配对码，未找到则返回null
     */
    private fun extractPairCode(text: String): String? {
        // 匹配独立的6位数字（有单词边界）
        val pattern = Pattern.compile("\\b(\\d{6})\\b")
        val matcher = pattern.matcher(text)
        if (matcher.find()) {
            return matcher.group(1)
        }
        // 兼容：匹配连续出现的6位数字（没有单词边界，如空格分隔）
        val pattern2 = Pattern.compile("(\\d{6})")
        val matcher2 = pattern2.matcher(text.replace(" ", ""))
        if (matcher2.find()) {
            return matcher2.group(1)
        }
        return null
    }

    /**
     * OCR识别成功后的处理
     * @param pairCode 识别到的配对码
     */
    private fun onCodeExtracted(pairCode: String) {
        Log.i(tag, "OCR成功识别到配对码: $pairCode")
        showOcrSuccessNotification(pairCode)
        
        if (discoveredPort > 0) {
            performPairingWithCode(pairCode, discoveredPort)
        } else {
            Log.w(tag, "未发现配对服务端口，等待1秒后重试")
            // 等待端口发现后再重试
            retryHandler.postDelayed({
                if (discoveredPort > 0) {
                    performPairingWithCode(pairCode, discoveredPort)
                } else {
                    Log.e(tag, "仍未发现配对服务端口")
                    showOcrErrorNotification(getString(R.string.pairing_service_not_found))
                }
            }, 1000)
        }
    }

    /**
     * 使用识别到的配对码执行无线调试配对
     * @param code 6位配对码
     * @param port 配对服务端口
     */
    private fun performPairingWithCode(code: String, port: Int) {
        if (port <= 0) {
            Log.e(tag, "无效的端口号: $port")
            showOcrErrorNotification(getString(R.string.ocr_error_invalid_port))
            return
        }

        Log.i(tag, "执行配对: code=$code, port=$port")
        // 调用现有的配对方法
        onInput(code, port)
    }

    /**
     * 显示OCR识别进行中的通知
     */
    private fun showOcrProcessingNotification() {
        val notification = Notification.Builder(this, alertNotificationChannel)
            .setSmallIcon(R.drawable.ic_stellar)
            .setContentTitle(getString(R.string.ocr_processing_title))
            .setContentText(getString(R.string.ocr_processing_text))
            .setAutoCancel(true)
            .build()

        try {
            getSystemService(NotificationManager::class.java).notify(alertNotificationId, notification)
        } catch (e: Exception) {
            Log.e(tag, "显示OCR处理通知失败", e)
        }
    }

    /**
     * 显示OCR识别成功并获取到配对码的通知
     * @param pairCode 识别到的配对码
     */
    private fun showOcrSuccessNotification(pairCode: String) {
        val notification = Notification.Builder(this, alertNotificationChannel)
            .setSmallIcon(R.drawable.ic_stellar)
            .setContentTitle(getString(R.string.ocr_success_title))
            .setContentText(getString(R.string.ocr_success_text, pairCode))
            .setAutoCancel(true)
            .build()

        try {
            getSystemService(NotificationManager::class.java).notify(alertNotificationId, notification)
        } catch (e: Exception) {
            Log.e(tag, "显示OCR成功通知失败", e)
        }
    }

    /**
     * 显示OCR识别失败的通知
     * @param message 失败原因描述
     */
    private fun showOcrErrorNotification(message: String) {
        val notification = Notification.Builder(this, alertNotificationChannel)
            .setSmallIcon(R.drawable.ic_stellar)
            .setContentTitle(getString(R.string.ocr_error_title))
            .setContentText(message)
            .setAutoCancel(true)
            .build()

        try {
            getSystemService(NotificationManager::class.java).notify(alertNotificationId, notification)
        } catch (e: Exception) {
            Log.e(tag, "显示OCR错误通知失败", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        retryHandler.removeCallbacksAndMessages(null)
        // 取消所有协程
        serviceScope.cancel()
        // 停止截图监听，释放资源
        stopScreenshotListener()
        stopSearch()
        adbMdns?.destroy()
        adbMdns = null
        connectMdns?.destroy()
        connectMdns = null
    }

    /**
     * 启动服务
     */
    private fun onStart(): Notification {
        if (isRunning && started) {
            Log.i(tag, "服务已在运行，忽略重复启动")
            return searchingNotification
        }
        isRunning = true
        startSearch()
        return searchingNotification
    }

    /**
     * 停止搜索
     */
    private fun onStopSearch(): Notification {
        stopSearch()
        // 停止截图监听
        stopScreenshotListener()
        return createManualInputNotification(discoveredPort)
    }

    /**
     * 停止并重试
     */
    private fun onStopAndRetry(): Notification {
        stopSearch()
        // 停止并重新创建截图监听器
        stopScreenshotListener()
        adbMdns?.destroy()
        adbMdns = null
        return onStart()
    }

    /**
     * 搜索次数达到上限时的处理
     */
    private fun onSearchMaxRefresh() {
        Log.i(tag, "搜索次数已达上限")
        stopSearch()
        // 停止截图监听
        stopScreenshotListener()
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

    private fun onInput(code: String, port: Int): Notification {
        if (port == -1) {
            return createManualInputNotification(-1)
        }

        serviceScope.launch(Dispatchers.IO) {
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

    /**
     * 处理配对结果
     */
    private fun handleResult(success: Boolean, exception: Throwable?) {
        retryHandler.post {
            if (success) {
                Log.i(tag, "配对成功，开始搜索连接服务")
                stopSearch()
                // 配对成功后停止截图监听
                stopScreenshotListener()

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
                val text = getString(R.string.please_wait_auto_return)

                Log.i(tag, "配对失败，正在重试")

                val failureNotification = Notification.Builder(this, notificationChannel)
                    .setSmallIcon(R.drawable.ic_stellar)
                    .setContentTitle(title)
                    .setContentText(text)
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

    /**
     * 搜索连接服务
     */
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

    /**
     * 找到连接服务后的处理
     */
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

    private fun grantSecureSettingsPermission(port: Int) {
        serviceScope.launch(Dispatchers.IO) {
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

    /**
     * 跳转到启动器界面
     */
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

    /**
     * 连接服务搜索次数达到上限的处理
     */
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