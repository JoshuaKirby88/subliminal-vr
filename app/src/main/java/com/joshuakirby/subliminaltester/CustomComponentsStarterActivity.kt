/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.joshuakirby.subliminaltester

import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.meta.spatial.castinputforward.CastInputForwardFeature
import com.meta.spatial.core.Color4
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.datamodelinspector.DataModelInspectorFeature
import com.meta.spatial.debugtools.HotReloadFeature
import com.meta.spatial.okhttp3.OkHttpAssetFetcher
import com.meta.spatial.ovrmetrics.OVRMetricsDataModel
import com.meta.spatial.ovrmetrics.OVRMetricsFeature
import com.meta.spatial.runtime.LayerConfig
import com.meta.spatial.runtime.NetworkedAssetLoader
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.panel.style
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.GLXFInfo
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Quad
import android.view.View
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import com.meta.spatial.toolkit.LayoutXMLPanelRegistration
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.UIPanelSettings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import com.meta.spatial.toolkit.PanelRegistration

import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.vr.VRFeature
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// default activity
class CustomComponentsStarterActivity : AppSystemActivity() {
  private val panelWidthMeters = 2.3f
  private val panelHeightMeters = 1.95f
  private var gltfxEntity: Entity? = null
  private var skyboxEntity: Entity? = null
  private var environmentEntity: Entity? = null
  private var fixationEntities = mutableListOf<Entity>()
  private val distractorEntities = mutableListOf<Entity>()
  private var distractorAnimator: ValueAnimator? = null
  private val distractorRadii = mutableListOf<Float>()
  private val distractorThetas = mutableListOf<Float>()
  private val distractorPhis = mutableListOf<Float>()
  private val distractorOrbitSpeeds = mutableListOf<Float>()
  private val distractorRotationAxes = mutableListOf<Vector3>()
  private val distractorRotationSpeeds = mutableListOf<Float>()
  private var animationStartTime: Long = 0
  private val activityScope = CoroutineScope(Dispatchers.Main)
  private val experimentSystem = ExperimentSystem()
  private lateinit var trialLogger: TrialLogger

  override fun registerFeatures(): List<SpatialFeature> {

    val features = mutableListOf<SpatialFeature>(VRFeature(this))
    if (BuildConfig.DEBUG) {
      features.add(CastInputForwardFeature(this))
      features.add(HotReloadFeature(this))
      features.add(
          OVRMetricsFeature(
              this,
              OVRMetricsDataModel() {
                numberOfMeshes()
                numberOfGrabbables()
              },
              LookAtMetrics {
                pos()
                pitch()
                yaw()
                roll()
              },
          )
      )
      features.add(DataModelInspectorFeature(spatial, this.componentManager))
    }
    return features
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // Request 120Hz for maximum timing precision
    window.attributes.preferredRefreshRate = 120.0f
    
    NetworkedAssetLoader.init(
        File(applicationContext.getCacheDir().canonicalPath),
        OkHttpAssetFetcher(),
    )

    componentManager.registerComponent<LookAt>(LookAt.Companion)
    systemManager.registerSystem(LookAtSystem())
    systemManager.registerSystem(experimentSystem)
    trialLogger = TrialLogger(this)
    experimentSystem.trialLogger = trialLogger
    experimentSystem.onBackgroundUpdate = { label ->
        updateEnvironment(label)
    }

    val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {

        this.display
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
    }
    experimentSystem.refreshRate = display?.refreshRate ?: 90f

    loadGLXF { composition ->
      // set the environment to be unlit
      environmentEntity = composition.getNodeByName("Environment").entity
      val environmentMesh = environmentEntity?.getComponent<Mesh>()
      environmentMesh?.defaultShaderOverride = SceneMaterial.UNLIT_SHADER
      environmentEntity?.setComponent(environmentMesh!!)

      // Link the panel entity from GLXF to our registration
      val panelEntity = composition.getNodeByName("Panel").entity
      panelEntity?.setComponent(Panel(R.id.main_panel))
      panelEntity?.setComponent(Scale(Vector3(panelWidthMeters, panelHeightMeters, 1f)))
      experimentSystem.panelEntity = panelEntity

      updateEnvironment("Indoor room")
    }

  }

