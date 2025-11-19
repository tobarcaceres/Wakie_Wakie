package cl.frn.wakiewakie.ui.dashboard

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import cl.frn.wakiewakie.DrowsinessLogEntry
import cl.frn.wakiewakie.DrowsinessState
import cl.frn.wakiewakie.R
import java.time.format.DateTimeFormatter

class DrowsinessRecordsAdapter(
    private val context: Context,
    private var records: List<DrowsinessLogEntry>
) : BaseAdapter() {

    private val inflater: LayoutInflater = LayoutInflater.from(context)

    override fun getCount(): Int = records.size

    override fun getItem(position: Int): DrowsinessLogEntry = records[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view: View = convertView ?: inflater.inflate(R.layout.item_drowsiness_record, parent, false)

        val record = records[position]

        val stateIcon = view.findViewById<ImageView>(R.id.iv_state_icon)
        val stateName = view.findViewById<TextView>(R.id.tv_state_name)
        val timestamp = view.findViewById<TextView>(R.id.tv_timestamp)
        val details = view.findViewById<TextView>(R.id.tv_details)
        val severityIndicator = view.findViewById<View>(R.id.v_severity_indicator)

        // Configurar según el estado
        when (record.state) {
            DrowsinessState.ASLEEP -> {
                stateName.text = "Episodio de Somnolencia"
                stateIcon.setImageResource(android.R.drawable.ic_lock_idle_alarm)
                stateIcon.setColorFilter(context.getColor(android.R.color.holo_red_dark))
                severityIndicator.setBackgroundColor(context.getColor(android.R.color.holo_red_dark))
            }
            DrowsinessState.YAWNING -> {
                stateName.text = "Bostezo Detectado"
                stateIcon.setImageResource(android.R.drawable.ic_dialog_alert)
                stateIcon.setColorFilter(context.getColor(android.R.color.holo_orange_dark))
                severityIndicator.setBackgroundColor(context.getColor(android.R.color.holo_orange_dark))
            }
            else -> {
                // No debería llegar aquí ya que filtramos solo ASLEEP y YAWNING
                stateName.text = "Evento Desconocido"
                stateIcon.setImageResource(android.R.drawable.ic_dialog_info)
                stateIcon.setColorFilter(context.getColor(android.R.color.darker_gray))
                severityIndicator.setBackgroundColor(context.getColor(android.R.color.darker_gray))
            }
        }

        // Formatear fecha y hora de manera más amigable
        try {
            val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' HH:mm:ss")
            timestamp.text = record.timestamp.format(formatter)
        } catch (e: Exception) {
            timestamp.text = "Fecha no disponible"
        }

        // Mostrar detalles técnicos
        val marText = record.marLevel?.let { " | MAR: %.3f".format(it) } ?: ""
        details.text = "EAR: %.3f$marText".format(record.earLevel)

        return view
    }

    fun updateRecords(newRecords: List<DrowsinessLogEntry>) {
        records = newRecords
        notifyDataSetChanged()
    }
}
