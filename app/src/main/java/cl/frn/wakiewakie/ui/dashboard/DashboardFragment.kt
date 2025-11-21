package cl.frn.wakiewakie.ui.dashboard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import cl.frn.wakiewakie.DrowsinessLogger
import cl.frn.wakiewakie.DrowsinessState
import cl.frn.wakiewakie.databinding.FragmentDashboardBinding
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private lateinit var dashboardViewModel: DashboardViewModel
    private val drowsinessLogger = DrowsinessLogger.getInstance()

    // SharedPreferences
    private val PREFS_NAME = "wakie_prefs"
    private val PREF_ALARM_URI = "alarm_uri"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dashboardViewModel =
            ViewModelProvider(this).get(DashboardViewModel::class.java)

        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        val root: View = binding.root

        drowsinessLogger.initialize(requireContext())

        setupButtonListeners()
        observeLogChanges()
        loadStatistics()
        updateCurrentAlarmText()

        // Keep this for permission handling if needed, although usually done in HomeFragment
        binding.btnRequestCamera.setOnClickListener {
            requestCameraPermission()
        }
        return root
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Handle permission result if needed
    }
    
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            if (uri != null) {
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    saveAlarmUri(uri.toString())
                } catch (e: SecurityException) {
                    saveAlarmUri(uri.toString())
                    Toast.makeText(requireContext(), "Nota: El acceso al archivo podría perderse al reiniciar", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Error al procesar archivo: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupButtonListeners() {
        // Replaced refresh listener with alarm selection
        binding.btnSelectAlarm.setOnClickListener {
            showAlarmOptionsDialog()
        }

        binding.btnClearRecords.setOnClickListener {
            clearRecords()
        }
    }
    
    private fun showAlarmOptionsDialog() {
        val options = arrayOf("Default", "Personalizado")
        AlertDialog.Builder(requireContext())
            .setTitle("Seleccionar sonido de alarma")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // Default
                        saveAlarmUri(null)
                        Toast.makeText(requireContext(), "Alarma configurada a Default", Toast.LENGTH_SHORT).show()
                    }
                    1 -> { // Personalizado
                        openFilePicker()
                    }
                }
            }
            .show()
    }
    
    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }
        try {
            filePickerLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "No se pudo abrir el selector de archivos", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun saveAlarmUri(uriString: String?) {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (uriString == null) {
            prefs.edit().remove(PREF_ALARM_URI).apply()
        } else {
            prefs.edit().putString(PREF_ALARM_URI, uriString).apply()
        }
        updateCurrentAlarmText()
    }
    
    private fun updateCurrentAlarmText() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(PREF_ALARM_URI, null)
        
        if (uriString != null) {
            try {
                val uri = Uri.parse(uriString)
                val fileName = getFileName(uri)
                binding.textCurrentAlarm.text = "Sonido actual: $fileName"
            } catch (e: Exception) {
                binding.textCurrentAlarm.text = "Sonido actual: Personalizado"
            }
        } else {
            binding.textCurrentAlarm.text = "Sonido actual: Default"
        }
    }
    
    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            } catch (e: Exception) {
                // ignore
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "Audio personalizado"
    }

    private fun observeLogChanges() {
        viewLifecycleOwner.lifecycleScope.launch {
            drowsinessLogger.logEntriesFlow.collect { entries ->
                updateStatistics(entries)
            }
        }
    }

    private fun updateStatistics(entries: List<cl.frn.wakiewakie.DrowsinessLogEntry>) {
        if (entries.isEmpty()) {
            resetStats()
            return
        }

        var yawnCount = 0
        var sleepCount = 0
        
        // 1. Count discrete events
        entries.forEach { entry ->
            if (entry.state == DrowsinessState.YAWNING) {
                yawnCount++
            } else if (entry.state == DrowsinessState.ASLEEP) {
                sleepCount++
            }
        }

        // 2. Calculate time duration for states
        // Simple approximation: each state lasts until the next log entry
        var totalTimeAwake = 0L
        var totalTimeDrowsy = 0L // Includes eyes closed, yawning, asleep

        for (i in 0 until entries.size - 1) {
            val current = entries[i]
            val next = entries[i+1]
            
            // FIX: Use Duration.between for LocalDateTime
            val duration = try {
                Duration.between(current.timestamp, next.timestamp).toMillis()
            } catch (e: Exception) {
                0L
            }
            
            // Filter out unreasonably long durations (e.g., app closed and reopened hours later)
            if (duration > 1000 * 60 * 60) continue 

            when (current.state) {
                DrowsinessState.AWAKE -> totalTimeAwake += duration
                else -> totalTimeDrowsy += duration
            }
        }
        
        // Add time since last entry to now (if recent)
        val lastEntry = entries.last()
        val now = LocalDateTime.now() // FIX: Use LocalDateTime
        val durationSinceLast = try {
            Duration.between(lastEntry.timestamp, now).toMillis() // FIX: Use Duration.between
        } catch (e: Exception) {
            0L
        }
        
        if (durationSinceLast < 1000 * 60 * 5) { // only if recent (< 5 mins)
             when (lastEntry.state) {
                DrowsinessState.AWAKE -> totalTimeAwake += durationSinceLast
                else -> totalTimeDrowsy += durationSinceLast
            }
        }

        val totalTime = totalTimeAwake + totalTimeDrowsy
        val awakePercent = if (totalTime > 0) (totalTimeAwake * 100 / totalTime).toInt() else 0
        val drowsyPercent = if (totalTime > 0) (totalTimeDrowsy * 100 / totalTime).toInt() else 0

        // Update UI
        binding.tvYawnCount.text = yawnCount.toString()
        binding.tvSleepCount.text = sleepCount.toString()
        
        binding.tvAwakePercent.text = "$awakePercent%"
        binding.progressAwake.progress = awakePercent
        
        binding.tvDrowsyPercent.text = "$drowsyPercent%"
        binding.progressDrowsy.progress = drowsyPercent
    }
    
    private fun resetStats() {
        binding.tvYawnCount.text = "0"
        binding.tvSleepCount.text = "0"
        binding.tvAwakePercent.text = "0%"
        binding.progressAwake.progress = 0
        binding.tvDrowsyPercent.text = "0%"
        binding.progressDrowsy.progress = 0
    }

    private fun loadStatistics() {
        val allEntries = drowsinessLogger.getLogEntries()
        updateStatistics(allEntries)
    }

    private fun clearRecords() {
        viewLifecycleOwner.lifecycleScope.launch {
            drowsinessLogger.clearLog()
            resetStats()
        }
    }

    override fun onResume() {
        super.onResume()
        loadStatistics()
        updateCurrentAlarmText()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}