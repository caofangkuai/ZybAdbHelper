package roro.stellar.manager.ui.features.terminal

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import roro.stellar.Stellar
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
    val pendingCommand: String? = null,
    val pendingSelection: PendingSelection? = null
)

enum class PendingSelection {
    APP, ACTIVITY
}

class TerminalViewModel(
    private val applicationContext: Context
) : ViewModel() {

    private val _state = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    private var currentJob: Job? = null
    private var currentProcess: Process? = null

    fun executeCommand(command: String) {
        if (command.isBlank() || _state.value.isRunning) return

        val processedCommand = processCommandTemplate(command)
        if (processedCommand == null) return

        startExecution(processedCommand)
    }

    private fun processCommandTemplate(command: String): String? {
        val pattern = Pattern.compile("\\$\\{CALL:([^}]+)}")
        val matcher = pattern.matcher(command)

        if (matcher.find()) {
            val fullMatch = matcher.group()
            val functionName = matcher.group(1)

            when {
                functionName.startsWith("selectApp()") -> {
                    _state.update {
                        it.copy(
                            pendingCommand = command,
                            pendingSelection = PendingSelection.APP
                        )
                    }
                    return null
                }
                functionName.startsWith("selectActivity()") -> {
                    _state.update {
                        it.copy(
                            pendingCommand = command,
                            pendingSelection = PendingSelection.ACTIVITY
                        )
                    }
                    return null
                }
            }
        }

        return command
    }

    fun onAppSelected(packageName: String, needActivity: Boolean = false) {
        val pendingCommand = _state.value.pendingCommand
        if (pendingCommand == null) return

        val fullPath = if (needActivity) {
            val mainActivity = getMainActivity(packageName)
            if (mainActivity.isNotEmpty()) {
                "$packageName/$mainActivity"
            } else {
                packageName
            }
        } else {
            packageName
        }

        val pattern = Pattern.compile("\\$\\{CALL:[^}]+}")
        val finalCommand = pattern.matcher(pendingCommand).replaceFirst(fullPath)

        _state.update { it.copy(pendingCommand = null, pendingSelection = null) }
        startExecution(finalCommand)
    }

    private fun getMainActivity(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            launchIntent?.component?.className ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun getInstalledApps(): Map<String, String> {
        val apps = mutableMapOf<String, String>()
        try {
            val pm = applicationContext.packageManager
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
        return apps.toSortedMap()
    }

    private fun startExecution(command: String) {
        currentJob?.cancel()
        currentJob = viewModelScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()

            _state.update {
                it.copy(
                    isRunning = true,
                    currentOutput = "",
                    showResultDialog = true,
                    result = null
                )
            }

            val outputLines = mutableListOf<String>()
            val maxStoredLines = 5000

            try {
                val process = Stellar.newProcess(
                    arrayOf("sh", "-c", command),
                    null,
                    null
                )
                currentProcess = process

                val readingJob = launch {
                    process.inputStream.bufferedReader().use { reader ->
                        reader.forEachLine { line ->
                            outputLines.add(line)
                            if (outputLines.size > maxStoredLines) {
                                outputLines.removeAt(0)
                            }
                            _state.update { it.copy(currentOutput = formatOutput(outputLines)) }
                        }
                    }
                }

                val errorReadingJob = launch {
                    process.errorStream.bufferedReader().use { reader ->
                        reader.forEachLine { line ->
                            outputLines.add("[STDERR] $line")
                            if (outputLines.size > maxStoredLines) {
                                outputLines.removeAt(0)
                            }
                            _state.update { it.copy(currentOutput = formatOutput(outputLines)) }
                        }
                    }
                }

                val exitCode = process.waitFor()
                readingJob.cancel()
                errorReadingJob.cancel()

                val executionTime = System.currentTimeMillis() - startTime
                val finalOutput = formatOutput(outputLines, maxStoredLines)

                _state.update {
                    it.copy(
                        isRunning = false,
                        result = ExecutionResult(
                            command = command,
                            output = finalOutput,
                            exitCode = exitCode,
                            executionTimeMs = executionTime,
                            isError = exitCode != 0
                        )
                    )
                }
            } catch (e: CancellationException) {
                currentProcess?.destroyForcibly()
                _state.update {
                    it.copy(
                        isRunning = false,
                        result = ExecutionResult(
                            command = command,
                            output = _state.value.currentOutput + "\n\n[Interrupted]",
                            exitCode = -1,
                            executionTimeMs = System.currentTimeMillis() - startTime,
                            isError = true
                        )
                    )
                }
            } catch (e: Exception) {
                currentProcess?.destroyForcibly()
                _state.update {
                    it.copy(
                        isRunning = false,
                        result = ExecutionResult(
                            command = command,
                            output = "Error: ${e.message}",
                            exitCode = -1,
                            executionTimeMs = System.currentTimeMillis() - startTime,
                            isError = true
                        )
                    )
                }
            } finally {
                currentProcess = null
            }
        }
    }

    private fun formatOutput(lines: List<String>, maxLines: Int = 200): String {
        return if (lines.size > maxLines) {
            "... (${lines.size} lines total, showing last $maxLines)\n" +
                    lines.takeLast(maxLines).joinToString("\n")
        } else {
            lines.joinToString("\n")
        }
    }

    fun dismissDialog() {
        _state.update { it.copy(showResultDialog = false, result = null) }
    }

    fun cancelExecution() {
        currentJob?.cancel()
        currentProcess?.destroyForcibly()
        currentProcess = null
    }

    fun clearPendingSelection() {
        _state.update { it.copy(pendingCommand = null, pendingSelection = null) }
    }
}