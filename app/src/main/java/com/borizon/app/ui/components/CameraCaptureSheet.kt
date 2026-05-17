package com.borizon.app.ui.components

import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.borizon.app.R
import com.borizon.app.util.BitmapUtils

/**
 * CameraX bottom sheet for capturing images.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraCaptureSheet(
    onImageCaptured: (Bitmap) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    var lastBoundSelector by remember { mutableStateOf<CameraSelector?>(null) }
    val imageCapture = remember { ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .build() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = stringResource(R.string.camera_take_picture),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .padding(horizontal = 16.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
        ) {
            // Camera preview
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                    val mainExecutor = ContextCompat.getMainExecutor(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build()
                            preview.setSurfaceProvider(previewView.surfaceProvider)
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner, cameraSelector, preview, imageCapture
                            )
                            lastBoundSelector = cameraSelector
                        } catch (e: Exception) {
                            Log.e("CameraCapture", "Camera bind failed", e)
                        }
                    }, mainExecutor)
                    previewView
                },
                modifier = Modifier.fillMaxSize(),
                update = { previewView ->
                    // Rebind camera when selector changes (flip camera)
                    if (cameraSelector != lastBoundSelector) {
                        val mainExecutor = ContextCompat.getMainExecutor(previewView.context)
                        cameraProviderFuture.addListener({
                            try {
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build()
                                preview.setSurfaceProvider(previewView.surfaceProvider)
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner, cameraSelector, preview, imageCapture
                                )
                                lastBoundSelector = cameraSelector
                            } catch (e: Exception) {
                                Log.e("CameraCapture", "Camera rebind failed", e)
                            }
                        }, mainExecutor)
                    }
                }
            )

            // Camera controls
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Flip camera
                FilledIconButton(
                    onClick = {
                        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        else CameraSelector.DEFAULT_BACK_CAMERA
                    },
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Default.FlipCameraAndroid, stringResource(R.string.camera_flip))
                }

                Spacer(modifier = Modifier.width(32.dp))

                // Shutter
                FilledIconButton(
                    onClick = {
                        imageCapture.takePicture(
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val bitmap = image.toBitmap()
                                    image.close()
                                    val resized = BitmapUtils.scaleToFit(bitmap, 1024)
                                    onImageCaptured(resized)
                                }
                                override fun onError(exception: ImageCaptureException) {
                                    Log.e("CameraCapture", "Capture failed", exception)
                                }
                            }
                        )
                    },
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.Camera, stringResource(R.string.camera_capture), modifier = Modifier.size(28.dp))
                }

                Spacer(modifier = Modifier.width(32.dp))

                // Close
                FilledIconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Default.Close, stringResource(R.string.close))
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
