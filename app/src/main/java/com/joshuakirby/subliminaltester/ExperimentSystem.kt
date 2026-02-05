package com.joshuakirby.subliminaltester

import com.meta.spatial.core.Entity
import com.meta.spatial.core.SystemBase
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.core.Color4
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.launch
import com.joshuakirby.subliminaltester.R
import java.util.Random

enum class ExperimentPhase {
    MENU,
    WAITING,
    FORWARD_MASKING,
    FLASHING,
    BACKWARD_MASKING,
    PROCESSING,
    GUESSING
}

class ExperimentSystem : SystemBase() {
    companion object {
        const val FLASH_PANEL_WIDTH_METERS = 1.2f
        const val FLASH_PANEL_HEIGHT_METERS = 0.6f
        const val FLASH_MASK_DEPTH_METERS = 1.08f
        const val STIMULUS_DEPTH_METERS = 1.10f
        const val FIXATION_DEPTH_METERS = 1.05f
        const val MIN_FORWARD_MASK_MS = 10
        const val MAX_FORWARD_MASK_MS = 200
        const val MIN_BACKWARD_MASK_MS = 10
        const val MAX_BACKWARD_MASK_MS = 250
        const val DEFAULT_FORWARD_MASK_MS = 50
        const val DEFAULT_BACKWARD_MASK_MS = 150
    }

    @Volatile var currentPhase = ExperimentPhase.MENU
    
    // Config
    @Volatile var flashDurationMs: Int = 100
    @Volatile var repetitions: Int = 1
    @Volatile var currentRepetition: Int = 0
    @Volatile var waitingBackground: String = "Indoor room"
    @Volatile var flashDisplayType: String = "Black void, white letters"
    @Volatile var forwardMaskDurationMs: Int = DEFAULT_FORWARD_MASK_MS
        set(value) {
            field = value.coerceIn(MIN_FORWARD_MASK_MS, MAX_FORWARD_MASK_MS)
        }
    @Volatile var backwardMaskDurationMs: Int = DEFAULT_BACKWARD_MASK_MS
        set(value) {
            field = value.coerceIn(MIN_BACKWARD_MASK_MS, MAX_BACKWARD_MASK_MS)
        }
    @Volatile var processingDelayMs: Int = 2000
    
    // Callbacks
    var onBackgroundUpdate: ((String) -> Unit)? = null

    // Timing
    @Volatile private var frameCounter: Int = 0
    @Volatile private var waitTimeMs: Long = 0
    @Volatile private var phaseStartTime: Long = 0
    @Volatile private var flashStartNano: Long = 0
    
    // Entities
    var panelEntity: Entity? = null
    var messageEntity: Entity? = null
    var maskEntity: Entity? = null
    val maskEntities = mutableListOf<Entity>()
    val maskOffsets = mutableListOf<Vector3>()
    val fixationEntities = mutableListOf<Entity>()
    val fixationOffsets = mutableListOf<Vector3>()
    
    // UI Refs (Views in the main panel)
    var settingsLayout: View? = null
    var testingLayout: View? = null
    var resultLayout: View? = null
    var flashTextView: TextView? = null
    var flashRootView: View? = null
    var trialLogger: TrialLogger? = null
    private var trialCounter: Int = 0
    private var guessPhaseStartTime: Long = 0
    private val waitDurationsMs = mutableListOf<Long>()
    private val flashDurationsActual = mutableListOf<Double>()
    private var currentTargetMessage: String? = null
    private var currentChoices: List<String> = emptyList()
    private var lastFlashBackground: String = ""
    private var trialStartTimestamp: Long = 0
    
    // Calibration
    var refreshRate: Float = 90f // Default, will be updated
    
