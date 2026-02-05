# Development Log: Subliminal Message Tester - Head-Locked Panel Implementation

This document summarizes the technical challenges encountered and the solutions applied during the development session to implement a head-locked secondary UI panel using the Meta Spatial SDK.

## 1. Coordinate System Mismatch (Positioning)
*   **Issue:** Initial attempts to place the panel resulted in it being invisible or appearing behind the user.
*   **Discovery:** While many VR SDKs use $-Z$ as the forward vector, the specific configuration of the `viewerPose` in this project resulted in **$+Z$ being the forward direction** for world-space offsets relative to the head.
*   **Solution:** Applied a positive distance offset: 
    `targetPosition = viewerPose.t + (viewerPose.q * Vector3(0f, 0f, 1.0f))`

## 2. Back-Face Culling (The "Pitch-Black" Issue)
*   **Issue:** The panel was correctly positioned and head-locked but appeared entirely black.
*   **Discovery:** The "front" face of an SDK `Panel` (rendered as a Quad) faces the $+Z$ direction by default. By applying an additional 180-degree yaw rotation (`Quaternion(0f, 180f, 0f)`), the panel was being flipped, forcing the user to look at the culled/unlit back-side.
*   **Solution:** Removed the 180-degree rotation and used the head's rotation (`viewerPose.q`) directly. This aligned the panel's "front" face with the user's field of view.

## 3. Entity Component Conflicts (Crashes)
*   **Issue:** The application crashed upon launch during diagnostic testing.
*   **Discovery:** Attempting to attach both a `Panel` component and a standard `Mesh` component (e.g., a diagnostic sphere) to the same `Entity` caused a native memory assertion/conflict in the Meta Spatial SDK.
*   **Solution:** Maintained strict separation of components. The UI entity now exclusively contains `Panel`, `Transform`, and `Visible` components.

## 4. API & Build Errors
*   **Issue:** Build failures occurred when attempting to use advanced features like `layerConfig` or `UIPanelRenderOptions` within `UIPanelSettings`.
*   **Discovery:** There were API mismatches between generic documentation snippets and the specific SDK version integrated into the project. Certain named parameters (like `layerConfig`) were not found in the `UIPanelSettings` constructor.
*   **Solution:** Reverted to the most stable, basic constructor for `UIPanelSettings` and ensured all required imports were explicitly added.

## 5. UI Rendering Overrides
*   **Issue:** The panel content occasionally appeared empty or failed to respect XML styling during the transition to a head-locked system.
*   **Discovery:** Relying purely on XML inflation for high-speed head-locked elements can sometimes lead to initialization delays in the rendering thread.
*   **Solution:** Utilized the `panelSetupWithRootView` callback in `LayoutXMLPanelRegistration` to programmatically force the background color and text state, ensuring the panel is visible immediately upon spawning.

## Final Working Configuration
*   **Entity Placement:** Created in `onSceneReady`.
*   **Distance:** 1.0 meters along the head's $+Z$ axis.
*   **Rotation:** Identity relative to viewer orientation (`viewerPose.q`).
*   **System Registration:** `HeadLockSystem` registered in `AppSystemActivity.onCreate` to ensure frame-perfect updates.
