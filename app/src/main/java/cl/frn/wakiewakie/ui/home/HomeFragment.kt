package cl.frn.wakiewakie.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cl.frn.wakiewakie.databinding.FragmentHomeBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import kotlin.math.hypot

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var cameraExecutor: ExecutorService
    private var faceLandmarker: FaceLandmarker? = null
    private var eyeOpenThreshold: Float = 0.05f
    // Detección mejorada
    private enum class DrowsinessState { AWAKE, EYES_CLOSED, YAWNING, ASLEEP }
    private var currentState = DrowsinessState.AWAKE

    // Rolling windows (timestamps in ms)
    private val closedWindow: ArrayDeque<Long> = ArrayDeque()
    private val yawnWindow: ArrayDeque<Long> = ArrayDeque()

    // thresholds - ajustar según pruebas
    // EAR threshold is adjustable at runtime via the SeekBar
    private var EAR_THRESHOLD = 0.20f           // ojo "abierto" si EAR > threshold
    private val EAR_CLOSED_MS = 1200L           // si ojos cerrados por más de 1.2s => somnolencia
    private val EAR_EYE_CLOSED_MS = 300L        // estado ojos cerrados intermedio
    private var MAR_THRESHOLD = 0.40f           // boca abierta (bostezo)
    private val MAR_YAWN_MS = 400L              // duración para considerar un bostezo

    // Contadores por frames
    private var consecutiveClosedFrames = 0
    private val CLOSED_FRAMES_THRESHOLD = 24    // número de frames consecutivos para considerar dormido
    private var asleepFrameCount = 0
    private var asleepLocked = false
    // Para desbloquear cuando la persona despierte
    private var consecutiveOpenFrames = 0
    private val WAKE_FRAMES_THRESHOLD = 15

    // Alarma
    private var mediaPlayer: MediaPlayer? = null
    private var isAlarmPlaying = false
    // Fallback tone generator
    private var toneGenerator: ToneGenerator? = null
    private var beepExecutor: ScheduledExecutorService? = null
    private var beepFuture: ScheduledFuture<*>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        cameraExecutor = Executors.newSingleThreadExecutor()
        initFaceLandmarker()
        requestCameraPermission()

        // Configuración del SeekBar para ajustar el umbral EAR dinámicamente
        // Rango deseado: 0.0100 .. 0.2000 con resolución 0.0001
        val MIN_EAR = 0.01f
        val MAX_EAR = 0.20f
        val SCALE = 10000f
        EAR_THRESHOLD = EAR_THRESHOLD.coerceIn(MIN_EAR, MAX_EAR)
        binding.seekBarEyeThreshold.max = ((MAX_EAR - MIN_EAR) * SCALE).toInt() // 1900
        binding.seekBarEyeThreshold.progress = ((EAR_THRESHOLD - MIN_EAR) * SCALE).toInt()
        binding.textEyeThreshold.text = "Umbral EAR: %.4f".format(EAR_THRESHOLD)
        binding.seekBarEyeThreshold.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                EAR_THRESHOLD = MIN_EAR + progress / SCALE
                binding.textEyeThreshold.text = "Umbral EAR: %.4f".format(EAR_THRESHOLD)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        return binding.root
    }

    private val onFaceResult: (FaceLandmarkerResult, MPImage) -> Unit = { result, inputImage ->
        // Procesar la primera cara detectada
        val first = result.faceLandmarks().firstOrNull()
        if (first != null) {
            val landmarks = first
            val ear = computeEAR(landmarks)
            val mar = computeMAR(landmarks)
            val now = System.currentTimeMillis()

            // actualizar ventanas temporales (por compatibilidad)
            if (ear < EAR_THRESHOLD) {
                closedWindow.addLast(now)
            } else {
                // no clear here; keep for time-based checks
            }

            if (mar > MAR_THRESHOLD) {
                yawnWindow.addLast(now)
            }
            // limpiamos timestamps antiguos
            while (closedWindow.isNotEmpty() && now - closedWindow.first() > EAR_CLOSED_MS) closedWindow.removeFirst()
            while (yawnWindow.isNotEmpty() && now - yawnWindow.first() > MAR_YAWN_MS) yawnWindow.removeFirst()
            // limpiamos timestamps antiguos
            while (closedWindow.isNotEmpty() && now - closedWindow.first() > EAR_CLOSED_MS) closedWindow.removeFirst()
            while (yawnWindow.isNotEmpty() && now - yawnWindow.first() > MAR_YAWN_MS) yawnWindow.removeFirst()

            // lógica por frames: detectar ojos cerrados consecutivos y bloquear estado ASLEEP
            val eyeClosed = ear < EAR_THRESHOLD
            val yawning = mar > MAR_THRESHOLD

            if (!asleepLocked) {
                if (yawning) {
                    // bostezando: reset closed frames
                    consecutiveClosedFrames = 0
                    consecutiveOpenFrames = 0
                } else if (eyeClosed) {
                    consecutiveClosedFrames++
                    consecutiveOpenFrames = 0
                } else {
                    consecutiveClosedFrames = 0
                    consecutiveOpenFrames++
                }

                // transición por frames a ASLEEP
                if (consecutiveClosedFrames >= CLOSED_FRAMES_THRESHOLD && !yawning) {
                    // entrar a estado dormido y bloquear
                    asleepLocked = true
                    asleepFrameCount = consecutiveClosedFrames
                    currentState = DrowsinessState.ASLEEP
                    activity?.runOnUiThread {
                        binding.textHome.text = "Dormido / Somnolencia profunda"
                        startAlarm()
                    }
                }
            } else {
                // ya está bloqueado en ASLEEP: contamos frames y solo permitimos desbloquear tras suficiente apertura
                asleepFrameCount++
                if (!eyeClosed) {
                    consecutiveOpenFrames++
                } else {
                    consecutiveOpenFrames = 0
                }
                if (consecutiveOpenFrames >= WAKE_FRAMES_THRESHOLD) {
                    // desbloquear
                    asleepLocked = false
                    asleepFrameCount = 0
                    consecutiveClosedFrames = 0
                    consecutiveOpenFrames = 0
                    currentState = DrowsinessState.AWAKE
                    activity?.runOnUiThread {
                        binding.textHome.text = "Despierto"
                        stopAlarm()
                    }
                } else {
                    // mantener el estado dormido en la UI
                    activity?.runOnUiThread {
                        binding.textHome.text = "Dormido / Somnolencia profunda (frames: %d)".format(asleepFrameCount)
                    }
                }
            }

            // decidir estado
            // Show EAR/MAR values on the UI for tuning
            activity?.runOnUiThread {
                val earText = "EAR: %.3f".format(ear)
                val marText = "MAR: %.3f".format(mar)
                binding.textEyeThreshold.text = "Umbral EAR: %.4f | %s | %s".format(EAR_THRESHOLD, earText, marText)
            }

            // Si no está bloqueado y no acabamos de entrar en ASLEEP por frames, actualizamos estados normales
            if (!asleepLocked) {
                val newState = when {
                    // time-based fallback
                    closedWindow.isNotEmpty() && now - (closedWindow.firstOrNull() ?: now) >= EAR_CLOSED_MS -> DrowsinessState.ASLEEP
                    closedWindow.isNotEmpty() && now - (closedWindow.firstOrNull() ?: now) >= EAR_EYE_CLOSED_MS -> DrowsinessState.EYES_CLOSED
                    yawnWindow.isNotEmpty() && now - (yawnWindow.firstOrNull() ?: now) <= MAR_YAWN_MS -> DrowsinessState.YAWNING
                    else -> DrowsinessState.AWAKE
                }

                if (newState != currentState) {
                    currentState = newState
                    activity?.runOnUiThread {
                        when (currentState) {
                            DrowsinessState.AWAKE -> {
                                binding.textHome.text = "Despierto"
                                stopAlarm()
                            }
                            DrowsinessState.EYES_CLOSED -> {
                                binding.textHome.text = "Ojos cerrados (breve)"
                            }
                            DrowsinessState.YAWNING -> {
                                binding.textHome.text = "Bostezo detectado"
                            }
                            DrowsinessState.ASLEEP -> {
                                // note: frames-based ASLEEP handled above; keep fallback
                                binding.textHome.text = "Dormido / Somnolencia profunda"
                                startAlarm()
                            }
                        }
                    }
                }
            }

            
        }
    }

    private fun initFaceLandmarker() {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(onFaceResult)
            .build()
        faceLandmarker = FaceLandmarker.createFromOptions(requireContext(), options)
    }

    private fun isEyeClosed(landmarks: MutableList<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Boolean {
        // Deprecated: usamos nuevo método EAR
        val ear = computeEAR(landmarks)
        return ear < EAR_THRESHOLD
    }

    // Distancia euclídea
    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return hypot((x1 - x2), (y1 - y2))
    }

    // Cálculo aproximado del Eye Aspect Ratio (EAR) usando índices de MediaPipe FaceMesh
    private fun computeEAR(landmarks: MutableList<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Float {
        // índices aproximados para MediaPipe FaceMesh
        val left = intArrayOf(33, 160, 158, 133, 153, 144)
        val right = intArrayOf(263, 387, 385, 362, 380, 373)

        fun eyeEAR(idx: IntArray): Float {
            val p1 = landmarks.getOrNull(idx[0])
            val p2 = landmarks.getOrNull(idx[1])
            val p3 = landmarks.getOrNull(idx[2])
            val p4 = landmarks.getOrNull(idx[3])
            val p5 = landmarks.getOrNull(idx[4])
            val p6 = landmarks.getOrNull(idx[5])
            if (p1 == null || p2 == null || p3 == null || p4 == null || p5 == null || p6 == null) return 0f
            val A = dist(p2.x(), p2.y(), p6.x(), p6.y())
            val B = dist(p3.x(), p3.y(), p5.x(), p5.y())
            val C = dist(p1.x(), p1.y(), p4.x(), p4.y())
            if (C == 0f) return 0f
            return (A + B) / (2.0f * C)
        }

        val leftEAR = eyeEAR(left)
        val rightEAR = eyeEAR(right)
        return if (leftEAR <= 0f && rightEAR <= 0f) 0f else (leftEAR + rightEAR) / 2f
    }

    // Mouth Aspect Ratio (MAR) aproximado
    private fun computeMAR(landmarks: MutableList<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Float {
        // índices aproximados para labio superior/inferior y comisuras
        val top = landmarks.getOrNull(13)
        val bottom = landmarks.getOrNull(14)
        val left = landmarks.getOrNull(78)
        val right = landmarks.getOrNull(308)
        if (top == null || bottom == null || left == null || right == null) return 0f
        val vertical = dist(top.x(), top.y(), bottom.x(), bottom.y())
        val horizontal = dist(left.x(), left.y(), right.x(), right.y())
        if (horizontal == 0f) return 0f
        return vertical / horizontal
    }

    private fun startAlarm() {
        if (isAlarmPlaying) return
        try {
            // Prefer MediaPlayer con AudioAttributes
            val resId = resources.getIdentifier("alarm", "raw", requireContext().packageName)
            if (resId != 0) {
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(requireContext(), android.net.Uri.parse("android.resource://${requireContext().packageName}/$resId"))
                    isLooping = true
                    prepare()
                    start()
                }
                isAlarmPlaying = true
                return
            }

            // Si no hay recurso o falla, usar ToneGenerator como fallback
            if (toneGenerator == null) toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            if (beepExecutor == null) beepExecutor = Executors.newSingleThreadScheduledExecutor()
            // beep cada 700ms
            beepFuture = beepExecutor?.scheduleAtFixedRate({
                try {
                    toneGenerator?.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
                } catch (e: Exception) {
                    // ignore
                }
            }, 0, 700, TimeUnit.MILLISECONDS)
            isAlarmPlaying = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopAlarm() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            mediaPlayer = null
            beepFuture?.cancel(true)
            beepFuture = null
            beepExecutor?.shutdownNow()
            beepExecutor = null
            toneGenerator?.release()
            toneGenerator = null
        } catch (e: Exception) {
            // ignore
        }
        mediaPlayer = null
        isAlarmPlaying = false
    }


    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            binding.textHome.text = "Permiso de cámara denegado"
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ::analyzeImage)
                }
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val yBuffer = imageProxy.planes[0].buffer // Y
        val uBuffer = imageProxy.planes[1].buffer // U
        val vBuffer = imageProxy.planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val imageBytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeImage(imageProxy: ImageProxy) {
        val bitmap = imageProxyToBitmap(imageProxy)
        if (bitmap != null && faceLandmarker != null) {
            val mpImage = BitmapImageBuilder(bitmap).build()
            faceLandmarker!!.detectAsync(mpImage, System.currentTimeMillis())
        }
        imageProxy.close()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        cameraExecutor.shutdown()
        // cleanup audio
        stopAlarm()
    }
}