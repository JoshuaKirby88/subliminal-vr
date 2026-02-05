/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.customcomponentsstarter

import android.net.Uri
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
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.core.Vector2
import android.view.View
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
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
  private val headLockSystem = HeadLockSystem()

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
    NetworkedAssetLoader.init(
        File(applicationContext.getCacheDir().canonicalPath),
        OkHttpAssetFetcher(),
    )

    componentManager.registerComponent<LookAt>(LookAt.Companion)
    systemManager.registerSystem(LookAtSystem())
    systemManager.registerSystem(experimentSystem)
    systemManager.registerSystem(headLockSystem)

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
                  shape = QuadShapeOptions(width = 2.0f, height = 1.5f),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent))
            },
            panelSetupWithRootView = { rootView, _, _ ->
              val backgroundBtn = rootView.findViewById<Button>(R.id.background_selector_btn)
              val displayBtn = rootView.findViewById<Button>(R.id.display_type_selector_btn)
              val durationSlider = rootView.findViewById<SeekBar>(R.id.duration_slider)
              val durationText = rootView.findViewById<TextView>(R.id.duration_value_text)
              val repetitionSlider = rootView.findViewById<SeekBar>(R.id.repetition_slider)
              val repetitionText = rootView.findViewById<TextView>(R.id.repetition_value_text)
              val startBtn = rootView.findViewById<Button>(R.id.start_button)

              val settingsLayout = rootView.findViewById<View>(R.id.settings_layout)
              val testingLayout = rootView.findViewById<View>(R.id.testing_layout)
              val resultLayout = rootView.findViewById<View>(R.id.result_layout)

              experimentSystem.settingsLayout = settingsLayout
              experimentSystem.testingLayout = testingLayout
              experimentSystem.resultLayout = resultLayout

              // Initialize slider values
              durationSlider?.progress = 85 // 100ms
              durationText?.text = "100"
              repetitionSlider?.progress = 0 // 1 repetition
              repetitionText?.text = "1"

              durationSlider?.setOnSeekBarChangeListener(
                  object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                      durationText?.text = (progress + 15).toString()
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
              backgroundBtn?.setOnClickListener {
                currentBgIndex = (currentBgIndex + 1) % backgroundOptions.size
                val selectedOption = backgroundOptions[currentBgIndex]
                backgroundBtn.text = selectedOption
                updateEnvironment(selectedOption)
              }

              val displayOptions = resources.getStringArray(R.array.display_type_options)
              var currentDisplayIndex = 0
              displayBtn?.setOnClickListener {
                currentDisplayIndex = (currentDisplayIndex + 1) % displayOptions.size
                displayBtn.text = displayOptions[currentDisplayIndex]
              }

              startBtn?.setOnClickListener {
                val duration = durationSlider.progress + 15
                val reps = repetitionSlider.progress + 1
                experimentSystem.startExperiment(duration, reps, "")
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
                  shape = QuadShapeOptions(width = 2.0f, height = 1.0f),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent))
            },
            panelSetupWithRootView = { rootView, _, _ ->
              experimentSystem.flashTextView = rootView.findViewById(R.id.flash_text)
            }
        ),
        LayoutXMLPanelRegistration(
            R.id.test_panel,
            layoutIdCreator = { R.layout.ui_test_panel },
            settingsCreator = {
              UIPanelSettings(
                  shape = QuadShapeOptions(width = 1.0f, height = 0.6f),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent)
              )
            },
            panelSetupWithRootView = { _, _, _ ->
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
            Transform(Pose(Vector3(0f, 0f, -0.8f))),
            Visible(true)
        )
    )

    createFixationIfNeeded()

    headLockSystem.panelEntity = Entity.create(
        listOf(
            Panel(R.id.test_panel),
            Transform(Pose(Vector3(0f, 1.5f, -1.0f))),
            Visible(true)
        )
    )
    createMaskEntities()
    
    // Ensure lists are synchronized
    experimentSystem.fixationEntities.clear()
    experimentSystem.fixationEntities.addAll(fixationEntities)
    
    updateEnvironment("Indoor room")
  }

  private fun createMaskEntities() {
    if (experimentSystem.maskEntities.isNotEmpty()) return

    // Create a "Mondrian Mask" of colorful quads - enlarged for debug visibility
    // Using mesh://quad + Quad() + Scale for absolute stability
    for (i in 1..40) {
      val x = (Math.random().toFloat() * 3f) - 1.5f
      val y = (Math.random().toFloat() * 2f) - 1f
      val z = -1.45f 
      val offset = Vector3(x, y, z)
      
      val width = 0.3f + Math.random().toFloat() * 0.6f
      val height = 0.3f + Math.random().toFloat() * 0.6f

      val mask =
          Entity.create(
            listOf(
                Mesh(Uri.parse("mesh://quad")),
                Scale(Vector3(width, height, 1f)),
                  Material().apply {
                    baseColor = Color4(Math.random().toFloat(), Math.random().toFloat(), Math.random().toFloat(), 1.0f)
                    unlit = true
                  },
                  Transform(Pose(offset)),
                  Visible(false)))
      experimentSystem.maskEntities.add(mask)
      experimentSystem.maskOffsets.add(offset)
    }
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

    // Toggle distractors and fixation
    val isComplex = label == "Complex"
    val isExperimentActive = experimentSystem.currentPhase != ExperimentPhase.MENU
    
    if (isComplex) startDistractorAnimation() else stopDistractorAnimation()

    for (distractor in distractorEntities) {
      distractor.setComponent(Visible(isComplex))
    }

    // Fixation cross visibility (visible in all but Indoor room, UNLESS experiment is active)
    val needsFixation = label != "Indoor room" || isExperimentActive
    if (needsFixation) createFixationIfNeeded()
    for (fixation in fixationEntities) {
      fixation.setComponent(Visible(needsFixation))
    }
  }

  private fun createFixationIfNeeded() {
    if (fixationEntities.isNotEmpty()) return

    // Create a simple head-locked fixation cross - using mesh://quad + Quad() for maximum stability
    val horizontal =
        Entity.create(
            listOf(
                Mesh(Uri.parse("mesh://quad")),
                Scale(Vector3(0.3f, 0.02f, 1f)),
                Material().apply { baseColor = Color4(0f, 1f, 0f, 1f); unlit = true }, // Neon Green
                Transform(Pose(Vector3(0f, 0f, -1f))),
                Visible(true)))

    val vertical =
        Entity.create(
            listOf(
                Mesh(Uri.parse("mesh://quad")),
                Scale(Vector3(0.02f, 0.3f, 1f)),
                Material().apply { baseColor = Color4(0f, 1f, 0f, 1f); unlit = true },
                Transform(Pose(Vector3(0f, 0f, -1f))),
                Visible(true)))
    
    fixationEntities.add(horizontal)
    fixationEntities.add(vertical)
    experimentSystem.fixationOffsets.clear()
    experimentSystem.fixationOffsets.add(Vector3(0f, 0f, -1f))
    experimentSystem.fixationOffsets.add(Vector3(0f, 0f, -1f))
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
}
