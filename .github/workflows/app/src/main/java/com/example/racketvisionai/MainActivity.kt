package com.example.racketvisionai
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt
// ==========================================
// 1. SPORT MODES & BIOMECHANICS ENGINE
// ==========================================
enum class RacketSport(val label: String) {
 TENNIS("Tennis"),
 PADEL("Padel"),
 PICKLEBALL("Pickleball")
}
data class GroundstrokeMetrics(
 val landmarks: List<NormalizedLandmark> = emptyList(),
 val kneeBendAngle: Int = 0, val elbowAngle: Int = 0,
 val formScore: Int = 0,
 val cleanReps: Int = 0,
 val totalReps: Int = 0,
 val habitAlert: String? = null,
 val coachingTip: String = "Select Tennis, Padel, or Pickleball and step into the Ghost Pro stance"
)
class GroundstrokeAnalyzer(
 context: Context,
 private val onResults: (GroundstrokeMetrics) -> Unit
) {
 private var poseLandmarker: PoseLandmarker? = null
 var currentSport: RacketSport = RacketSport.TENNIS
 var isLeftHanded: Boolean = false
 private var cleanRepCount = 0
 private var totalRepCount = 0
 private var wasSwinging = false
 private var lastSwingTimestamp = 0L
 init {
 val baseOptions = BaseOptions.builder()
 .setModelAssetPath("pose_landmarker_lite.task")
 .build()
 val options = PoseLandmarker.PoseLandmarkerOptions.builder()
 .setBaseOptions(baseOptions)
 .setRunningMode(RunningMode.LIVE_STREAM)
 .setNumPoses(1)
 .setMinPoseDetectionConfidence(0.5f)
 .setMinTrackingConfidence(0.5f)
 .setResultListener { result: PoseLandmarkerResult, _ ->
 processPoseResult(result)
 }
 .build()
 poseLandmarker = PoseLandmarker.createFromOptions(context, options)
 }
 fun analyzeFrame(imageProxy: ImageProxy) {
 val frameTime = SystemClock.uptimeMillis()
 val bitmap = imageProxy.toBitmap()
 val matrix = Matrix().apply {
 postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
 }
 val rotatedBitmap = Bitmap.createBitmap(
 bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
 )
 val mpImage = BitmapImageBuilder(rotatedBitmap).build()
 poseLandmarker?.detectAsync(mpImage, frameTime)
 imageProxy.close()
 }
 private fun processPoseResult(result: PoseLandmarkerResult) {
 val pose = result.landmarks().firstOrNull() ?: return
 if (pose.size < 29) return
 // Select dominant hitting side landmarks
 val shoulderIdx = if (isLeftHanded) 11 else 12
 val elbowIdx = if (isLeftHanded) 13 else 14
 val wristIdx = if (isLeftHanded) 15 else 16
 val hipIdx = if (isLeftHanded) 23 else 24
 val kneeIdx = if (isLeftHanded) 25 else 26
 val ankleIdx = if (isLeftHanded) 27 else 28
 val elbowAngle = calculateAngle(pose[shoulderIdx], pose[elbowIdx], pose[wristIdx]).toInt()
 val kneeAngle = calculateAngle(pose[hipIdx], pose[kneeIdx], pose[ankleIdx]).toInt()
 // Measure horizontal wrist reach behind hip (detects big backswing vs compact swing)
 val wristBehindHip = if (isLeftHanded) {
 pose[wristIdx].x() - pose[hipIdx].x()
 } else {
 pose[hipIdx].x() - pose[wristIdx].x()
 }
 val now = SystemClock.uptimeMillis() var habitWarning: String? = null
 var tip = ""
 var score = 80
 when (currentSport) {
 RacketSport.TENNIS -> {
 val kneeScore = max(0, 100 - abs(kneeAngle - 120))
 val swingScore = if (elbowAngle in 115..165) 95 else 72
 score = ((kneeScore + swingScore) / 2).coerceIn(45, 99)
 if (kneeAngle > 148) {
 habitWarning = "Stiff Knees — Load Legs!"
 tip = "Bend knees ${kneeAngle - 120}° deeper for heavy topspin power"
 } else if (elbowAngle < 105) {
 tip = "Extend hitting arm out in front of your front hip at contact"
 } else {
 tip = "Great Tennis unit turn! Low-to-high brush through the ball"
 }
 }
 RacketSport.PADEL -> {
 val isOverSwinging = wristBehindHip > 0.14f
 val kneeScore = max(0, 100 - abs(kneeAngle - 115))
 score = (if (isOverSwinging) kneeScore - 25 else kneeScore).coerceIn(40, 99)
 if (isOverSwinging) {
 habitWarning = "TENNIS BACKSWING ALERT!"
 tip = "Keep paddle in front of your torso — shorten swing for glass rebounds"
 } else if (kneeAngle > 142) {
 tip = "Get lower in your knees for low padel wall bounces"
 } else {
 tip = "Compact Padel ready stance! Clean block and control in front"
 }
 }
 RacketSport.PICKLEBALL -> {
 val isBigSwing = wristBehindHip > 0.11f || elbowAngle > 155
 val kneeScore = max(0, 100 - abs(kneeAngle - 110))
 score = (if (isBigSwing) kneeScore - 25 else kneeScore).coerceIn(40, 99)
 if (isBigSwing) {
 habitWarning = "BIG SWING / WRIST FLICK ALERT!"
 tip = "Lock your wrist and lift softly from the shoulder at the kitchen line"
 } else if (kneeAngle > 138) {
 tip = "Drop hips lower at the Non-Volley Zone for fast hands"
 } else {
 tip = "Textbook Pickleball dink posture! Soft hands and low centre of gravity"
 }
 }
 }
 // Automatic Swing Rep Counter (triggers when hitting wrist crosses body centre quickly)
 val wristNearCenter = abs(pose[wristIdx].x() - pose[hipIdx].x()) < 0.08f
 if (wristNearCenter && !wasSwinging && (now - lastSwingTimestamp > 1400L)) {
 wasSwinging = true
 lastSwingTimestamp = now
 totalRepCount++
 if (habitWarning == null && score >= 75) {
 cleanRepCount++
 }
 } else if (!wristNearCenter) {
 wasSwinging = false
 }
 onResults(
 GroundstrokeMetrics(
 landmarks = pose,
 kneeBendAngle = kneeAngle,
 elbowAngle = elbowAngle,
 formScore = score,
 cleanReps = cleanRepCount,
 totalReps = totalRepCount,
 habitAlert = habitWarning,
 coachingTip = tip
 )
 )
 } private fun calculateAngle(a: NormalizedLandmark, b: NormalizedLandmark, c: NormalizedLandmark): Double {
 val radians = atan2((c.y() - b.y()).toDouble(), (c.x() - b.x()).toDouble()) -
 atan2((a.y() - b.y()).toDouble(), (a.x() - b.x()).toDouble())
 var angle = abs(radians * 180.0 / Math.PI)
 if (angle > 180.0) angle = 360.0 - angle
 return angle
 }
}
// ==========================================
// 2. MAIN ACTIVITY & PERMISSIONS
// ==========================================
class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
 super.onCreate(savedInstanceState)
 setContent {
 RacketVisionApp()
 }
 }
}
@Composable
fun RacketVisionApp() {
 val context = LocalContext.current
 var hasPermissions by remember {
 mutableStateOf(
 ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == 
PackageManager.PERMISSION_GRANTED &&
 ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == 
PackageManager.PERMISSION_GRANTED
 )
 }
 val launcher = rememberLauncherForActivityResult(
 ActivityResultContracts.RequestMultiplePermissions()
 ) { perms ->
 hasPermissions = perms.values.all { it }
 }
 LaunchedEffect(Unit) {
 if (!hasPermissions) {
 launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
 }
 }
 if (hasPermissions) {
 GroundstrokeCameraScreen()
 } else {
 Box(
 modifier = Modifier.fillMaxSize().background(Color.Black),
 contentAlignment = Alignment.Center
 ) {
 Text("Please grant Camera & Mic permissions to start coaching", color = Color.White)
 }
 }
}
// ==========================================
// 3. LIVE CAMERA + 3-SPORT SELECTOR + REPLAY
// ==========================================
@Composable
fun GroundstrokeCameraScreen() {
 val context = LocalContext.current
 val lifecycleOwner = LocalLifecycleOwner.current
 var selectedSport by remember { mutableStateOf(RacketSport.TENNIS) }
 var metrics by remember { mutableStateOf(GroundstrokeMetrics()) }
 var showGhostPro by remember { mutableStateOf(true) }
 var isLeftHanded by remember { mutableStateOf(false) }
 var isRecording by remember { mutableStateOf(false) }
 var activeRecording by remember { mutableStateOf<Recording?>(null) }
 var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
 var savedReplayUri by remember { mutableStateOf<Uri?>(null) } val analyzer = remember {
 GroundstrokeAnalyzer(context) { newMetrics -> metrics = newMetrics }
 }
 LaunchedEffect(selectedSport, isLeftHanded) {
 analyzer.currentSport = selectedSport
 analyzer.isLeftHanded = isLeftHanded
 }
 if (savedReplayUri != null) {
 GroundstrokeReplayScreen(
 videoUri = savedReplayUri!!,
 sport = selectedSport,
 metrics = metrics,
 onClose = { savedReplayUri = null }
 )
 return
 }
 Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
 // Layer 1: Live CameraX Feed & Video Recorder
 AndroidView(
 modifier = Modifier.fillMaxSize(),
 factory = { ctx ->
 val previewView = PreviewView(ctx)
 val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
 cameraProviderFuture.addListener({
 val cameraProvider = cameraProviderFuture.get()
 val preview = Preview.Builder().build().also {
 it.setSurfaceProvider(previewView.surfaceProvider)
 }
 val recorder = Recorder.Builder()
 .setQualitySelector(QualitySelector.from(Quality.FHD))
 .build()
 videoCapture = VideoCapture.withOutput(recorder)
 val imageAnalysis = ImageAnalysis.Builder()
 .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
 .build()
 .also {
 it.setAnalyzer(Executors.newSingleThreadExecutor()) { proxy ->
 analyzer.analyzeFrame(proxy)
 }
 }
 cameraProvider.unbindAll()
 cameraProvider.bindToLifecycle(
 lifecycleOwner,
 CameraSelector.DEFAULT_BACK_CAMERA,
 preview,
 videoCapture,
 imageAnalysis
 )
 }, ContextCompat.getMainExecutor(ctx))
 previewView
 }
 )
 // Layer 2: Sport-Specific Ghost Pro Stance Silhouette
 if (showGhostPro) {
 GroundstrokeGhostOverlay(
 sport = selectedSport,
 playerLandmarks = metrics.landmarks,
 isLeftHanded = isLeftHanded
 )
 }
 // Layer 3: Live Colour-Coded Neon Stick Figure
 GroundstrokeStickOverlay(metrics)
 // Layer 4: Top Sport Switcher & Toggles
 Column(
 modifier = Modifier
 .fillMaxWidth()
 .padding(top = 38.dp, start = 14.dp, end = 14.dp)
 ) { Row(
 modifier = Modifier.fillMaxWidth(),
 horizontalArrangement = Arrangement.SpaceBetween,
 verticalAlignment = Alignment.CenterVertically
 ) {
 Text(
 text = "RacketVision AI",
 color = Color(0xFF00FF88),
 fontSize = 19.sp,
 fontWeight = FontWeight.Bold
 )
 Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
 FilterChip(
 selected = showGhostPro,
 onClick = { showGhostPro = !showGhostPro },
 label = { Text("Ghost", fontSize = 11.sp) }
 )
 FilterChip(
 selected = isLeftHanded,
 onClick = { isLeftHanded = !isLeftHanded },
 label = { Text(if (isLeftHanded) "Lefty" else "Righty", fontSize = 11.sp) }
 )
 }
 }
 // 3-Sport Selector Pills (Tennis | Padel | Pickleball)
 Row(
 modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
 horizontalArrangement = Arrangement.spacedBy(8.dp)
 ) {
 RacketSport.entries.forEach { sport ->
 FilterChip(
 selected = selectedSport == sport,
 onClick = { selectedSport = sport },
 label = { Text(sport.label, fontWeight = FontWeight.Bold) },
 colors = FilterChipDefaults.filterChipColors(
 selectedContainerColor = Color(0xFF00FF88),
 selectedLabelColor = Color.Black
 )
 )
 }
 }
 // Cross-Sport Habit Detector Banner
 metrics.habitAlert?.let { alertMsg ->
 Box(
 modifier = Modifier
 .fillMaxWidth()
 .padding(top = 8.dp)
 .background(Color(0xDDFF3B30), RoundedCornerShape(12.dp))
 .padding(horizontal = 14.dp, vertical = 8.dp)
 ) {
 Text(
 text = alertMsg,
 color = Color.White,
 fontWeight = FontWeight.Bold,
 fontSize = 13.sp
 )
 }
 }
 }
 // Layer 5: Bottom Record Button + 3-Metric Glass Card
 Column(
 modifier = Modifier
 .align(Alignment.BottomCenter)
 .fillMaxWidth()
 .padding(16.dp),
 horizontalAlignment = Alignment.CenterHorizontally
 ) {
 Button(
 onClick = {
 val capture = videoCapture ?: return@Button
 if (isRecording) {
 activeRecording?.stop()
 activeRecording = null
 isRecording = false } else {
 val contentValues = ContentValues().apply {
 put(MediaStore.MediaColumns.DISPLAY_NAME, "${selectedSport.label}_$
{System.currentTimeMillis()}")
 put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
 }
 val outputOptions = MediaStoreOutputOptions.Builder(
 context.contentResolver,
 MediaStore.Video.Media.EXTERNAL_CONTENT_URI
 ).setContentValues(contentValues).build()
 activeRecording = capture.output
 .prepareRecording(context, outputOptions)
 .withAudioEnabled()
 .start(ContextCompat.getMainExecutor(context)) { event ->
 if (event is VideoRecordEvent.Finalize && !event.hasError()) {
 savedReplayUri = event.outputResults.outputUri
 }
 }
 isRecording = true
 }
 },
 colors = ButtonDefaults.buttonColors(
 containerColor = if (isRecording) Color.Red else Color(0xFF00FF88)
 ),
 shape = CircleShape,
 modifier = Modifier.padding(bottom = 10.dp)
 ) {
 Text(
 text = if (isRecording) "STOP & REVIEW RALLY" else "RECORD $
{selectedSport.label.uppercase()}",
 color = if (isRecording) Color.White else Color.Black,
 fontWeight = FontWeight.Bold
 )
 }
 Card(
 modifier = Modifier.fillMaxWidth(),
 shape = RoundedCornerShape(24.dp),
 colors = CardDefaults.cardColors(containerColor = Color(0xCC121A1E))
 ) {
 Column(modifier = Modifier.padding(16.dp)) {
 Row(
 modifier = Modifier.fillMaxWidth(),
 horizontalArrangement = Arrangement.SpaceAround
 ) {
 SportMetricGauge("Form Score", "${metrics.formScore}%", Color(0xFF00FF88))
 SportMetricGauge("Clean Reps", "${metrics.cleanReps}/${metrics.totalReps}", 
Color(0xFF00E5FF))
 SportMetricGauge("Knee Load", "${metrics.kneeBendAngle}°", Color(0xFF00E5FF))
 }
 Spacer(modifier = Modifier.height(12.dp))
 Text(
 text = metrics.coachingTip,
 color = Color.White,
 fontSize = 14.sp,
 lineHeight = 19.sp
 )
 }
 }
 }
 }
}
// ==========================================
// 4. NEON STICK FIGURE & METRIC GAUGE UI
// ==========================================
@Composable
fun GroundstrokeStickOverlay(metrics: GroundstrokeMetrics) {
 val landmarks = metrics.landmarks
 if (landmarks.isEmpty()) return
 val connections = listOf(
 11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16, 11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 24 to 26, 26 to 28
 )
 Canvas(modifier = Modifier.fillMaxSize()) {
 val skeletonColor = if (metrics.habitAlert == null) Color(0xFF00FF88) else Color(0xFFFF9500)
 connections.forEach { (startIdx, endIdx) ->
 if (startIdx < landmarks.size && endIdx < landmarks.size) {
 val start = Offset(landmarks[startIdx].x() * size.width, landmarks[startIdx].y() * size.height)
 val end = Offset(landmarks[endIdx].x() * size.width, landmarks[endIdx].y() * size.height)
 drawLine(color = skeletonColor, start = start, end = end, strokeWidth = 10f, cap = 
StrokeCap.Round)
 }
 }
 listOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28).forEach { idx ->
 if (idx < landmarks.size) {
 drawCircle(
 color = Color.White,
 radius = 11f,
 center = Offset(landmarks[idx].x() * size.width, landmarks[idx].y() * size.height)
 )
 }
 }
 }
}
@Composable
fun SportMetricGauge(label: String, value: String, ringColor: Color) {
 Column(horizontalAlignment = Alignment.CenterHorizontally) {
 Box(contentAlignment = Alignment.Center, modifier = Modifier.size(70.dp)) {
 Canvas(modifier = Modifier.fillMaxSize()) {
 drawArc(
 color = ringColor.copy(alpha = 0.25f),
 startAngle = 0f, sweepAngle = 360f, useCenter = false,
 style = Stroke(width = 8.dp.toPx())
 )
 drawArc(
 color = ringColor,
 startAngle = -90f, sweepAngle = 285f, useCenter = false,
 style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
 )
 }
 Text(text = value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
 }
 Spacer(modifier = Modifier.height(5.dp))
 Text(text = label, color = Color.LightGray, fontSize = 11.sp)
 }
}
// ==========================================
// 5. SPORT-SPECIFIC GHOST PRO OVERLAY
// ==========================================
@Composable
fun GroundstrokeGhostOverlay(
 sport: RacketSport,
 playerLandmarks: List<NormalizedLandmark>,
 isLeftHanded: Boolean
) {
 val proTemplate = remember(sport, isLeftHanded) {
 val raw = when (sport) {
 RacketSport.TENNIS -> mapOf(
 0 to Offset(0.50f, 0.34f),
 11 to Offset(0.44f, 0.41f), 12 to Offset(0.56f, 0.42f),
 13 to Offset(0.38f, 0.48f), 14 to Offset(0.66f, 0.46f),
 15 to Offset(0.45f, 0.44f), 16 to Offset(0.72f, 0.40f),
 23 to Offset(0.45f, 0.56f), 24 to Offset(0.55f, 0.56f),
 25 to Offset(0.38f, 0.70f), 26 to Offset(0.61f, 0.70f),
 27 to Offset(0.35f, 0.85f), 28 to Offset(0.64f, 0.85f)
 )
 RacketSport.PADEL -> mapOf(
 0 to Offset(0.50f, 0.37f),
 11 to Offset(0.44f, 0.44f), 12 to Offset(0.56f, 0.44f),
 13 to Offset(0.42f, 0.52f), 14 to Offset(0.58f, 0.52f),
 15 to Offset(0.47f, 0.46f), 16 to Offset(0.53f, 0.46f),
 23 to Offset(0.45f, 0.59f), 24 to Offset(0.55f, 0.59f), 25 to Offset(0.39f, 0.72f), 26 to Offset(0.61f, 0.72f),
 27 to Offset(0.37f, 0.86f), 28 to Offset(0.63f, 0.86f)
 )
 RacketSport.PICKLEBALL -> mapOf(
 0 to Offset(0.50f, 0.39f),
 11 to Offset(0.44f, 0.46f), 12 to Offset(0.56f, 0.46f),
 13 to Offset(0.42f, 0.54f), 14 to Offset(0.58f, 0.54f),
 15 to Offset(0.48f, 0.47f), 16 to Offset(0.52f, 0.47f),
 23 to Offset(0.45f, 0.61f), 24 to Offset(0.55f, 0.61f),
 25 to Offset(0.38f, 0.74f), 26 to Offset(0.62f, 0.74f),
 27 to Offset(0.36f, 0.87f), 28 to Offset(0.64f, 0.87f)
 )
 }
 if (isLeftHanded) raw.mapValues { (_, pt) -> Offset(1f - pt.x, pt.y) } else raw
 }
 val alignmentScore = remember(playerLandmarks, proTemplate) {
 if (playerLandmarks.size < 29) 0 else {
 var totalDist = 0f
 var count = 0
 proTemplate.forEach { (idx, targetPt) ->
 val p = playerLandmarks[idx]
 val dx = p.x() - targetPt.x
 val dy = p.y() - targetPt.y
 totalDist += sqrt(dx * dx + dy * dy)
 count++
 }
 ((1f - (totalDist / count) * 3.5f) * 100).toInt().coerceIn(0, 100)
 }
 }
 val isLocked = alignmentScore >= 85
 val ghostColor = if (isLocked) Color(0xAA00FF88) else Color(0x7700E5FF)
 Canvas(modifier = Modifier.fillMaxSize()) {
 val w = size.width
 val h = size.height
 val dashEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 14f), 0f)
 val bones = listOf(
 11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,
 11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 24 to 26, 26 to 28
 )
 bones.forEach { (a, b) ->
 val start = proTemplate[a] ?: return@forEach
 val end = proTemplate[b] ?: return@forEach
 drawLine(
 color = ghostColor,
 start = Offset(start.x * w, start.y * h),
 end = Offset(end.x * w, end.y * h),
 strokeWidth = 11f,
 cap = StrokeCap.Round,
 pathEffect = if (isLocked) null else dashEffect
 )
 }
 proTemplate[0]?.let { head ->
 drawCircle(
 color = ghostColor,
 radius = 26f,
 center = Offset(head.x * w, head.y * h),
 style = Stroke(width = 6f)
 )
 }
 }
}
// ==========================================
// 6. SLOW-MOTION RALLY REPLAY SCREEN
// ==========================================
@Composable
fun GroundstrokeReplayScreen(
 videoUri: Uri,
 sport: RacketSport,
 metrics: GroundstrokeMetrics, onClose: () -> Unit
) {
 val context = LocalContext.current
 var playbackSpeed by remember { mutableFloatStateOf(0.5f) }
 var sliderPosition by remember { mutableFloatStateOf(0f) }
 var videoDuration by remember { mutableLongStateOf(1L) }
 val exoPlayer = remember {
 ExoPlayer.Builder(context).build().apply {
 setMediaItem(MediaItem.fromUri(videoUri))
 repeatMode = ExoPlayer.REPEAT_MODE_ALL
 playbackParameters = PlaybackParameters(playbackSpeed)
 prepare()
 playWhenReady = true
 }
 }
 LaunchedEffect(exoPlayer) {
 while (true) {
 if (exoPlayer.duration > 0) {
 videoDuration = exoPlayer.duration
 sliderPosition = exoPlayer.currentPosition.toFloat() / videoDuration
 }
 delay(50L)
 }
 }
 DisposableEffect(Unit) {
 onDispose { exoPlayer.release() }
 }
 Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
 AndroidView(
 modifier = Modifier.fillMaxSize(),
 factory = { ctx ->
 PlayerView(ctx).apply {
 player = exoPlayer
 useController = false
 layoutParams = FrameLayout.LayoutParams(
 ViewGroup.LayoutParams.MATCH_PARENT,
 ViewGroup.LayoutParams.MATCH_PARENT
 )
 }
 }
 )
 Button(
 onClick = onClose,
 modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 16.dp),
 colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC121A1E))
 ) {
 Text("Done", color = Color.White)
 }
 Column(
 modifier = Modifier
 .align(Alignment.BottomCenter)
 .fillMaxWidth()
 .background(Color(0xDD121A1E), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
 .padding(16.dp)
 ) {
 Text(
 text = "${sport.label} Session: ${metrics.cleanReps}/${metrics.totalReps} Clean Reps",
 color = Color(0xFF00FF88),
 fontWeight = FontWeight.Bold,
 fontSize = 14.sp
 )
 Slider(
 value = sliderPosition.coerceIn(0f, 1f),
 onValueChange = { fraction ->
 sliderPosition = fraction
 exoPlayer.pause()
 exoPlayer.seekTo((fraction * videoDuration).toLong())
 },
 colors = SliderDefaults.colors(
 thumbColor = Color(0xFF00FF88),
 activeTrackColor = Color(0xFF00FF88) )
 )
 Row(
 modifier = Modifier.fillMaxWidth(),
 horizontalArrangement = Arrangement.SpaceEvenly
 ) {
 listOf(0.25f to "0.25x Slow", 0.5f to "0.5x Slow", 1.0f to "1.0x Live").forEach { (speed, 
label) ->
 FilterChip(
 selected = playbackSpeed == speed,
 onClick = {
 playbackSpeed = speed
 exoPlayer.playbackParameters = PlaybackParameters(speed)
 exoPlayer.play()
 },
 label = { Text(label) }
 )
 }
 }
 Spacer(modifier = Modifier.height(6.dp))
 Text(
 text = "Coach Note: ${metrics.coachingTip}",
 color = Color.White,
 fontSize = 13.sp
 )
 }
 }
}
