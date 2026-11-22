package com.flighttrackar.ui

import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flighttrackar.model.CameraIntrinsics
import com.flighttrackar.vision.CameraAnalyzer
import com.flighttrackar.vision.SkyAircraftDetector
import java.util.concurrent.Executors

/**
 * Hosts CameraX PreviewView + YUV ImageAnalysis for sky detection, reads exact Camera2
 * physical sensor & focal-length intrinsics (including multi-lens zoom range on Pixel 9 Pro),
 * and supports pinch-to-zoom and tap-to-select gestures.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraPreviewContainer(
    viewModel: FlightArViewModel,
    requestedZoomRatio: Float?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val detector = remember { SkyAircraftDetector() }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    // Apply programmatic zoom changes (e.g. from 0.5x / 1x / 2x / 5x preset pills)
    LaunchedEffect(requestedZoomRatio, boundCamera) {
        val target = requestedZoomRatio ?: return@LaunchedEffect
        val cam = boundCamera ?: return@LaunchedEffect
        cam.cameraControl.setZoomRatio(target)
        viewModel.onZoomApplied(target)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    viewModel.updateCameraIntrinsics {
                        it.copy(viewportWidthPx = size.width, viewportHeightPx = size.height)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    viewModel.onOverlayTap(offset.x, offset.y)
                }
            }
            .pointerInput(boundCamera) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    val cam = boundCamera ?: return@detectTransformGestures
                    val zoomState = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                    val newRatio = (zoomState.zoomRatio * zoomChange)
                        .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(newRatio)
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(960, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val analyzer = CameraAnalyzer(
                        detector = detector,
                        hintsProvider = { viewModel.getLatestRoiHints() },
                        visionModeProvider = { viewModel.getCurrentVisionMode() },
                        intrinsicsProvider = { viewModel.getCurrentIntrinsics() },
                        onResult = { res -> viewModel.onVisionFrameAnalyzed(res) }
                    )
                    imageAnalysis.setAnalyzer(analysisExecutor, analyzer)

                    val selector = CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            selector,
                            preview,
                            imageAnalysis
                        )
                        boundCamera = camera

                        // Extract physical Camera2 intrinsics for accurate AR FOV projection
                        val cam2Info = Camera2CameraInfo.from(camera.cameraInfo)
                        val physicalSize = cam2Info.getCameraCharacteristic(
                            CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
                        )
                        val focalLengths = cam2Info.getCameraCharacteristic(
                            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                        )
                        val sensorOrientation = cam2Info.getCameraCharacteristic(
                            CameraCharacteristics.SENSOR_ORIENTATION
                        ) ?: 90

                        viewModel.updateCameraIntrinsics { cur ->
                            cur.copy(
                                sensorWidthMm = physicalSize?.width ?: cur.sensorWidthMm,
                                sensorHeightMm = physicalSize?.height ?: cur.sensorHeightMm,
                                focalLengthMm = focalLengths?.firstOrNull() ?: cur.focalLengthMm,
                                sensorOrientationDeg = sensorOrientation
                            )
                        }

                        // Observe live zoom ratio (dynamically recalibrates AR FOV for 0.5x..30x!)
                        camera.cameraInfo.zoomState.observe(lifecycleOwner) { zs ->
                            if (zs != null) {
                                viewModel.updateCameraIntrinsics { cur ->
                                    cur.copy(
                                        zoomRatio = zs.zoomRatio,
                                        minZoomRatio = zs.minZoomRatio,
                                        maxZoomRatio = zs.maxZoomRatio
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Camera binding failed (e.g. emulator without rear camera)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )
    }
}