  override fun registerPanels(): List<PanelRegistration> {
    return listOf(
        LayoutXMLPanelRegistration(
            R.id.main_panel,
            layoutIdCreator = { R.layout.ui_example },
            settingsCreator = {
              UIPanelSettings(
                  shape = QuadShapeOptions(width = panelWidthMeters, height = panelHeightMeters),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent))
            },
            panelSetupWithRootView = { rootView, _, _ ->
              val backgroundBtn = rootView.findViewById<Button>(R.id.background_selector_btn)
              val displayBtn = rootView.findViewById<Button>(R.id.display_type_selector_btn)
              val durationSlider = rootView.findViewById<SeekBar>(R.id.duration_slider)
              val durationText = rootView.findViewById<TextView>(R.id.duration_value_text)
              val repetitionSlider = rootView.findViewById<SeekBar>(R.id.repetition_slider)
              val repetitionText = rootView.findViewById<TextView>(R.id.repetition_value_text)
              val forwardMaskSlider = rootView.findViewById<SeekBar>(R.id.forward_mask_slider)
              val forwardMaskText = rootView.findViewById<TextView>(R.id.forward_mask_value_text)
              val backwardMaskSlider = rootView.findViewById<SeekBar>(R.id.backward_mask_slider)
              val backwardMaskText = rootView.findViewById<TextView>(R.id.backward_mask_value_text)
              val startBtn = rootView.findViewById<Button>(R.id.start_button)

              val settingsLayout = rootView.findViewById<View>(R.id.settings_layout)
              val testingLayout = rootView.findViewById<View>(R.id.testing_layout)
              val resultLayout = rootView.findViewById<View>(R.id.result_layout)

              experimentSystem.settingsLayout = settingsLayout
              experimentSystem.testingLayout = testingLayout
              experimentSystem.resultLayout = resultLayout

              // Initialize slider values
              durationSlider?.progress = 85 // 100ms
              val initialMs = 100
              val initialFrames = Math.max(1, Math.round(initialMs * experimentSystem.refreshRate / 1000f))
              durationText?.text = "$initialMs ($initialFrames fr)"
              
              repetitionSlider?.progress = 0 // 1 repetition
              repetitionText?.text = "1"

              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                forwardMaskSlider?.min = ExperimentSystem.MIN_FORWARD_MASK_MS
                backwardMaskSlider?.min = ExperimentSystem.MIN_BACKWARD_MASK_MS
              }

              forwardMaskSlider?.progress = experimentSystem.forwardMaskDurationMs
              forwardMaskText?.text = "${experimentSystem.forwardMaskDurationMs} ms"
              backwardMaskSlider?.progress = experimentSystem.backwardMaskDurationMs
              backwardMaskText?.text = "${experimentSystem.backwardMaskDurationMs} ms"

              durationSlider?.setOnSeekBarChangeListener(
                  object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                      val ms = progress + 15
                      val frames = Math.max(1, Math.round(ms * experimentSystem.refreshRate / 1000f))
                      durationText?.text = "$ms ($frames fr)"
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                  })

              repetitionSlider?.setOnSeekBarChangeListener(
                  object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                      repetitionText?.text = (progress + 1).toString()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                  })

              // Custom Cycling Selector Logic
              val backgroundOptions = resources.getStringArray(R.array.background_options)
              var currentBgIndex = 0
              backgroundBtn?.text = backgroundOptions[currentBgIndex]
              experimentSystem.waitingBackground = backgroundOptions[currentBgIndex]
              backgroundBtn?.setOnClickListener {
                currentBgIndex = (currentBgIndex + 1) % backgroundOptions.size
                val selectedOption = backgroundOptions[currentBgIndex]
                backgroundBtn.text = selectedOption
                updateEnvironment(selectedOption)
                experimentSystem.waitingBackground = selectedOption
                experimentSystem.refreshFlashVisuals()
              }

              val displayOptions = resources.getStringArray(R.array.display_type_options)
              var currentDisplayIndex = 0
              displayBtn?.text = displayOptions[currentDisplayIndex]
              experimentSystem.flashDisplayType = displayOptions[currentDisplayIndex]
              experimentSystem.refreshFlashVisuals()
              displayBtn?.setOnClickListener {
                currentDisplayIndex = (currentDisplayIndex + 1) % displayOptions.size
                val selectedOption = displayOptions[currentDisplayIndex]
                displayBtn.text = selectedOption
                experimentSystem.flashDisplayType = selectedOption
                experimentSystem.refreshFlashVisuals()
              }

