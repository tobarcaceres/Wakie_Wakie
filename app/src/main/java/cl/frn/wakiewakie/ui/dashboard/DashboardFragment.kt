package cl.frn.wakiewakie.ui.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import cl.frn.wakiewakie.DrowsinessLogger
import cl.frn.wakiewakie.DrowsinessState
import cl.frn.wakiewakie.databinding.FragmentDashboardBinding
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private lateinit var dashboardViewModel: DashboardViewModel
    private lateinit var recordsAdapter: DrowsinessRecordsAdapter

    // Usar el singleton del logger
    private val drowsinessLogger = DrowsinessLogger.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dashboardViewModel =
            ViewModelProvider(this).get(DashboardViewModel::class.java)

        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // Inicializar el logger
        drowsinessLogger.initialize(requireContext())

        setupRecordsList()
        setupButtonListeners()
        observeLogChanges()

        // Cargar datos iniciales
        loadRecords()

        binding.btnRequestCamera.setOnClickListener {
            requestCameraPermission()
        }
        return root
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            binding.textDashboard.text = "Permiso de cámara concedido"
        } else {
            binding.textDashboard.text = "Permiso de cámara denegado"
        }
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            binding.textDashboard.text = "Permiso de cámara ya concedido"
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupRecordsList() {
        recordsAdapter = DrowsinessRecordsAdapter(requireContext(), emptyList())
        binding.lvDrowsinessRecords.adapter = recordsAdapter
    }

    private fun setupButtonListeners() {
        binding.btnRefreshRecords.setOnClickListener {
            loadRecords()
        }

        binding.btnClearRecords.setOnClickListener {
            clearRecords()
        }
    }

    private fun observeLogChanges() {
        // Observar cambios en el log usando StateFlow
        viewLifecycleOwner.lifecycleScope.launch {
            drowsinessLogger.logEntriesFlow.collect { entries ->
                updateRecordsList(entries)
            }
        }
    }

    private fun updateRecordsList(allEntries: List<cl.frn.wakiewakie.DrowsinessLogEntry>) {
        // Filtrar solo los estados ASLEEP y YAWNING
        val filteredEntries = allEntries.filter { entry ->
            entry.state == DrowsinessState.ASLEEP || entry.state == DrowsinessState.YAWNING
        }.reversed() // Mostrar los más recientes primero

        recordsAdapter.updateRecords(filteredEntries)

        // Actualizar contador y visibilidad
        binding.tvRecordsCount.text = "Eventos registrados: ${filteredEntries.size}"

        if (filteredEntries.isEmpty()) {
            binding.lvDrowsinessRecords.visibility = View.GONE
            binding.tvEmptyMessage.visibility = View.VISIBLE
        } else {
            binding.lvDrowsinessRecords.visibility = View.VISIBLE
            binding.tvEmptyMessage.visibility = View.GONE
        }
    }

    private fun loadRecords() {
        val allEntries = drowsinessLogger.getLogEntries()
        updateRecordsList(allEntries)
    }

    private fun clearRecords() {
        viewLifecycleOwner.lifecycleScope.launch {
            drowsinessLogger.clearLog()
            // El StateFlow se actualizará automáticamente
        }
    }

    override fun onResume() {
        super.onResume()
        // Recargar datos cuando la vista vuelve a ser visible
        loadRecords()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}