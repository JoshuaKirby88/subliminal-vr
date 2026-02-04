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
import android.view.View
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
  private val activityScope = CoroutineScope(Dispatchers.Main)

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

    // TODO: register the LookAt system and component

    loadGLXF { composition ->
      // set the environment to be unlit
      environmentEntity = composition.getNodeByName("Environment").entity
      val environmentMesh = environmentEntity?.getComponent<Mesh>()
      environmentMesh?.defaultShaderOverride = SceneMaterial.UNLIT_SHADER
      environmentEntity?.setComponent(environmentMesh!!)

      // Link the panel entity from GLXF to our registration
      val panelEntity = composition.getNodeByName("Panel").entity
      panelEntity?.setComponent(Panel(R.id.main_panel))

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
            }))
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
    
    updateEnvironment("Indoor room")
  }

  private fun updateEnvironment(label: String) {
    val env = environmentEntity
    val sky = skyboxEntity ?: return

    when (label) {
      "Indoor room" -> {
        env?.setComponent(Visible(true))
        sky.setComponent(Visible(false))
      }
      "Black void" -> {
        env?.setComponent(Visible(false))
        sky.setComponent(Visible(false))
      }
      "White void" -> {
        env?.setComponent(Visible(false))
        sky.setComponent(Visible(true))
        sky.setComponent(
            Material().apply {
              baseColor = Color4(1f, 1f, 1f, 1f)
              unlit = true
            })
      }
      "Landscape" -> {
        env?.setComponent(Visible(false))
        sky.setComponent(Visible(true))
        sky.setComponent(
            Material().apply {
              baseTextureAndroidResourceId = R.drawable.skydome
              unlit = true
            })
      }
      "Complex" -> {
        env?.setComponent(Visible(true))
        sky.setComponent(Visible(true))
        sky.setComponent(
            Material().apply {
              baseTextureAndroidResourceId = R.drawable.skydome
              unlit = true
            })
      }
    }
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
