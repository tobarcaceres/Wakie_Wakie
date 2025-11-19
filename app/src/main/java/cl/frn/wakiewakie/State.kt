package cl.frn.wakiewakie

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken

enum class DrowsinessState {
    AWAKE,
    EYES_CLOSED,
    YAWNING,
    ASLEEP
}

data class DrowsinessLogEntry(
    val timestamp: LocalDateTime,
    val state: DrowsinessState,
    val earLevel: Float,
    val marLevel: Float? = null
) {
    fun toLogString(): String {
        return try {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            val marText = marLevel?.let { " | MAR: %.3f".format(it) } ?: ""
            "${timestamp.format(formatter)} | Estado: $state | EAR: %.3f$marText".format(earLevel)
        } catch (e: Exception) {
            // Fallback si hay error con el timestamp
            val marText = marLevel?.let { " | MAR: %.3f".format(it) } ?: ""
            "${System.currentTimeMillis()} | Estado: $state | EAR: %.3f$marText".format(earLevel)
        }
    }
}

class DrowsinessLogger private constructor() {
    companion object {
        @Volatile
        private var INSTANCE: DrowsinessLogger? = null
        private const val MAX_LOG_ENTRIES = 1000
        private const val PREFS_NAME = "drowsiness_log_prefs"
        private const val LOG_ENTRIES_KEY = "log_entries"

        fun getInstance(): DrowsinessLogger {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DrowsinessLogger().also { INSTANCE = it }
            }
        }
    }

    private val mutex = Mutex()
    private val logEntries = ConcurrentLinkedQueue<DrowsinessLogEntry>()
    private var lastLoggedState: DrowsinessState? = null
    private var context: Context? = null

    // Gson con deserializador personalizado para LocalDateTime
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(LocalDateTime::class.java, JsonSerializer<LocalDateTime> { src, _, _ ->
            com.google.gson.JsonPrimitive(src.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        })
        .registerTypeAdapter(LocalDateTime::class.java, JsonDeserializer<LocalDateTime> { json, _, _ ->
            try {
                LocalDateTime.parse(json.asString, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            } catch (e: Exception) {
                // Fallback: crear un timestamp actual si no se puede parsear
                LocalDateTime.now()
            }
        })
        .create()

    // StateFlow para observar cambios en el log
    private val _logEntriesFlow = MutableStateFlow<List<DrowsinessLogEntry>>(emptyList())
    val logEntriesFlow: StateFlow<List<DrowsinessLogEntry>> = _logEntriesFlow.asStateFlow()

    // StateFlow para el estado actual
    private val _currentStateFlow = MutableStateFlow(DrowsinessState.AWAKE)
    val currentStateFlow: StateFlow<DrowsinessState> = _currentStateFlow.asStateFlow()

    fun initialize(context: Context) {
        this.context = context.applicationContext
        loadLogFromPreferences()
    }

    suspend fun logStateChange(state: DrowsinessState, earLevel: Float, marLevel: Float? = null) {
        mutex.withLock {
            // Solo registrar cambios de estado para estados que no son AWAKE
            // o cuando cambiamos de un estado no-AWAKE a AWAKE
            if (state != DrowsinessState.AWAKE || (lastLoggedState != null && lastLoggedState != DrowsinessState.AWAKE)) {
                val entry = DrowsinessLogEntry(
                    timestamp = LocalDateTime.now(),
                    state = state,
                    earLevel = earLevel,
                    marLevel = marLevel
                )

                logEntries.offer(entry)
                lastLoggedState = state

                // Mantener solo las últimas MAX_LOG_ENTRIES entradas
                while (logEntries.size > MAX_LOG_ENTRIES) {
                    logEntries.poll()
                }

                // Actualizar StateFlow
                _logEntriesFlow.value = logEntries.toList()
                _currentStateFlow.value = state

                // Guardar en SharedPreferences de forma asíncrona
                saveLogToPreferences()
            }
        }
    }

    fun logStateChangeSync(state: DrowsinessState, earLevel: Float, marLevel: Float? = null) {
        // Versión síncrona para usar desde el hilo principal
        if (state != DrowsinessState.AWAKE || (lastLoggedState != null && lastLoggedState != DrowsinessState.AWAKE)) {
            val entry = DrowsinessLogEntry(
                timestamp = LocalDateTime.now(),
                state = state,
                earLevel = earLevel,
                marLevel = marLevel
            )

            logEntries.offer(entry)
            lastLoggedState = state

            while (logEntries.size > MAX_LOG_ENTRIES) {
                logEntries.poll()
            }

            _logEntriesFlow.value = logEntries.toList()
            _currentStateFlow.value = state

            saveLogToPreferences()
        }
    }

    fun getLogEntries(): List<DrowsinessLogEntry> = logEntries.toList()

    fun getFormattedLog(): String {
        return logEntries.joinToString("\n") { entry ->
            try {
                entry.toLogString()
            } catch (e: Exception) {
                "Error formatting entry: ${entry.state} | EAR: ${entry.earLevel}"
            }
        }
    }

    suspend fun clearLog() {
        mutex.withLock {
            logEntries.clear()
            lastLoggedState = null
            _logEntriesFlow.value = emptyList()
            _currentStateFlow.value = DrowsinessState.AWAKE
            clearLogFromPreferences()
        }
    }

    fun clearLogSync() {
        logEntries.clear()
        lastLoggedState = null
        _logEntriesFlow.value = emptyList()
        _currentStateFlow.value = DrowsinessState.AWAKE
        clearLogFromPreferences()
    }

    fun getEntriesForState(state: DrowsinessState): List<DrowsinessLogEntry> {
        return logEntries.filter { it.state == state }
    }

    fun getEntriesInTimeRange(startTime: LocalDateTime, endTime: LocalDateTime): List<DrowsinessLogEntry> {
        return logEntries.filter { entry ->
            try {
                entry.timestamp.isAfter(startTime) && entry.timestamp.isBefore(endTime)
            } catch (e: Exception) {
                false // Si hay error con el timestamp, excluir la entrada
            }
        }
    }

    fun getCurrentState(): DrowsinessState = _currentStateFlow.value

    private fun saveLogToPreferences() {
        context?.let { ctx ->
            try {
                val prefs: SharedPreferences = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val entriesList = logEntries.toList()
                val json = gson.toJson(entriesList)
                prefs.edit().putString(LOG_ENTRIES_KEY, json).apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadLogFromPreferences() {
        context?.let { ctx ->
            try {
                val prefs: SharedPreferences = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val json = prefs.getString(LOG_ENTRIES_KEY, null)
                if (json != null) {
                    val type = object : TypeToken<List<DrowsinessLogEntry>>() {}.type
                    val entries: List<DrowsinessLogEntry> = gson.fromJson(json, type) ?: emptyList()

                    logEntries.clear()
                    // Filtrar entradas con timestamps válidos
                    val validEntries = entries.filter { entry ->
                        try {
                            entry.timestamp != null
                        } catch (e: Exception) {
                            false
                        }
                    }
                    logEntries.addAll(validEntries)
                    _logEntriesFlow.value = logEntries.toList()

                    // Restaurar último estado
                    lastLoggedState = validEntries.lastOrNull()?.state
                    _currentStateFlow.value = lastLoggedState ?: DrowsinessState.AWAKE
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Si hay error cargando, empezar con log limpio
                logEntries.clear()
                _logEntriesFlow.value = emptyList()
                _currentStateFlow.value = DrowsinessState.AWAKE
            }
        }
    }

    private fun clearLogFromPreferences() {
        context?.let { ctx ->
            val prefs: SharedPreferences = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(LOG_ENTRIES_KEY).apply()
        }
    }
}
