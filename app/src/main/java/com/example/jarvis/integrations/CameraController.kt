package com.example.jarvis.integrations

import android.content.Intent
import android.provider.MediaStore

/** Opens the system camera app in photo or video mode ("Jarvis kamera och"). */
class CameraController(private val launcher: ActivityLauncher) {

    fun open(video: Boolean): ActivityLauncher.Result {
        val intent = Intent(if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        val result = launcher.launch(intent, if (video) "Video kamera" else "Kamera")
        if (result != ActivityLauncher.Result.NO_APP) return result
        // Some OEM cameras only register the capture action.
        return launcher.launch(Intent(if (video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE), "Kamera")
    }
}
