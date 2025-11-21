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
import androidx.core.net.toUri
import cl.frn.wakiewakie.DrowsinessState
import cl.frn.wakiewakie.DrowsinessLogger
import android.content.Context
import android.widget.Toast
import android.widget.Button
import android.graphics.Color
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var cameraExecutor: ExecutorService
    private var faceLandmarker: FaceLandmarker? = null
    private var eyeOpenThreshold: Float = 0.05f
    // Detección mejorada
    private var currentState = DrowsinessState.AWAKE

    // Sistema de logging - usar singleton
    private val drowsinessLogger = DrowsinessLogger.getInstance()

    // Rolling windows (timestamps in ms)
    private val closedWindow: ArrayDeque<Long> = ArrayDeque()
    private val yawnWindow: ArrayDeque<Long> = ArrayDeque()

    // thresholds - ajustar según pruebas
    // EAR threshold is adjustable at runtime via the SeekBar
    private var EAR_THRESHOLD = 0.20f           // ojo "abierto" si EAR > threshold
    private val EAR_CLOSED_MS = 1500L           // aumentado: si ojos cerrados por más de 1.5s => somnolencia (time-based)
    private val EAR_EYE_CLOSED_MS = 300L        // estado ojos cerrados intermedio
    private var MAR_THRESHOLD = 0.40f           // boca abierta (bostezo)
    private val MAR_YAWN_MS = 400L              // duración para considerar un bostezo

    // Contadores por frames
    private var consecutiveClosedFrames = 0
    private val CLOSED_FRAMES_THRESHOLD = 36    // aumentado: más frames consecutivos para considerar dormido (reduce falsos positivos)
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

    // Persistencia
    private val PREFS_NAME = "wakie_prefs"
    private val PREF_EAR_THRESHOLD = "ear_threshold"
    private val PREF_MAR_THRESHOLD = "mar_threshold"
    private val PREF_ALARM_URI = "alarm_uri" // Nuevo: Clave para URI de alarma personalizada

    // Calibración
    private var isCalibrating = false
    private val calibrationEars = ArrayList<Float>()
    private val calibrationMars = ArrayList<Float>()
    private var calibrationEndTime = 0L
    private val CALIBRATION_MS = 3000L // duración de calibración en ms

    // Referencia al botón creado programáticamente
    private var programmaticCalibrateButton: Button? = null

    // Volume monitoring
    private var volumeReceiver: BroadcastReceiver? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Inicializar el logger singleton con el contexto
        drowsinessLogger.initialize(requireContext())

        initFaceLandmarker()
        requestCameraPermission()

        // Cargar valores guardados (si existen) antes de configurar el SeekBar
        loadThresholdsFromPrefs()

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
                // Guardar cambio manual inmediatamente
                saveThresholdsToPrefs()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // Botón de configuración (tuerca)
        binding.btnSettings.setOnClickListener {
            if (binding.layoutThresholds.visibility == View.VISIBLE) {
                binding.layoutThresholds.visibility = View.GONE
            } else {
                binding.layoutThresholds.visibility = View.VISIBLE
            }
        }

        // --- Reemplazamos la búsqueda por id por un botón creado programáticamente ---
        // Creamos un botón grande y visible en la parte superior del fragment
        val rootGroup = binding.root as ViewGroup
        programmaticCalibrateButton = Button(requireContext()).apply {
            id = View.generateViewId()
            text = "Calibrar ojos y boca"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(30, 24, 30, 24)
            setBackgroundColor(Color.parseColor("#E64A19")) // color naranja oscuro visible
            // hacer *match parent* en ancho para que sea claramente visible
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isAllCaps = false
        }
        // Añadir como primer hijo para que quede arriba y visible
        try {
            rootGroup.addView(programmaticCalibrateButton, 0)
        } catch (e: Exception) {
            // Si no se puede insertar en la posición 0, añadir al final como fallback
            try { rootGroup.addView(programmaticCalibrateButton) } catch (_: Exception) {}
        }

        programmaticCalibrateButton?.setOnClickListener {
            if (isCalibrating) {
                Toast.makeText(requireContext(), "Calibración ya en curso...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startCalibration()
        }

        // Buscamos el botón de calibrar de forma segura; si no existe, usamos un fallback sobre textHome
        val btnId = resources.getIdentifier("buttonCalibrate", "id", requireContext().packageName)
        val btnCalibrate = if (btnId != 0) binding.root.findViewById<Button?>(btnId) else null

        if (btnCalibrate != null) {
            btnCalibrate.setOnClickListener {
                if (isCalibrating) {
                    Toast.makeText(requireContext(), "Calibración ya en curso...", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                startCalibration()
            }
        } else {
            // Fallback leve: permitir iniciar calibración tocando el estado (útil mientras no se agregue el botón al layout)
            binding.textHome.setOnClickListener {
                if (isCalibrating) {
                    Toast.makeText(requireContext(), "Calibración ya en curso...", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Toast.makeText(requireContext(), "Botón 'buttonCalibrate' no encontrado. Iniciando calibración vía texto.", Toast.LENGTH_SHORT).show()
                startCalibration()
            }
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        // Forzar que los botones de volumen controlen el volumen de la alarma
        requireActivity().volumeControlStream = AudioManager.STREAM_ALARM
        checkVolume()
        registerVolumeReceiver()
    }

    override fun onPause() {
        super.onPause()
        // Restaurar comportamiento por defecto
        requireActivity().volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
        unregisterVolumeReceiver()
    }

    private fun checkVolume() {
        val audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Verificar volumen de alarma, ya que es el que usa la app para despertar
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        
        if (currentVolume < maxVolume) {
            binding.textVolumeWarning.visibility = View.VISIBLE
        } else {
            binding.textVolumeWarning.visibility = View.GONE
        }
    }

    private fun registerVolumeReceiver() {
        volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                    checkVolume()
                }
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        requireContext().registerReceiver(volumeReceiver, filter)
    }

    private fun unregisterVolumeReceiver() {
        volumeReceiver?.let {
            try {
                requireContext().unregisterReceiver(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        volumeReceiver = null
    }

    private val onFaceResult: (FaceLandmarkerResult, MPImage) -> Unit = { result, inputImage ->
        // Verificar que el binding no sea null antes de procesar
        if (_binding != null) {
            // Procesar la primera cara detectada
            val first = result.faceLandmarks().firstOrNull()
            if (first != null) {
                val landmarks = first
                val ear = computeEAR(landmarks)
                val mar = computeMAR(landmarks)
                val now = System.currentTimeMillis()

                // --- Manejo de calibración: recolectar muestras mientras isCalibrating ---
                if (isCalibrating) {
                    calibrationEars.add(ear)
                    calibrationMars.add(mar)
                    // si llegó el tiempo, finalizar calibración
                    if (now >= calibrationEndTime) {
                        // calcular promedios (en camera thread) y aplicar heurística simple
                        val avgEar = if (calibrationEars.isNotEmpty()) calibrationEars.average().toFloat() else ear
                        val avgMar = if (calibrationMars.isNotEmpty()) calibrationMars.average().toFloat() else mar

                        // Heurística para thresholds (ajustable):
                        // EAR_THRESHOLD: un porcentaje del EAR en ojos abiertos (p. ej. 80%)
                        // MAR_THRESHOLD: algo mayor que MAR en reposo (p. ej. 1.5x) para detectar bostezos
                        val newEarThreshold = (avgEar * 0.8f).coerceIn(0.01f, 0.2f)
                        val newMarThreshold = (avgMar * 1.6f + 0.02f).coerceAtLeast(0.05f)

                        EAR_THRESHOLD = newEarThreshold
                        MAR_THRESHOLD = newMarThreshold

                        // persistir y actualizar UI en hilo principal
                        activity?.runOnUiThread {
                            // actualizar SeekBar
                            val MIN_EAR = 0.01f
                            val SCALE = 10000f
                            binding.seekBarEyeThreshold.progress = ((EAR_THRESHOLD - MIN_EAR) * SCALE).toInt()
                            binding.textEyeThreshold.text = "Umbral EAR: %.4f | EAR_prom: %.3f | MAR_prom: %.3f".format(EAR_THRESHOLD, avgEar, avgMar)
                            programmaticCalibrateButton?.text = "Calibrar"
                            Toast.makeText(requireContext(), "Calibración completada", Toast.LENGTH_SHORT).show()
                        }
                        saveThresholdsToPrefs()
                        // limpiar estado
                        isCalibrating = false
                        calibrationEars.clear()
                        calibrationMars.clear()
                    }
                    // aún en calibración: evitar procesar lógica de somnolencia restante para no interferir
                    // pero dejamos que el resto del código siga mostrando valores (si lo desea)
                }

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

                        // Registrar cambio de estado a ASLEEP por frames
                        if (currentState != DrowsinessState.ASLEEP) {
                            drowsinessLogger.logStateChangeSync(DrowsinessState.ASLEEP, ear, mar)
                        }

                        currentState = DrowsinessState.ASLEEP
                        activity?.runOnUiThread {
                            // Verificar binding antes de actualizar UI
                            _binding?.let { binding ->
                                binding.textHome.text = "Dormido / Somnolencia profunda"
                            }
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

                        // Registrar cambio de estado a AWAKE cuando despierta
                        if (currentState != DrowsinessState.AWAKE) {
                            drowsinessLogger.logStateChangeSync(DrowsinessState.AWAKE, ear, mar)
                        }

                        currentState = DrowsinessState.AWAKE
                        activity?.runOnUiThread {
                            // Verificar binding antes de actualizar UI
                            _binding?.let { binding ->
                                binding.textHome.text = "Despierto"
                            }
                            stopAlarm()
                        }
                    } else {
                        // mantener el estado dormido en la UI
                        activity?.runOnUiThread {
                            // Verificar binding antes de actualizar UI
                            _binding?.let { binding ->
                                binding.textHome.text = "Dormido / Somnolencia profunda (frames: %d)".format(asleepFrameCount)
                            }
                        }
                    }
                }

                // decidir estado
                // Show EAR/MAR values on the UI for tuning
                activity?.runOnUiThread {
                    // Verificar binding antes de actualizar UI
                    _binding?.let { binding ->
                        val earText = "EAR: %.3f".format(ear)
                        val marText = "MAR: %.3f".format(mar)
                        binding.textEyeThreshold.text = "Umbral EAR: %.4f | %s | %s".format(EAR_THRESHOLD, earText, marText)
                    }
                }

                // Si no está bloqueado y no acabamos de entrar en ASLEEP por frames, actualizamos estados normales
                if (!asleepLocked) {
                    val newState = when {
                        // time-based fallback: marcar ASLEEP *pero NO iniciar la alarma desde aquí*
                        closedWindow.isNotEmpty() && now - (closedWindow.firstOrNull() ?: now) >= EAR_CLOSED_MS -> DrowsinessState.ASLEEP
                        closedWindow.isNotEmpty() && now - (closedWindow.firstOrNull() ?: now) >= EAR_EYE_CLOSED_MS -> DrowsinessState.EYES_CLOSED
                        yawnWindow.isNotEmpty() && now - (yawnWindow.firstOrNull() ?: now) <= MAR_YAWN_MS -> DrowsinessState.YAWNING
                        else -> DrowsinessState.AWAKE
                    }

                    if (newState != currentState) {
                        // Registrar cambio de estado con EAR y MAR
                        drowsinessLogger.logStateChangeSync(newState, ear, mar)

                        currentState = newState
                        activity?.runOnUiThread {
                            // Verificar binding antes de actualizar UI
                            _binding?.let { binding ->
                                when (currentState) {
                                    DrowsinessState.AWAKE -> {
                                        binding.textHome.text = "Despierto"
                                        stopAlarm() // detener alarma solo aquí o desde el unlock por frames
                                    }
                                    DrowsinessState.EYES_CLOSED -> {
                                        binding.textHome.text = "Ojos cerrados (breve)"
                                        // NO iniciar alarma
                                    }
                                    DrowsinessState.YAWNING -> {
                                        binding.textHome.text = "Bostezo detectado"
                                        // NO iniciar alarma
                                    }
                                    DrowsinessState.ASLEEP -> {
                                        // Nota: no iniciamos la alarma desde la comprobación time-based.
                                        // La alarma seguirá iniciándose únicamente desde la lógica por frames (asleepLocked),
                                        // que es una confirmación más robusta y evita sonidos por cierres breves.
                                        binding.textHome.text = "Dormido / Somnolencia profunda"
                                        // NO startAlarm() aquí
                                    }
                                }
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
            // --- NUEVO: Comprobación de URI personalizada ---
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val uriString = prefs.getString(PREF_ALARM_URI, null)
            
            if (uriString != null) {
                try {
                    val uri = Uri.parse(uriString)
                    mediaPlayer = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        // Verificar y configurar permiso antes de setDataSource
                        try {
                            requireContext().contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (e: Exception) {
                            // ignore, might already have permission or it's not needed
                        }
                        
                        setDataSource(requireContext(), uri)
                        isLooping = true
                        prepare()
                        start()
                    }
                    isAlarmPlaying = true
                    return
                } catch (e: Exception) {
                    // Fallback to default alarm
                    e.printStackTrace()
                }
            }
            
            // Prefer MediaPlayer con AudioAttributes (Default)
            val resId = resources.getIdentifier("alarm", "raw", requireContext().packageName)
            if (resId != 0) {
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(requireContext(),
                        "android.resource://${requireContext().packageName}/$resId".toUri())
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

    // Métodos públicos para acceder al sistema de logging
    fun getDrowsinessLog(): String {
        return drowsinessLogger.getFormattedLog()
    }

    fun getDrowsinessLogEntries(): List<cl.frn.wakiewakie.DrowsinessLogEntry> {
        return drowsinessLogger.getLogEntries()
    }

    suspend fun clearDrowsinessLog() {
        drowsinessLogger.clearLog()
    }

    fun getEntriesForState(state: DrowsinessState): List<cl.frn.wakiewakie.DrowsinessLogEntry> {
        return drowsinessLogger.getEntriesForState(state)
    }

    // Nuevos métodos: persistencia de umbrales
    private fun saveThresholdsToPrefs() {
        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putFloat(PREF_EAR_THRESHOLD, EAR_THRESHOLD)
                .putFloat(PREF_MAR_THRESHOLD, MAR_THRESHOLD)
                .apply()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun loadThresholdsFromPrefs() {
        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val storedEar = prefs.getFloat(PREF_EAR_THRESHOLD, EAR_THRESHOLD)
            val storedMar = prefs.getFloat(PREF_MAR_THRESHOLD, MAR_THRESHOLD)
            EAR_THRESHOLD = storedEar
            MAR_THRESHOLD = storedMar
        } catch (e: Exception) {
            // ignore
        }
    }

    // Nueva función para centralizar el inicio de calibración
    private fun startCalibration() {
        isCalibrating = true
        calibrationEars.clear()
        calibrationMars.clear()
        calibrationEndTime = System.currentTimeMillis() + CALIBRATION_MS
        // Actualizar UI en hilo principal
        activity?.runOnUiThread {
            _binding?.let { binding ->
                binding.textHome.text = "Calibrando: mantén ojos abiertos y boca cerrada..."
            }
            programmaticCalibrateButton?.text = "Calibrando..."
            Toast.makeText(requireContext(), "Calibrando durante ${CALIBRATION_MS/1000} s", Toast.LENGTH_SHORT).show()
        }
        // La recolección de muestras ocurre en onFaceResult
    }
}