    override fun execute() {
        val scene = getScene() ?: return
        val viewerPose = scene.getViewerPose()
        val targetRot = viewerPose.q

        // Layered depths to prevent Z-fighting
        val fixationPos = viewerPose.t + (viewerPose.q * Vector3(0f, 0f, FIXATION_DEPTH_METERS))
        val stimulusPos = viewerPose.t + (viewerPose.q * Vector3(0f, 0f, STIMULUS_DEPTH_METERS))

        // Head-lock logic for message
        messageEntity?.let {
            val isVisible = currentPhase == ExperimentPhase.FLASHING
            it.setComponent(Visible(isVisible))
            if (isVisible) {
                it.setComponent(Transform(Pose(stimulusPos, targetRot)))
            }
        }

        val maskingPhase =
            currentPhase == ExperimentPhase.FORWARD_MASKING || currentPhase == ExperimentPhase.BACKWARD_MASKING
        if (maskEntities.isNotEmpty()) {
            val maskBaseLocal = Vector3(
                -FLASH_PANEL_WIDTH_METERS / 2f,
                -FLASH_PANEL_HEIGHT_METERS / 2f,
                FLASH_MASK_DEPTH_METERS
            )
            for (i in maskEntities.indices) {
                val entity = maskEntities[i]
                entity.setComponent(Visible(maskingPhase))
                if (maskingPhase) {
                    val offset = if (i < maskOffsets.size) maskOffsets[i] else Vector3(0f)
                    val localPos = maskBaseLocal + offset
                    val pos = viewerPose.t + (viewerPose.q * localPos)
                    entity.setComponent(Transform(Pose(pos, targetRot)))
                }
            }
        }

        // Fallback to single quad if Mondrian tiles are unavailable
        maskEntity?.let {
            val fallbackVisible = maskEntities.isEmpty() && maskingPhase
            it.setComponent(Visible(fallbackVisible))
            if (fallbackVisible) {
                val pos = viewerPose.t + (viewerPose.q *
                    Vector3(
                        -FLASH_PANEL_WIDTH_METERS / 2f,
                        -FLASH_PANEL_HEIGHT_METERS / 2f,
                        FLASH_MASK_DEPTH_METERS
                    ))
                it.setComponent(Transform(Pose(pos, targetRot)))
            }
        }

        // Head-lock logic for fixation
        for (i in fixationEntities.indices) {
            val entity = fixationEntities[i]
            val isVisible =
                currentPhase == ExperimentPhase.WAITING || currentPhase == ExperimentPhase.PROCESSING
            entity.setComponent(Visible(isVisible))
            if (isVisible) {
                val offset = if (i < fixationOffsets.size) fixationOffsets[i] else Vector3(0f)
                val pos = fixationPos + (viewerPose.q * offset)
                entity.setComponent(Transform(Pose(pos, targetRot)))
            }
        }

        // State machine logic
        when (currentPhase) {
            ExperimentPhase.MENU -> { }
            ExperimentPhase.WAITING -> {
                if (System.currentTimeMillis() - phaseStartTime >= waitTimeMs) {
                    startForwardMasking()
                }
            }
            ExperimentPhase.FORWARD_MASKING -> {
                if (System.currentTimeMillis() - phaseStartTime >= forwardMaskDurationMs) {
                    startFlashing()
                }
            }
            ExperimentPhase.FLASHING -> {
                frameCounter--
                if (frameCounter <= 0) {
                    val actualDurMs = (System.nanoTime() - flashStartNano) / 1_000_000.0
                    flashDurationsActual.add(actualDurMs)
                    Log.d("ExperimentSystem", "FLASH COMPLETED. Actual duration: ${"%.2f".format(actualDurMs)}ms")
                    startBackwardMasking()
                }
            }
            ExperimentPhase.BACKWARD_MASKING -> {
                if (System.currentTimeMillis() - phaseStartTime >= backwardMaskDurationMs) {
                    checkRepetitions()
                }
            }
            ExperimentPhase.PROCESSING -> {
                if (System.currentTimeMillis() - phaseStartTime >= processingDelayMs) {
                    startGuessing()
                }
            }
            ExperimentPhase.GUESSING -> { }
        }
    }
    
