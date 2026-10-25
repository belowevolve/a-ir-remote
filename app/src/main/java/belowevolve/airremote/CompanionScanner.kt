package belowevolve.airremote

import android.Manifest
import android.content.pm.PackageManager
import android.view.ScaleGestureDetector
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.camera.CameraSettings
import java.util.Locale

// ZXing exposes zoom through its legacy camera API. Keep that detail inside this view.
@Suppress("DEPRECATION")
@Composable
internal fun CompanionScanner(onCode: (String) -> Unit, onManualCode: () -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val codeCallback by rememberUpdatedState(onCode)
    var allowed by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var error by remember { mutableStateOf("") }
    var zoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    val camera = remember { BarcodeView(context).apply {
        decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        cameraSettings = CameraSettings().apply { focusMode = CameraSettings.FocusMode.CONTINUOUS }
    } }
    fun applyZoom(value: Float) {
        zoom = value.coerceIn(1f, maxZoom)
        val target = zoom
        camera.cameraInstance?.changeCameraParameters { parameters ->
            if (parameters.isZoomSupported) {
                parameters.zoom = parameters.zoomRatios.indices.minByOrNull { kotlin.math.abs(parameters.zoomRatios[it] / 100f - target) } ?: 0
            }
            parameters
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(Unit) { if (!allowed) permission.launch(Manifest.permission.CAMERA) }
    val currentAllowed by rememberUpdatedState(allowed)
    LaunchedEffect(allowed) { if (allowed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) camera.resume() }
    DisposableEffect(camera, lifecycle) {
        var active = true
        val listener = object : CameraPreview.StateListener {
            override fun previewSized() = Unit
            override fun previewStarted() {
                camera.cameraInstance?.changeCameraParameters { parameters ->
                    val limit = if (parameters.isZoomSupported) (parameters.zoomRatios.last() / 100f).coerceAtMost(8f) else 1f
                    camera.post { if (active) { maxZoom = limit; applyZoom(zoom) } }
                    parameters
                }
            }
            override fun previewStopped() = Unit
            override fun cameraClosed() = Unit
            override fun cameraError(e: Exception) { error = "Не удалось открыть камеру" }
        }
        camera.addStateListener(listener)
        camera.decodeSingle { result -> codeCallback(result.text) }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && currentAllowed) camera.resume()
            if (event == Lifecycle.Event.ON_PAUSE) camera.pause()
        }
        lifecycle.addObserver(observer)
        if (allowed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) camera.resume()
        onDispose { active = false; lifecycle.removeObserver(observer); camera.pause() }
    }
    RemoteDialog(
        onDismissRequest = dismiss,
        title = { Text("Сканировать QR") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap)) {
                if (allowed) {
                    AndroidView(factory = {
                        camera.apply {
                            val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                                override fun onScale(detector: ScaleGestureDetector): Boolean { applyZoom(zoom * detector.scaleFactor); return true }
                            })
                            setOnTouchListener { view, event ->
                                view.parent?.requestDisallowInterceptTouchEvent(event.pointerCount > 1)
                                scale.onTouchEvent(event)
                                true
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RemoteLayout.HeaderShape))
                    if (maxZoom > 1f) {
                        Text(String.format(Locale.ROOT, "Приближение · %.1f×", zoom))
                        Slider(value = zoom, onValueChange = ::applyZoom, valueRange = 1f..maxZoom)
                    }
                } else TextButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Разрешить камеру") }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onManualCode) { Text("Ввести код") } },
        confirmButton = { TextButton(onClick = dismiss) { Text("Отмена") } },
    )
}
