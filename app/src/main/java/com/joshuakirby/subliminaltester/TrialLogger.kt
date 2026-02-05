package com.joshuakirby.subliminaltester

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TrialLogger(context: Context) {
    private val logsDir = File(context.filesDir, "experiment_logs").apply { mkdirs() }
    private val sessionId =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(System.currentTimeMillis()))
    private val logFile = File(logsDir, "session_$sessionId.csv")
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var headerWritten = logFile.exists() && logFile.length() > 0

    fun log(entry: TrialLogEntry) {
        val line = entry.toCsvRow(sessionId)
        ioScope.launch {
            synchronized(logFile) {
                if (!headerWritten) {
                    logFile.appendText(TrialLogEntry.csvHeader())
                    headerWritten = true
                }
                logFile.appendText(line)
            }
        }
    }

    fun close() {
        ioScope.cancel()
    }
}

data class TrialLogEntry(
    val trialIndex: Int,
    val startedAtEpochMs: Long,
    val background: String,
    val flashDisplayType: String,
    val flashBackgroundDuringStimulus: String,
    val flashDurationTargetMs: Int,
    val forwardMaskDurationMs: Int,
    val backwardMaskDurationMs: Int,
    val repetitions: Int,
    val waitDurationsMs: List<Long>,
    val flashDurationsMs: List<Double>,
    val targetMessage: String,
    val choices: List<String>,
    val guess: String,
    val correct: Boolean,
    val responseTimeMs: Long
) {
    fun toCsvRow(sessionId: String): String {
        val columns =
            listOf(
                sessionId,
                trialIndex.toString(),
                startedAtEpochMs.toString(),
                background,
                flashDisplayType,
                flashBackgroundDuringStimulus,
                flashDurationTargetMs.toString(),
                forwardMaskDurationMs.toString(),
                backwardMaskDurationMs.toString(),
                repetitions.toString(),
                waitDurationsMs.joinToString("|"),
                flashDurationsMs.joinToString("|") { "%.2f".format(it) },
                targetMessage,
                choices.joinToString("|"),
                guess,
                correct.toString(),
                responseTimeMs.toString()
            )
        return columns.joinToString(",") { escape(it) } + "\n"
    }

    companion object {
        fun csvHeader(): String {
            return listOf(
                    "session_id",
                    "trial_index",
                    "started_at_epoch_ms",
                    "waiting_background",
                    "flash_display_type",
                    "flash_background",
                    "flash_duration_target_ms",
                    "forward_mask_ms",
                    "backward_mask_ms",
                    "repetitions",
                    "wait_durations_ms",
                    "flash_durations_ms",
                    "target_message",
                    "choices",
                    "guess",
                    "correct",
                    "response_time_ms")
                .joinToString(",") { escape(it) } + "\n"
        }

        private fun escape(value: String): String {
            val sanitized = value.replace("\"", "\"\"")
            return "\"$sanitized\""
        }
    }
}