    fun startExperiment(duration: Int, reps: Int, bg: String, displayType: String) {
        Log.d("ExperimentSystem", "START EXPERIMENT: dur=$duration, reps=$reps, bg=$bg, display=$displayType")
        flashDurationMs = duration
        repetitions = reps
        waitingBackground = bg
        flashDisplayType = displayType
        refreshFlashVisuals()
        lastFlashBackground = waitingBackground
        currentRepetition = 0
        waitDurationsMs.clear()
        flashDurationsActual.clear()
        trialStartTimestamp = System.currentTimeMillis()
        trialCounter++
        
        val messages = listOf("APPLE", "BANANA", "CHERRY", "DOG", "ELEPHANT", "FLOWER", "GRAPE", "HOUSE", "ISLAND", "JOKER")
        val correctMessage = messages.random()
        val decoys = messages.filter { it != correctMessage }.shuffled().take(2)
        val choices = (decoys + correctMessage).shuffled()
        currentTargetMessage = correctMessage
        currentChoices = choices

        activityScope.launch {
            flashTextView?.text = correctMessage
            
            testingLayout?.findViewById<Button>(R.id.guess_button_1)?.text = choices[0]
            testingLayout?.findViewById<Button>(R.id.guess_button_2)?.text = choices[1]
            testingLayout?.findViewById<Button>(R.id.guess_button_3)?.text = choices[2]
            
            // Store target for verification
            testingLayout?.tag = correctMessage

            panelEntity?.setComponent(Visible(false))
            maskEntity?.setComponent(Visible(false))
            settingsLayout?.visibility = View.GONE
            testingLayout?.visibility = View.GONE
            resultLayout?.visibility = View.GONE
        }
        
        startWaiting()
    }

    fun handleGuess(guess: String) {
        val correctMessage = testingLayout?.tag as? String
        val isCorrect = guess == correctMessage
        logTrialResult(guess, isCorrect)
        showResult(isCorrect)
    }
    
    private fun startWaiting() {
        currentPhase = ExperimentPhase.WAITING
        phaseStartTime = System.currentTimeMillis()
        waitTimeMs = (4000..7000).random().toLong()
        waitDurationsMs.add(waitTimeMs)
        
        activityScope.launch {
            onBackgroundUpdate?.invoke(waitingBackground)
        }
        
        messageEntity?.setComponent(Visible(false))
        maskEntity?.setComponent(Visible(false))
        // Visibility controlled by execute() based on currentPhase
        Log.d("ExperimentSystem", "Waiting for $waitTimeMs ms")
    }
    
    private fun startFlashing() {
        currentPhase = ExperimentPhase.FLASHING
        phaseStartTime = System.currentTimeMillis()
        flashStartNano = System.nanoTime()
        
        applyFlashVisualsForFlash()
        
        // Calculate frames: round(ms * rate / 1000)
        frameCounter = Math.max(1, Math.round(flashDurationMs * refreshRate / 1000f))
        
        messageEntity?.setComponent(Visible(true))
        
        Log.d("ExperimentSystem", "Flashing for $frameCounter frames at $refreshRate Hz (Duration: $flashDurationMs ms)")
    }

    
    private fun startForwardMasking() {
        if (forwardMaskDurationMs <= 0) {
            startFlashing()
            return
        }
        currentPhase = ExperimentPhase.FORWARD_MASKING
        phaseStartTime = System.currentTimeMillis()
        randomizeMaskPattern()
        messageEntity?.setComponent(Visible(false))
        Log.d("ExperimentSystem", "Starting Forward Mask (${forwardMaskDurationMs}ms)")
    }

    private fun startBackwardMasking() {
        activityScope.launch {
            onBackgroundUpdate?.invoke(waitingBackground)
        }

        if (backwardMaskDurationMs <= 0) {
            checkRepetitions()
            return
        }
        currentPhase = ExperimentPhase.BACKWARD_MASKING
        phaseStartTime = System.currentTimeMillis()

        randomizeMaskPattern()
        messageEntity?.setComponent(Visible(false))
        Log.d("ExperimentSystem", "Starting Backward Mask (${backwardMaskDurationMs}ms)")
    }
    
