package sh.belfry.app.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.net.InviteLink
import sh.belfry.core.net.InviteScan
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** What the scanner shows instead of (or over) the camera. Every text is fixed. */
enum class ScanUi(val title: String, val text: String) {
    SCANNING("", "Point the camera at the QR code of an invitation."),
    ASKING("The camera", "Belfry asks for the camera only to read the QR code of an invitation."),
    DENIED(
        "The camera is off for Belfry",
        "Belfry needs the camera to read the QR code of an invitation. Nothing is recorded or sent. Allow the camera, or open the invitation’s link instead.",
    ),
    NO_CAMERA("No camera", "This device has no camera. Open the invitation’s link on this device instead."),
    FAILED("The camera did not start", "Another app may be using it. Close it and try again, or open the invitation’s link instead."),
}

fun hasCamera(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

private fun cameraGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * "Scan an invitation": the camera reads QR codes and takes only an invitation link
 * (`https://chat.<domain>/join/<token>`, read by the parser the app's links go through). Any
 * other code: a fixed message, and it keeps scanning. The camera permission is asked here, when
 * the scanner opens, never before. CameraX + ZXing: works without Google Play services.
 */
@Composable
fun ScanScreen(onInvite: (InviteLink) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var ui by remember {
        mutableStateOf(
            when {
                !hasCamera(context) -> ScanUi.NO_CAMERA
                cameraGranted(context) -> ScanUi.SCANNING
                else -> ScanUi.ASKING
            },
        )
    }
    var badAt by remember { mutableLongStateOf(0L) }
    var bad by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        ui = if (granted) ScanUi.SCANNING else ScanUi.DENIED
    }
    LaunchedEffect(Unit) { if (ui == ScanUi.ASKING) launcher.launch(Manifest.permission.CAMERA) }
    // the message leaves 3 s after the last other code was seen
    LaunchedEffect(badAt) {
        if (badAt != 0L) { bad = true; delay(3000); bad = false }
    }
    BackHandler(onBack = onClose)
    ScanContent(
        ui = ui,
        message = if (bad) InviteScan.NOT_AN_INVITE else null,
        onClose = onClose,
        onAllow = {
            // came back from the settings with the permission, or ask (again)
            if (cameraGranted(context)) ui = ScanUi.SCANNING else launcher.launch(Manifest.permission.CAMERA)
        },
        onSettings = { openAppSettings(context) },
        onRetry = { ui = if (cameraGranted(context)) ScanUi.SCANNING else ScanUi.ASKING },
    ) {
        CameraPreview(
            onText = { text ->
                if (!found) {
                    val link = InviteScan.read(text)
                    if (link != null) { found = true; onInvite(link) } else badAt = System.nanoTime()
                }
            },
            onFailed = { ui = ScanUi.FAILED },
        )
    }
}

fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        // no settings screen on this device
    }
}

/** The scanner's screen without the camera ([preview] is drawn only while scanning). */
@Composable
fun ScanContent(
    ui: ScanUi,
    message: String?,
    onClose: () -> Unit,
    onAllow: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    preview: @Composable () -> Unit,
) {
    val c = Belfry.colors
    if (ui != ScanUi.SCANNING) {
        Column(Modifier.fillMaxSize().background(c.bg).testTag("scan-$ui")) {
            TopBar("Scan an invitation", navigation = { BackButton(onClose) })
            Column(Modifier.widthIn(max = 480.dp).align(Alignment.CenterHorizontally).padding(24.dp)) {
                Text(ui.title, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Spacer(Modifier.height(8.dp))
                Text(ui.text, fontSize = 16.sp, lineHeight = 22.sp, color = c.muted)
                Spacer(Modifier.height(24.dp))
                when (ui) {
                    ScanUi.DENIED -> {
                        BusyButton("Allow the camera", onClick = { onAllow() }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        BusyButton("Open the app’s settings", onClick = { onSettings() }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
                    }
                    ScanUi.FAILED -> BusyButton("Try again", onClick = { onRetry() }, modifier = Modifier.fillMaxWidth())
                    ScanUi.ASKING -> BusyButton("Allow the camera", onClick = { onAllow() }, modifier = Modifier.fillMaxWidth())
                    else -> BusyButton("Back", onClick = { onClose() }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
                }
            }
        }
        return
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("scan-SCANNING")) {
        preview()
        // the frame to aim with
        Box(
            Modifier.align(Alignment.Center).padding(40.dp).widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f)
                .border(3.dp, Color.White, RoundedCornerShape(20.dp)),
        )
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.6f))) {
                    IconAction(BelfryIcons.Back, "Back", onClose, tint = Color.White)
                }
            }
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (message != null) {
                    Text(
                        message, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.Black, textAlign = TextAlign.Center,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).padding(horizontal = 14.dp, vertical = 8.dp).testTag("scan-message"),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Text("Scan an invitation", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Spacer(Modifier.height(2.dp))
                Text(ui.text, fontSize = 15.sp, lineHeight = 20.sp, color = Color.White, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * The camera on screen, with every frame's grey plane given to [QrDecoder] on its own thread.
 * The back camera, or the front one on a device that has no other. Bound to the screen's
 * lifecycle: the camera is released when the scanner leaves or the app goes to the background.
 */
@Composable
private fun CameraPreview(onText: (String) -> Unit, onFailed: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val text by rememberUpdatedState(onText)
    val failed by rememberUpdatedState(onFailed)
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(owner) {
        val main = ContextCompat.getMainExecutor(context)
        val worker: ExecutorService = Executors.newSingleThreadExecutor()
        var provider: ProcessCameraProvider? = null
        var gone = false
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (gone) return@addListener
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(worker) { image ->
                    try {
                        val plane = image.planes[0]
                        val luma = QrDecoder.packed(plane.buffer, plane.rowStride, image.width, image.height)
                        val found = QrDecoder.decode(luma, image.width, image.height)
                        if (found != null) main.execute { if (!gone) text(found) }
                    } catch (e: Exception) {
                        // a frame that could not be read: the next one
                    } finally {
                        image.close()
                    }
                }
                val selector = when {
                    p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> null
                }
                if (selector == null) { failed(); return@addListener }
                p.unbindAll()
                p.bindToLifecycle(owner, selector, preview, analysis)
            } catch (e: Exception) {
                failed()
            }
        }, main)
        onDispose {
            gone = true
            try { provider?.unbindAll() } catch (e: Exception) { }
            worker.shutdown()
        }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}
