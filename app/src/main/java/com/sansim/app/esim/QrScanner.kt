package com.sansim.app.esim

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerDialog(onDismiss: () -> Unit, onResult: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫码下载 Profile") },
        text = {
            if (!granted) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("需要相机权限才能扫描 LPA 二维码。")
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("授权相机") }
                }
            } else {
                Box(Modifier.fillMaxWidth().height(420.dp).background(Color.Black, RoundedCornerShape(16.dp))) {
                    CameraPreviewScanner(
                        modifier = Modifier.fillMaxSize(),
                        onCode = { code ->
                            LogCollector.d("QrScanner", "QR result=$code")
                            onResult(code)
                        }
                    )
                    Text(
                        "请对准 LPA:1$... 二维码",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreviewScanner(modifier: Modifier, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var done by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val scanner = BarcodeScanning.getClient()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analyzer ->
                        analyzer.setAnalyzer(executor) { proxy ->
                            val media = proxy.image
                            if (media != null && !done) {
                                val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                                scanner.process(image)
                                    .addOnSuccessListener { codes ->
                                        val value = codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                                            ?: codes.firstOrNull()?.rawValue
                                        if (!value.isNullOrBlank() && !done) {
                                            done = true
                                            onCode(value)
                                        }
                                    }
                                    .addOnCompleteListener { proxy.close() }
                            } else proxy.close()
                        }
                    }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }.onFailure { LogCollector.e("QrScanner", "bind camera failed", it) }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}