    private fun checkRepetitions() {
        currentRepetition++
        if (currentRepetition < repetitions) {
            startWaiting()
        } else {
            startProcessing()
        }
    }

    private fun startProcessing() {
        currentPhase = ExperimentPhase.PROCESSING
        phaseStartTime = System.currentTimeMillis()
        activityScope.launch {
            onBackgroundUpdate?.invoke(waitingBackground)
        }
        messageEntity?.setComponent(Visible(false))
        maskEntity?.setComponent(Visible(false))
        Log.d("ExperimentSystem", "Processing delay for ${processingDelayMs}ms")
    }
    
    private fun startGuessing() {
        currentPhase = ExperimentPhase.GUESSING
        maskEntity?.setComponent(Visible(false))
        // Visibility controlled by execute() based on currentPhase
        
        activityScope.launch {
            panelEntity?.setComponent(Visible(true))
            testingLayout?.visibility = View.VISIBLE
        }
        guessPhaseStartTime = System.currentTimeMillis()
    }
    
    private fun logTrialResult(guess: String, correct: Boolean) {
        val choicesSnapshot =
            if (currentChoices.isNotEmpty()) {
                currentChoices
            } else {
                listOf(
                    testingLayout?.findViewById<Button>(R.id.guess_button_1)?.text?.toString().orEmpty(),
                    testingLayout?.findViewById<Button>(R.id.guess_button_2)?.text?.toString().orEmpty(),
                    testingLayout?.findViewById<Button>(R.id.guess_button_3)?.text?.toString().orEmpty())
            }
        val entry =
            TrialLogEntry(
                trialIndex = trialCounter,
                startedAtEpochMs = trialStartTimestamp,
                background = waitingBackground,
                flashDisplayType = flashDisplayType,
                flashBackgroundDuringStimulus =
                    if (lastFlashBackground.isNotEmpty()) lastFlashBackground
                    else currentFlashConfig().backgroundLabel,
                flashDurationTargetMs = flashDurationMs,
                forwardMaskDurationMs = forwardMaskDurationMs,
                backwardMaskDurationMs = backwardMaskDurationMs,
                repetitions = repetitions,
                waitDurationsMs = waitDurationsMs.toList(),
                flashDurationsMs = flashDurationsActual.toList(),
                targetMessage = currentTargetMessage.orEmpty(),
                choices = choicesSnapshot,
                guess = guess,
                correct = correct,
                responseTimeMs =
                    if (guessPhaseStartTime > 0) System.currentTimeMillis() - guessPhaseStartTime else 0L)
        trialLogger?.log(entry)
    }
    
    fun showResult(correct: Boolean) {
        currentPhase = ExperimentPhase.MENU
        maskEntity?.setComponent(Visible(false))
        activityScope.launch {
            onBackgroundUpdate?.invoke(waitingBackground)
            panelEntity?.setComponent(Visible(true))
            testingLayout?.visibility = View.GONE
            resultLayout?.visibility = View.VISIBLE
            val resultText = resultLayout?.findViewById<TextView>(R.id.result_text)
            if (correct) {
                resultText?.text = "CORRECT!"
                resultText?.setTextColor(android.graphics.Color.GREEN)
            } else {
                resultText?.text = "INCORRECT"
                resultText?.setTextColor(android.graphics.Color.RED)
            }
        }
    }
    
    fun resetToMenu() {
        currentPhase = ExperimentPhase.MENU
        maskEntity?.setComponent(Visible(false))
        activityScope.launch {
            onBackgroundUpdate?.invoke(waitingBackground)
            panelEntity?.setComponent(Visible(true))
            settingsLayout?.visibility = View.VISIBLE
            testingLayout?.visibility = View.GONE
            resultLayout?.visibility = View.GONE
        }
        messageEntity?.setComponent(Visible(false))
        // Visibility controlled by execute() based on currentPhase
    }

