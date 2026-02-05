package com.meta.spatial.samples.customcomponentsstarter

import com.meta.spatial.core.Entity
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Transform

import android.util.Log

class HeadLockSystem : SystemBase() {
    var panelEntity: Entity? = null

    override fun execute() {
        val entity = panelEntity
        if (entity == null) {
            return
        }
        val scene = getScene() ?: return
        val viewerPose = scene.getViewerPose()

        // 1. Calculate Position: 1.0 meters in front of the viewer
        // In Meta Spatial SDK, +Z is often the viewer's forward direction
        val distance = 1.0f 
        val targetPosition = viewerPose.t + (viewerPose.q * Vector3(0f, 0f, distance))

        // 2. Calculate Rotation: Face the user
        // We add a 180-degree yaw to the head rotation so the panel faces the user
        val targetRotation = viewerPose.q * Quaternion(0f, 180f, 0f)

        // 3. Apply Transform
        entity.setComponent(Transform(Pose(targetPosition, targetRotation)))
        
        // Ensure it's visible
        entity.setComponent(com.meta.spatial.toolkit.Visible(true))
    }
}