              forwardMaskSlider?.setOnSeekBarChangeListener(
                  object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                      val clamped =
                          progress.coerceIn(
                              ExperimentSystem.MIN_FORWARD_MASK_MS,
                              ExperimentSystem.MAX_FORWARD_MASK_MS)
                      if (clamped != progress) {
                        seekBar?.progress = clamped
                      }
                      forwardMaskText?.text = "$clamped ms"
                      experimentSystem.forwardMaskDurationMs = clamped
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                  })

              backwardMaskSlider?.setOnSeekBarChangeListener(
                  object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                      val clamped =
                          progress.coerceIn(
                              ExperimentSystem.MIN_BACKWARD_MASK_MS,
                              ExperimentSystem.MAX_BACKWARD_MASK_MS)
                      if (clamped != progress) {
                        seekBar?.progress = clamped
                      }
                      backwardMaskText?.text = "$clamped ms"
                      experimentSystem.backwardMaskDurationMs = clamped
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                  })

              startBtn?.setOnClickListener {
                val duration = durationSlider.progress + 15
                val reps = repetitionSlider.progress + 1
                val bg = backgroundOptions[currentBgIndex]
                val displayType = displayOptions[currentDisplayIndex]
                experimentSystem.startExperiment(duration, reps, bg, displayType)
              }

              rootView.findViewById<Button>(R.id.guess_button_1)?.setOnClickListener { btn ->
                experimentSystem.handleGuess((btn as Button).text.toString())
              }
              rootView.findViewById<Button>(R.id.guess_button_2)?.setOnClickListener { btn ->
                experimentSystem.handleGuess((btn as Button).text.toString())
              }
              rootView.findViewById<Button>(R.id.guess_button_3)?.setOnClickListener { btn ->
                experimentSystem.handleGuess((btn as Button).text.toString())
              }
              rootView.findViewById<Button>(R.id.reset_button)?.setOnClickListener { experimentSystem.resetToMenu() }
            }),
        LayoutXMLPanelRegistration(
            R.id.flash_panel,
            layoutIdCreator = { R.layout.ui_flash },
            settingsCreator = {
              UIPanelSettings(
                  shape = QuadShapeOptions(
                      width = ExperimentSystem.FLASH_PANEL_WIDTH_METERS,
                      height = ExperimentSystem.FLASH_PANEL_HEIGHT_METERS
                  ),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent))
            },
            panelSetupWithRootView = { rootView, _, _ ->
              experimentSystem.flashRootView = rootView
              experimentSystem.flashTextView = rootView.findViewById(R.id.flash_text)
              experimentSystem.refreshFlashVisuals()
            }
        )
    )
  }



  override fun onSceneReady() {
    super.onSceneReady()

    // set the reference space to enable recentering
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)

    scene.setLightingEnvironment(
        ambientColor = Vector3(0f),
        sunColor = Vector3(7.0f, 7.0f, 7.0f),
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
        environmentIntensity = 0.3f,
    )
    scene.updateIBLEnvironment("environment.env")

    scene.setViewOrigin(0.0f, 0.0f, -1.0f, 0.0f)

    skyboxEntity =
        Entity.create(
            listOf(
                Mesh(Uri.parse("mesh://skybox"), hittable = MeshCollision.NoCollision),
                Material().apply {
                  baseTextureAndroidResourceId = R.drawable.skydome
                  unlit = true // Prevent scene lighting from affecting the skybox
                },
                Transform(Pose(Vector3(x = 0f, y = 0f, z = 0f))),
            )
        )
    
    experimentSystem.messageEntity = Entity.create(
        listOf(
            Panel(R.id.flash_panel),
            Transform(Pose(Vector3(0f, 0f, ExperimentSystem.STIMULUS_DEPTH_METERS))),
            Visible(false)
        )
    )

    experimentSystem.maskEntity = Entity.create(
        listOf(
            Mesh(Uri.parse("mesh://quad")),
            Scale(Vector3(ExperimentSystem.FLASH_PANEL_WIDTH_METERS, ExperimentSystem.FLASH_PANEL_HEIGHT_METERS, 1f)),
            Material().apply {
                baseColor = Color4(0.5f, 0.5f, 0.5f, 1.0f) // Neutral Gray mask
                unlit = true
            },
            Transform(
                Pose(
                    Vector3(
                        -ExperimentSystem.FLASH_PANEL_WIDTH_METERS / 2f,
                        -ExperimentSystem.FLASH_PANEL_HEIGHT_METERS / 2f,
                        ExperimentSystem.FLASH_MASK_DEPTH_METERS
                    )
                )
            ), // Offset to center the bottom-left anchored quad
            Visible(false)
        )
    )

    createFixationIfNeeded()
    createMondrianMaskIfNeeded()

    updateEnvironment("Indoor room")
  }

  private fun updateEnvironment(label: String) {
    val env = environmentEntity
    val sky = skyboxEntity

    // Default lighting values
    var ambientColor = Vector3(0f)
    var sunColor = Vector3(7.0f, 7.0f, 7.0f)
    var envIntensity = 0.3f
    var passthrough = false

    when (label) {
      "Indoor room" -> {
        env?.setComponent(Visible(true))
        sky?.setComponent(Visible(false))
      }
      "Black void" -> {
        env?.setComponent(Visible(false))
        sky?.setComponent(Visible(false))
        sunColor = Vector3(0f)
        envIntensity = 0f
      }
      "White void" -> {
        env?.setComponent(Visible(false))
        if (sky != null) {
          sky.setComponent(Visible(true))
          sky.setComponent(
              Material().apply {
                baseColor = Color4(1f, 1f, 1f, 1f)
                unlit = true
              })
        }
        ambientColor = Vector3(1f)
        sunColor = Vector3(0f)
        envIntensity = 0f
      }
      "Landscape" -> {
        env?.setComponent(Visible(false))
        if (sky != null) {
          sky.setComponent(Visible(true))
          sky.setComponent(
              Material().apply {
                baseTextureAndroidResourceId = R.drawable.skydome
                unlit = true
              })
        }
      }
      "Complex" -> {
        env?.setComponent(Visible(false))
        sky?.setComponent(Visible(false))
        sunColor = Vector3(0f)
        envIntensity = 0f
        createDistractorsIfNeeded()
        startDistractorAnimation()
      }
      "Passthrough" -> {
        env?.setComponent(Visible(false))
        sky?.setComponent(Visible(false))
        passthrough = true
      }
    }

    // Apply lighting
    scene.setLightingEnvironment(
        ambientColor = ambientColor,
        sunColor = sunColor,
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
        environmentIntensity = envIntensity,
    )
    scene.enablePassthrough(passthrough)

    // Toggle distractors
    val isComplex = label == "Complex"
    
    if (isComplex) startDistractorAnimation() else stopDistractorAnimation()

    for (distractor in distractorEntities) {
      distractor.setComponent(Visible(isComplex))
    }

    // Fixation cross creation (handled by system for visibility)
    createFixationIfNeeded()
    createMondrianMaskIfNeeded()
  }

  private fun createFixationIfNeeded() {
    if (fixationEntities.isNotEmpty()) return

    // Create a centered head-locked fixation cross
    // mesh://quad is anchored at bottom-left, so we must offset by -half-scale to center it
    val horizontal =
        Entity.create(
            listOf(
                Mesh(Uri.parse("mesh://quad")),
                Scale(Vector3(0.3f, 0.02f, 1f)),
                Material().apply { baseColor = Color4(0f, 1f, 0f, 1f); unlit = true }, // Neon Green
                Transform(Pose(Vector3(0f, 0f, 0f))),
                Visible(false)))

    val vertical =
        Entity.create(
            listOf(
                Mesh(Uri.parse("mesh://quad")),
                Scale(Vector3(0.02f, 0.3f, 1f)),
                Material().apply { baseColor = Color4(0f, 1f, 0f, 1f); unlit = true },
                Transform(Pose(Vector3(0f, 0f, 0.001f))),
                Visible(false)))
    
    fixationEntities.add(horizontal)
    fixationEntities.add(vertical)
    experimentSystem.fixationEntities.clear()
    experimentSystem.fixationEntities.addAll(fixationEntities)
    experimentSystem.fixationOffsets.clear()
    // Offsets to center the bottom-left anchored quads: (-width/2, -height/2, Z)
    experimentSystem.fixationOffsets.add(Vector3(-0.15f, -0.01f, 0f))
    experimentSystem.fixationOffsets.add(Vector3(-0.01f, -0.15f, 0.001f))
  }

  private fun createMondrianMaskIfNeeded() {
    if (experimentSystem.maskEntities.isNotEmpty()) return

    val tileCount = 40
    for (i in 0 until tileCount) {
      val tile =
          Entity.create(
              listOf(
                  Mesh(Uri.parse("mesh://quad")),
                  Scale(Vector3(0.2f, 0.2f, 1f)),
                  Material().apply {
                    baseColor = Color4(1f, 1f, 1f, 1f)
                    unlit = true
                  },
                  Transform(Pose(Vector3(0f, 0f, ExperimentSystem.FLASH_MASK_DEPTH_METERS))),
                  Visible(false)))
      experimentSystem.maskEntities.add(tile)
      experimentSystem.maskOffsets.add(Vector3(0f, 0f, 0f))
    }
  }

  private fun createDistractorsIfNeeded() {
    if (distractorEntities.isNotEmpty()) return

    // Create "Geometric Flux" - a dense, abstract cloud of 200 distractors
    // Using mesh://quad for absolute stability and to avoid procedural mesh crashes
    for (i in 1..200) {
      val radius = 1.5f + (Math.random().toFloat() * 4.5f)
      val theta = Math.random().toFloat() * Math.PI.toFloat()
      val phi = Math.random().toFloat() * 2f * Math.PI.toFloat()
      
      val orbitSpeed = (Math.random().toFloat() - 0.5f) * 1.2f
      val rotationAxis = Vector3(Math.random().toFloat(), Math.random().toFloat(), Math.random().toFloat()).normalize()
      val rotationSpeed = (Math.random().toFloat() * 250f) + 100f

      val size = 0.15f + (Math.random().toFloat() * 0.15f)
      
      val distractor = Entity.create(
          listOf(
              Mesh(Uri.parse("mesh://quad")),
              Scale(Vector3(size, size, 1f)),
              Material().apply {
                baseColor = Color4(Math.random().toFloat(), Math.random().toFloat(), Math.random().toFloat(), 1.0f)
                unlit = true
              },
              Transform(Pose(sphericalToCartesian(radius, theta, phi))),
              Visible(true)))
      
      distractorEntities.add(distractor)
      distractorRadii.add(radius)
      distractorThetas.add(theta)
      distractorPhis.add(phi)
      distractorOrbitSpeeds.add(orbitSpeed)
      distractorRotationAxes.add(rotationAxis)
      distractorRotationSpeeds.add(rotationSpeed)
    }
  }

  private fun sphericalToCartesian(r: Float, theta: Float, phi: Float): Vector3 {
    val x = r * Math.sin(theta.toDouble()).toFloat() * Math.sin(phi.toDouble()).toFloat()
    val y = r * Math.cos(theta.toDouble()).toFloat()
    val z = r * Math.sin(theta.toDouble()).toFloat() * Math.cos(phi.toDouble()).toFloat()
    return Vector3(x, y, z)
  }

  private fun startDistractorAnimation() {
    if (distractorAnimator != null) return
    
    animationStartTime = System.currentTimeMillis()
    distractorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = 1000 // Just to keep the listener pumping
      repeatCount = ValueAnimator.INFINITE
      interpolator = LinearInterpolator()
      addUpdateListener { _ ->
        val elapsedSeconds = (System.currentTimeMillis() - animationStartTime) / 1000f
        for (i in distractorEntities.indices) {
          val distractor = distractorEntities[i]
          
          val r = distractorRadii[i]
          val theta = distractorThetas[i]
          // phi orbits over time
          val phi = distractorPhis[i] + (distractorOrbitSpeeds[i] * elapsedSeconds)
          
          val pos = sphericalToCartesian(r, theta, phi)
          
          val axis = distractorRotationAxes[i]
          val rotSpeed = distractorRotationSpeeds[i]
          // Rotation in degrees
          val currentRotation = rotSpeed * elapsedSeconds
          val rotation = Quaternion(axis.x * currentRotation, axis.y * currentRotation, axis.z * currentRotation)
          
          distractor.setComponent(Transform(Pose(pos, rotation)))
        }
      }
      start()
    }
  }

  private fun stopDistractorAnimation() {
    distractorAnimator?.cancel()
    distractorAnimator = null
  }

  private fun loadGLXF(onLoaded: ((GLXFInfo) -> Unit) = {}): Job {
    gltfxEntity = Entity.create()
    return activityScope.launch {
      glXFManager.inflateGLXF(
          Uri.parse("apk:///scenes/Composition.glxf"),
          rootEntity = gltfxEntity!!,
          onLoaded = onLoaded,
      )
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    if (::trialLogger.isInitialized) {
      trialLogger.close()
    }
  }
}
