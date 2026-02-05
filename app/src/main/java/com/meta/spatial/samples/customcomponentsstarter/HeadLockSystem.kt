package com.meta.spatial.samples.customcomponentsstarter

import com.meta.spatial.core.Entity
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

class HeadLockSystem : SystemBase() {
    var panelEntity: Entity? = null

    override fun execute() {
        val entity = panelEntity ?: return
        val scene = getScene() ?: return
        val viewerPose = scene.getViewerPose()

        // Based on user feedback:
        // 1. Position: 1.0 meter in front of the head
        val distance = 1.0f 
        val targetPosition = viewerPose.t + (viewerPose.q * Vector3(0f, 0f, distance))

        // 2. Rotation: Use viewer rotation directly to show the front of the panel
        val targetRotation = viewerPose.q

        entity.setComponent(Transform(Pose(targetPosition, targetRotation)))
        entity.setComponent(Visible(true))
    }
}