    private val maskColorPalette = arrayOf(
        Color4(1f, 1f, 1f, 1f),
        Color4(0f, 0f, 0f, 1f),
        Color4(0.9f, 0.1f, 0.1f, 1f),
        Color4(0.1f, 0.1f, 0.9f, 1f),
        Color4(0.95f, 0.9f, 0.1f, 1f),
        Color4(0.1f, 0.8f, 0.7f, 1f)
    )
    private val random = Random()
    
    // Helper to run on main thread for UI
    private val activityScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)

    fun refreshFlashVisuals() {
        val config = currentFlashConfig()
        activityScope.launch {
            flashTextView?.setTextColor(config.textColor)
            flashRootView?.setBackgroundColor(config.panelColor)
        }
    }

    private fun applyFlashVisualsForFlash() {
        val config = currentFlashConfig()
        lastFlashBackground = config.backgroundLabel
        activityScope.launch {
            flashTextView?.setTextColor(config.textColor)
            flashRootView?.setBackgroundColor(config.panelColor)
        }
    }

    private fun currentFlashConfig(): FlashVisualConfig =
        FlashVisualConfig.fromDescriptor(flashDisplayType, waitingBackground)

    private fun randomizeMaskPattern() {
        if (maskEntities.isEmpty()) return
        while (maskOffsets.size < maskEntities.size) {
            maskOffsets.add(Vector3(0f))
        }
        for (i in maskEntities.indices) {
            val width = randomRange(
                FLASH_PANEL_WIDTH_METERS * 0.08f,
                FLASH_PANEL_WIDTH_METERS * 0.35f
            )
            val height = randomRange(
                FLASH_PANEL_HEIGHT_METERS * 0.2f,
                FLASH_PANEL_HEIGHT_METERS * 0.45f
            )
            val localX = randomRange(0f, FLASH_PANEL_WIDTH_METERS - width)
            val localY = randomRange(0f, FLASH_PANEL_HEIGHT_METERS - height)
            val offset = Vector3(localX, localY, 0f)
            if (i < maskOffsets.size) {
                maskOffsets[i] = offset
            } else {
                maskOffsets.add(offset)
            }

            val entity = maskEntities[i]
            entity.setComponent(Scale(Vector3(width, height, 1f)))
            entity.getComponent<Material>()?.let { material ->
                material.baseColor = maskColorPalette[random.nextInt(maskColorPalette.size)]
                material.unlit = true
                entity.setComponent(material)
            }
        }
    }

    private fun randomRange(min: Float, max: Float): Float {
        return min + random.nextFloat() * (max - min)
    }
}

data class FlashVisualConfig(
    val backgroundLabel: String,
    val textColor: Int,
    val panelColor: Int
) {
    companion object {
        fun fromDescriptor(descriptor: String, waitingBackground: String): FlashVisualConfig {
            val normalized = descriptor.lowercase()
            val wantsWhiteLetters = normalized.contains("white letters")
            val wantsBlackLetters = normalized.contains("black letters")

            return when {
                normalized.contains("black void") -> FlashVisualConfig(
                    waitingBackground,
                    Color.WHITE,
                    Color.BLACK
                )
                normalized.contains("white void") -> FlashVisualConfig(
                    waitingBackground,
                    Color.BLACK,
                    Color.WHITE
                )
                wantsWhiteLetters -> FlashVisualConfig(
                    waitingBackground,
                    Color.WHITE,
                    Color.TRANSPARENT
                )
                wantsBlackLetters -> FlashVisualConfig(
                    waitingBackground,
                    Color.BLACK,
                    Color.TRANSPARENT
                )
                else -> FlashVisualConfig(
                    waitingBackground,
                    Color.WHITE,
                    Color.TRANSPARENT
                )
            }
        }
    }
}
