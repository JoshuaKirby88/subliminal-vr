package com.joshuakirby.subliminaltester

import com.meta.spatial.core.Entity
import com.meta.spatial.core.SystemBase
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Transform
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.launch
import com.joshuakirby.subliminaltester.R

enum class ExperimentPhase {
    MENU,
    WAITING,
    FLASHING,
    MASKING,
    GUESSING
}

class ExperimentSystem : SystemBase() {
    @Volatile var currentPhase = ExperimentPhase.MENU
    
    // Config
    @Volatile var flashDurationMs: Int = 100
    @Volatile var repetitions: Int = 1
    @Volatile var currentRepetition: Int = 0
    
    // Timing
    @Volatile private var frameCounter: Int = 0
    @Volatile private var waitTimeMs: Long = 0
    @Volatile private var phaseStartTime: Long = 0
    
    // Entities
    var panelEntity: Entity? = null
    var messageEntity: Entity? = null
    val maskEntities = mutableListOf<Entity>()
    val maskOffsets = mutableListOf<Vector3>()
    val fixationEntities = mutableListOf<Entity>()
    val fixationOffsets = mutableListOf<Vector3>()
    
    // UI Refs (Views in the main panel)
    var settingsLayout: View? = null
    var testingLayout: View? = null
    var resultLayout: View? = null
    var flashTextView: TextView? = null
    
    // Calibration
    var refreshRate: Float = 90f // Default, will be updated
    
    override fun execute() {
        val scene = getScene() ?: return
        val viewerPose = scene.getViewerPose()
        val targetRot = viewerPose.q

        // Layered depths to prevent Z-fighting
        // Note: +Z is forward in this project's coordinate system (see HEAD_LOCK_DEV_LOG.md)
        val fixationPos = viewerPose.t + (viewerPose.q * Vector3(0f, 0f, 1.05f))
        val maskPosBase = viewerPose.t + (viewerPose.q * Vector3(0f, 0f, 1.00f))
        val stimulusPos = viewerPose.t + (viewerPose.q * Vector3(0f, 0f, 1.10f))


        // Head-lock logic for message
        messageEntity?.let {
            val isVisible = currentPhase == ExperimentPhase.FLASHING
            it.setComponent(Visible(isVisible))
            if (isVisible) {
                it.setComponent(Transform(Pose(stimulusPos, targetRot)))
            }
        }
        
        // Head-lock logic for masks
        for (i in maskEntities.indices) {
            val entity = maskEntities[i]
            // Show all masks during MASKING phase, or first 5 masks during WAITING for visual reference
            val isVisible = currentPhase == ExperimentPhase.MASKING || (currentPhase == ExperimentPhase.WAITING && i < 5)
            entity.setComponent(Visible(isVisible))
            if (isVisible) {
                val offset = maskOffsets[i]
                val pos = maskPosBase + (viewerPose.q * Vector3(offset.x, offset.y, 0f))
                entity.setComponent(Transform(Pose(pos, targetRot)))
            }
        }

        // Head-lock logic for fixation
        for (i in fixationEntities.indices) {
            val entity = fixationEntities[i]
            val isVisible = currentPhase == ExperimentPhase.WAITING
            entity.setComponent(Visible(isVisible))
            if (isVisible) {
                entity.setComponent(Transform(Pose(fixationPos, targetRot)))
            }
        }

        // State machine logic
        when (currentPhase) {
            ExperimentPhase.MENU -> { }
            ExperimentPhase.WAITING -> {
                if (System.currentTimeMillis() - phaseStartTime >= waitTimeMs) {
                    startFlashing()
                }
            }
            ExperimentPhase.FLASHING -> {
                frameCounter--
                if (frameCounter <= 0) {
                    startMasking()
                }
            }
            ExperimentPhase.MASKING -> {
                if (System.currentTimeMillis() - phaseStartTime >= 150) { // 150ms backward mask
                    checkRepetitions()
                }
            }
            ExperimentPhase.GUESSING -> { }
        }
    }
    
    fun startExperiment(duration: Int, reps: Int, message: String) {
        Log.d("ExperimentSystem", "START EXPERIMENT: dur=$duration, reps=$reps, refresh=$refreshRate")
        flashDurationMs = duration
        repetitions = reps
        currentRepetition = 0
        
        val messages = listOf("APPLE", "BANANA", "CHERRY", "DOG", "ELEPHANT", "FLOWER", "GRAPE", "HOUSE", "ISLAND", "JOKER")
        val correctMessage = messages.random()
        val decoys = messages.filter { it != correctMessage }.shuffled().take(2)
        val choices = (decoys + correctMessage).shuffled()

        activityScope.launch {
            flashTextView?.text = correctMessage
            
            testingLayout?.findViewById<Button>(R.id.guess_button_1)?.text = choices[0]
            testingLayout?.findViewById<Button>(R.id.guess_button_2)?.text = choices[1]
            testingLayout?.findViewById<Button>(R.id.guess_button_3)?.text = choices[2]
            
            // Store target for verification
            testingLayout?.tag = correctMessage

            panelEntity?.setComponent(Visible(false))
            settingsLayout?.visibility = View.GONE
            testingLayout?.visibility = View.GONE
            resultLayout?.visibility = View.GONE
        }
        
        startWaiting()
    }

    fun handleGuess(guess: String) {
        val correctMessage = testingLayout?.tag as? String
        showResult(guess == correctMessage)
    }
    
    private fun startWaiting() {
        currentPhase = ExperimentPhase.WAITING
        phaseStartTime = System.currentTimeMillis()
        waitTimeMs = (4000..7000).random().toLong()
        
        messageEntity?.setComponent(Visible(false))
        // Visibility controlled by execute() based on currentPhase
        Log.d("ExperimentSystem", "Waiting for $waitTimeMs ms")
    }
    
    private fun startFlashing() {
        currentPhase = ExperimentPhase.FLASHING
        phaseStartTime = System.currentTimeMillis()
        
        // Calculate frames: round(ms * rate / 1000)
        frameCounter = Math.max(1, Math.round(flashDurationMs * refreshRate / 1000f))
        
        messageEntity?.setComponent(Visible(true))
        
        Log.d("ExperimentSystem", "Flashing for $frameCounter frames at $refreshRate Hz (Duration: $flashDurationMs ms)")
    }

    
    private fun startMasking() {
        currentPhase = ExperimentPhase.MASKING
        phaseStartTime = System.currentTimeMillis()
        
        messageEntity?.setComponent(Visible(false))
        // Visibility controlled by execute() based on currentPhase
    }
    
    private fun checkRepetitions() {
        currentRepetition++
        if (currentRepetition < repetitions) {
            startWaiting()
        } else {
            startGuessing()
        }
    }
    
    private fun startGuessing() {
        currentPhase = ExperimentPhase.GUESSING
        // Visibility controlled by execute() based on currentPhase
        
        activityScope.launch {
            panelEntity?.setComponent(Visible(true))
            testingLayout?.visibility = View.VISIBLE
        }
    }
    
    fun showResult(correct: Boolean) {
        currentPhase = ExperimentPhase.MENU
        activityScope.launch {
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
        activityScope.launch {
            panelEntity?.setComponent(Visible(true))
            settingsLayout?.visibility = View.VISIBLE
            testingLayout?.visibility = View.GONE
            resultLayout?.visibility = View.GONE
        }
        messageEntity?.setComponent(Visible(false))
        // Visibility controlled by execute() based on currentPhase
    }

    
    // Helper to run on main thread for UI
    private val activityScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
}
