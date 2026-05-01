package roro.stellar.manager

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import roro.stellar.Stellar
import roro.stellar.manager.compat.BuildUtils.atLeast30
import roro.stellar.manager.db.AppDatabase
import roro.stellar.manager.startup.notification.BootStartNotifications
import roro.stellar.manager.util.Logger.Companion.LOGGER
import android.content.res.Configuration
import android.util.DisplayMetrics
import com.cfks.utils.ScreenCaptureHelper
import com.cfks.utils.TesseractHelper

lateinit var application: StellarApplication

class StellarApplication : Application() {

    @RequiresApi(Build.VERSION_CODES.P)
    companion object {

        init {
            LOGGER.d("init")

            @Suppress("DEPRECATION")
            Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR))

            HiddenApiBypass.setHiddenApiExemptions("")

            if (atLeast30) {
                System.loadLibrary("adb")
            }
        }
    }

    private fun init(context: Context) {
        StellarSettings.initialize(context)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }

override fun onCreate() {
    super.onCreate()
    application = this
    
    init(this)
    BootStartNotifications.createChannel(this)
    Stellar.addServiceStartedListener(Stellar.OnServiceStartedListener { executeFollowCommands() })
    
    // 获取屏幕分辨率并设置到裁剪工具类
    val dm = resources.displayMetrics
    val screenWidth = dm.widthPixels
    val screenHeight = dm.heightPixels
    ScreenCaptureHelper.setScreenSize(screenWidth, screenHeight)
    
    // 初始化 Tesseract
    Thread {
        TesseractHelper.init(this)
    }.start()
}

    private fun executeFollowCommands() {
        val context = this
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val commands = AppDatabase.get(context).commandDao().getAll()
                    .filter { it.mode == "FOLLOW_SERVICE" }
                commands.forEach { cmd ->
                    try {
                        LOGGER.d("执行跟随服务命令: title=${cmd.title}, command=${cmd.command}")
                        val process = Stellar.newProcess(arrayOf("sh", "-c", cmd.command), null, null)
                        val stdout = process.inputStream.bufferedReader().readText()
                        val stderr = process.errorStream.bufferedReader().readText()
                        val exitCode = process.waitFor()
                        if (exitCode != 0) {
                            LOGGER.w("命令执行失败: title=${cmd.title}, 退出码=$exitCode, stdout=$stdout, stderr=$stderr")
                        } else {
                            LOGGER.d("命令执行完成: ${cmd.title}, 退出码=$exitCode")
                        }
                    } catch (e: Exception) {
                        LOGGER.e("命令执行失败: ${cmd.title}", e)
                    }
                }
            } catch (e: Exception) {
                LOGGER.e("读取跟随服务命令失败", e)
            }
        }
    }
}
