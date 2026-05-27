package com.mrc.warehouse.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.mrc.warehouse.R
import com.mrc.warehouse.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class ReportsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_reports, container, false)


        return view
    }

    private fun generateTestPdf() {
        val report = IncidentReport(
            incidentNumber = "ИНЦ-021543225",
            date = Date(),
            masterName = "Колебанов Сергей Васильевич",
            arrivalTime = "10:00",
            workTime = "1 час 30 мин",
            objectNumber = "19785 31AT",
            address = "ст-ца Дмитриевская, 50 лет ВЛКСМ ул 23",
            callType = "РВР V",
            additionalWork = "",
            faults = listOf(
                FaultItem(
                    requestNumber = "Заявка №123",
                    equipmentType = "ТСД/МРМ",
                    faultReason = "Не включается, не заряжается",
                    inventoryNumber = "INV-001",
                    additionalNote = "ТСД/МРМ на Android не включается/не заряжается/механические проблемы"
                )
            ),
            works = listOf(
                WorkItem(1, "Замена аккумулятора", "шт", 1.0f),
                WorkItem(2, "Прошивка ПО", "усл", 1.0f)
            ),
            materials = listOf(
                MaterialItem(1, "Аккумулятор 3.7V", "шт", 1.0f),
                MaterialItem(2, "Кабель USB", "шт", 1.0f)
            )
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = PdfTemplateExporter.exportIncidentReport(
                    context = requireContext(),
                    report = report,
                    authority = "${requireContext().packageName}.fileprovider"
                )
                withContext(Dispatchers.Main) {
                    PdfTemplateExporter.sharePdf(
                        context = requireContext(),
                        file = file,
                        authority = "${requireContext().packageName}.fileprovider"
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}