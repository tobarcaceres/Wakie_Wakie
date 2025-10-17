package cl.frn.wakiewakie.ui.notifications

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import cl.frn.wakiewakie.DrowsinessState
import cl.frn.wakiewakie.DrowsinessLogger
import cl.frn.wakiewakie.databinding.FragmentNotificationsBinding
import kotlinx.coroutines.launch

class NotificationsFragment : Fragment() {

    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!

    private lateinit var notificationsViewModel: NotificationsViewModel
    private var currentStateFilter: DrowsinessState? = null

    // Usar el singleton directamente
    private val drowsinessLogger = DrowsinessLogger.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        notificationsViewModel = ViewModelProvider(this)[NotificationsViewModel::class.java]

        _binding = FragmentNotificationsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // Inicializar el logger si no está inicializado
        drowsinessLogger.initialize(requireContext())

        setupStateFilterSpinner()
        setupButtonListeners()
        observeLogChanges()

        // Actualizar el log inicial
        refreshLog()

        return root
    }

    private fun observeLogChanges() {
        // Observar cambios en el log usando StateFlow
        viewLifecycleOwner.lifecycleScope.launch {
            drowsinessLogger.logEntriesFlow.collect { entries ->
                updateLogDisplay(entries)
            }
        }
    }

    private fun updateLogDisplay(allEntries: List<cl.frn.wakiewakie.DrowsinessLogEntry>) {
        val entries = if (currentStateFilter != null) {
            allEntries.filter { it.state == currentStateFilter }
        } else {
            allEntries
        }

        if (entries.isEmpty()) {
            binding.textLogContent.text = "No hay entradas en el log"
            binding.textLogCount.text = "Entradas en el log: 0"
        } else {
            val logText = entries.joinToString("\n") { entry ->
                entry.toLogString()
            }
            binding.textLogContent.text = logText
            binding.textLogCount.text = "Entradas en el log: ${entries.size}"

            // Scroll al final para mostrar las entradas más recientes
            binding.textLogContent.post {
                val scrollView = binding.textLogContent.parent as? android.widget.ScrollView
                scrollView?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun setupStateFilterSpinner() {
        val stateOptions = mutableListOf<String>().apply {
            add("Todos los estados")
            add("Despierto")
            add("Ojos cerrados")
            add("Bostezando")
            add("Dormido")
        }

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, stateOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerStateFilter.adapter = adapter

        binding.spinnerStateFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentStateFilter = when (position) {
                    0 -> null // Todos los estados
                    1 -> DrowsinessState.AWAKE
                    2 -> DrowsinessState.EYES_CLOSED
                    3 -> DrowsinessState.YAWNING
                    4 -> DrowsinessState.ASLEEP
                    else -> null
                }
                // Actualizar la vista con el filtro actual
                updateLogDisplay(drowsinessLogger.getLogEntries())
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                currentStateFilter = null
                updateLogDisplay(drowsinessLogger.getLogEntries())
            }
        }
    }

    private fun setupButtonListeners() {
        binding.btnRefreshLog.setOnClickListener {
            refreshLog()
        }

        binding.btnClearLog.setOnClickListener {
            clearLog()
        }
    }

    private fun refreshLog() {
        // Simplemente actualizar la vista con los datos actuales
        updateLogDisplay(drowsinessLogger.getLogEntries())
    }

    private fun clearLog() {
        viewLifecycleOwner.lifecycleScope.launch {
            drowsinessLogger.clearLog()
            // El StateFlow se actualizará automáticamente
        }
    }

    override fun onResume() {
        super.onResume()
        // Actualizar el log cuando la vista vuelve a ser visible
        refreshLog()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}