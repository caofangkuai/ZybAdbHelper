package roro.stellar.manager.ui.features.terminal

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import roro.stellar.Stellar
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.regex.Pattern

data class ExecutionResult(
    val command: String,
    val output: String,
    val exitCode: Int,
    val executionTimeMs: Long,
    val isError: Boolean = false
)

data class TerminalState(
    val isRunning: Boolean = false,
    val currentOutput: String = "",
    val showResultDialog: Boolean = false,
    val result: ExecutionResult? = null,
    val pendingCommand: String? = null
)

class TerminalViewModel : ViewModel() {

    private val _state = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    private var currentJob: Job? = null
    private var currentProcess: Process? = null
    private var pendingCallback: ((String) -> Unit)? = null
    private var currentDialog: AlertDialog? = null

    private fun getContext(): Context? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
            val currentActivityThread = currentActivityThreadMethod.invoke(null)
            val getSystemContextMethod = activityThreadClass.getMethod("getSystemContext")
            getSystemContextMethod.invoke(currentActivityThread) as? Context
        } catch (e: Exception) {
            null
        }
    }

    private fun getCurrentActivity(): Activity? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
            val currentActivityThread = currentActivityThreadMethod.invoke(null)
            
            val activitiesField = activityThreadClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            val activities = activitiesField.get(currentActivityThread) as HashMap<*, *>
            
            for (activityRecord in activities.values) {
                val activityRecordClass = activityRecord?.javaClass ?: continue
                val pausedField = activityRecordClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                
                if (!pausedField.getBoolean(activityRecord)) {
                    val activityField = activityRecordClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    return activityField.get(activityRecord) as? Activity
                }
            }
            
            activities.values.firstOrNull()?.let {
                val activityField = it.javaClass.getDeclaredField("activity")
                activityField.isAccessible = true
                activityField.get(it) as? Activity
            }
        } catch (e: Exception) {
            null
        }
    }

    fun executeCommand(command: String) {
        if (command.isBlank() || _state.value.isRunning) return
        if (!Stellar.pingBinder()) return
        
        val processedCommand = processCommandTemplate(command)
        if (processedCommand != null) {
            _state.value = _state.value.copy(pendingCommand = processedCommand)
            return
        }
        
        startExecution(processedCommand ?: command)
    }
    
    private fun processCommandTemplate(command: String): String? {
        val pattern = Pattern.compile("\\$\\{CALL:([^}]+)}")
        val matcher = pattern.matcher(command)
        
        if (matcher.find()) {
            val fullMatch = matcher.group()
            val functionName = matcher.group(1)
            
            when {
                functionName.startsWith("selectApp()") -> {
                    pendingCallback = { result ->
                        val finalCommand = command.replace(fullMatch, result)
                        startExecution(finalCommand)
                        pendingCallback = null
                    }
                    showAppListDialog(false)
                    return null
                }
                functionName.startsWith("selectActivity()") -> {
                    pendingCallback = { result ->
                        val finalCommand = command.replace(fullMatch, result)
                        startExecution(finalCommand)
                        pendingCallback = null
                    }
                    showAppListDialog(true)
                    return null
                }
            }
        }
        
        return command
    }
    
    private fun showAppListDialog(needActivity: Boolean) {
        val context = getContext() ?: return
        val apps = getInstalledApps(context)
        val appNames = apps.keys.toTypedArray()
        
        currentDialog = AlertDialog.Builder(context)
            .setTitle("选择应用")
            .setItems(appNames) { _, which ->
                val packageName = apps[appNames[which]]
                if (packageName != null) {
                    if (needActivity) {
                        val mainActivity = getMainActivity(packageName)
                        pendingCallback?.invoke("$packageName/$mainActivity")
                    } else {
                        pendingCallback?.invoke(packageName)
                    }
                } else {
                    pendingCallback?.invoke("")
                }
                currentDialog = null
            }
            .setOnCancelListener {
                if (pendingCallback != null) {
                    pendingCallback?.invoke("")
                    pendingCallback = null
                }
                currentDialog = null
            }
            .create()
        
        currentDialog?.show()
    }
    
    private fun getInstalledApps(context: Context): MutableMap<String, String> {
        val apps = mutableMapOf<String, String>()
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in packages) {
                if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                    val appName = pm.getApplicationLabel(app).toString()
                    apps[appName] = app.packageName
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return apps
    }
    
    private fun getMainActivity(packageName: String): String {
        return try {
            val context = getContext() ?: return ""
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            launchIntent?.component?.className ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    
    private fun startExecution(command: String) {
        currentJob = viewModelScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            _state.value = _state.value.copy(
                isRunning = true,
                currentOutput = "",
                showResultDialog = true,
                pendingCommand = null
            )

            try {
                val process = Stellar.newProcess(
                    arrayOf("sh", "-c", command),
                    null,
                    null
                )
                currentProcess = process

                val outputLines = mutableListOf<String>()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))

                val maxStoredLines = 5000
                val maxDisplayLines = 200
                var updateInterval = 50L
                var lastUpdateTime = System.currentTimeMillis()
                var isHighFrequency = false

                reader.lineSequence().forEach { line ->
                    outputLines.add(line)

                    if (outputLines.size > maxStoredLines) {
                        outputLines.removeAt(0)
                    }

                    if (!isHighFrequency && outputLines.size >= 100) {
                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed < 1000) {
                            isHighFrequency = true
                            updateInterval = 500L
                        }
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime >= updateInterval) {
                        val displayLines = outputLines.takeLast(maxDisplayLines)
                        val displayText = if (outputLines.size > maxDisplayLines) {
                            "... (${outputLines.size} lines total, showing last $maxDisplayLines)\n" + displayLines.joinToString("\n")
                        } else {
                            displayLines.joinToString("\n")
                        }
                        _state.value = _state.value.copy(currentOutput = displayText)
                        lastUpdateTime = now
                    }
                }

                errorReader.lineSequence().forEach { line ->
                    outputLines.add(line)

                    if (outputLines.size > maxStoredLines) {
                        outputLines.removeAt(0)
                    }
                }

                val exitCode = process.waitFor()
                process.destroy()
                currentProcess = null
                val executionTime = System.currentTimeMillis() - startTime

                val finalOutput = if (outputLines.size > maxStoredLines) {
                    "... (${outputLines.size} lines total, showing last $maxStoredLines)\n" + outputLines.takeLast(maxStoredLines).joinToString("\n")
                } else {
                    outputLines.joinToString("\n")
                }

                _state.value = _state.value.copy(
                    isRunning = false,
                    result = ExecutionResult(
                        command = command,
                        output = finalOutput,
                        exitCode = exitCode,
                        executionTimeMs = executionTime,
                        isError = exitCode != 0
                    )
                )
            } catch (e: CancellationException) {
                currentProcess?.destroy()
                currentProcess = null
                val executionTime = System.currentTimeMillis() - startTime
                _state.value = _state.value.copy(
                    isRunning = false,
                    result = ExecutionResult(
                        command = command,
                        output = _state.value.currentOutput + "\n\n[Interrupted]",
                        exitCode = -1,
                        executionTimeMs = executionTime,
                        isError = true
                    )
                )
                throw e
            } catch (e: Exception) {
                currentProcess?.destroy()
                currentProcess = null
                val executionTime = System.currentTimeMillis() - startTime
                _state.value = _state.value.copy(
                    isRunning = false,
                    result = ExecutionResult(
                        command = command,
                        output = "Error: ${e.message}",
                        exitCode = -1,
                        executionTimeMs = executionTime,
                        isError = true
                    )
                )
            }
        }
    }

    fun dismissDialog() {
        currentDialog?.dismiss()
        currentDialog = null
        _state.value = _state.value.copy(showResultDialog = false, result = null, pendingCommand = null)
    }

    fun cancelExecution() {
        currentJob?.cancel()
        currentProcess?.destroy()
        currentProcess = null
        currentDialog?.dismiss()
        currentDialog = null
    }
}