package com.mrc.warehouse.util

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

// ======================== МОДЕЛИ ДАННЫХ ========================

data class IncidentReport(
    val incidentNumber: String,          // № инцидента
    val date: Date,                      // дата
    val masterName: String,              // КА Колебанов Сергей Васильевич
    val arrivalTime: String,             // "10:00"
    val workTime: String,                // "2:30"
    val objectNumber: String,            // "19785 31AT"
    val address: String,                 // ст-ца Дмитриевская, 50 лет ВЛКСМ ул 23
    val callType: String,                // "РВР V"
    val additionalWork: String,          // "Доп. работы"
    val faults: List<FaultItem>,         // таблица неисправностей
    val works: List<WorkItem>,           // перечень работ
    val materials: List<MaterialItem>    // затраченные материалы
)

data class FaultItem(
    val requestNumber: String,   // № заявки / причина вызова
    val equipmentType: String,   // тип/марка оборудования
    val faultReason: String,     // причина неисправности
    val inventoryNumber: String, // инв. номер
    val additionalNote: String   // последняя колонка (ТСД/МРМ…)
)

data class WorkItem(
    val id: Int,
    val name: String,
    val unit: String,   // ед. изм.
    val quantity: Float
)

data class MaterialItem(
    val id: Int,
    val name: String,
    val unit: String,
    val quantity: Float
)

// ======================== ЭКСПОРТЕР PDF ========================

object PdfTemplateExporter {

    // Размеры страницы A4 в мм -> pt (1 мм = 72/25.4 ≈ 2.8346 pt)
    private const val PAGE_WIDTH = 595f   // A4 width in pt
    private const val PAGE_HEIGHT = 842f  // A4 height in pt
    private const val MM = 2.8346f        // 1 mm in pt

    private val MARGIN_LEFT = 15f * MM
    private val MARGIN_RIGHT = 15f * MM
    private val MARGIN_TOP = 15f * MM
    private val MARGIN_BOTTOM = 15f * MM
    private val PAGE_W = 210f * MM  // A4 width
    private val PAGE_H = 297f * MM  // A4 height (visible area 267mm)
    private val CONTENT_W = 180f * MM  // 180mm

    // Цвета
    private val COLOR_BLACK = Color.BLACK
    private val COLOR_GRAY_LIGHT = Color.rgb(240, 240, 240)
    private val COLOR_GRAY_MEDIUM = Color.rgb(180, 180, 180)

    // Толщина рамки 0.3 мм
    private val BORDER_WIDTH = 0.3f * MM / 2.8346f // ~0.3 pt
    private val BORDER_PAINT = Paint().apply {
        color = COLOR_BLACK
        strokeWidth = 0.5f  // тонкая линия
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val FILL_LIGHT = Paint().apply {
        color = COLOR_GRAY_LIGHT
        style = Paint.Style.FILL
    }

    // Шрифты
    private val fontRegular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val fontBold = Typeface.create("sans-serif", Typeface.BOLD)
    private val fontMono = Typeface.create("monospace", Typeface.NORMAL)

    // Вспомогательные классы
    private data class Pos(val x: Float, val y: Float)
    private data class Col(val label: String, val x: Float, val width: Float)

    // ======================== ПУБЛИЧНЫЙ МЕТОД ========================
    fun exportIncidentReport(context: Context, report: IncidentReport, authority: String): File {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), 1).create()
        var page = document.startPage(pageInfo)
        var canvas = page.canvas
        var y = MARGIN_TOP // в pt

        // 1. Верхняя шапка (15…55 мм)
        y = drawHeader(canvas, report, y)

        // 2. Блок информации о сотруднике и объекте
        y = drawEmployeeInfo(canvas, report, y)

        // 3. Таблица "Сведения о неисправности"
        y = drawFaultTable(canvas, document, page, report.faults, y)

        // 4. Таблица "Перечень проведенных работ"
        y = drawWorkTable(canvas, document, page, report.works, y)

        // 5. Таблица "Затраченные материалы и запчасти"
        y = drawMaterialsTable(canvas, document, page, report.materials, y)

        // 6. Нижняя часть отчёта
        y = drawFooter(canvas, y)

        document.finishPage(page)

        // Сохраняем файл
        val safeName = "Акт_${report.incidentNumber}_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.pdf"
        val cacheDir = File(context.cacheDir, "pdf_exports")
        cacheDir.mkdirs()
        val file = File(cacheDir, safeName)
        FileOutputStream(file).use { document.writeTo(it) }

        document.close()
        return file
    }

    fun sharePdf(context: Context, file: File, authority: String) {
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Отправить PDF"))
    }

    // ======================== 1. ВЕРХНЯЯ ШАПКА ========================
    private fun drawHeader(canvas: Canvas, report: IncidentReport, startY: Float): Float {
        // 1.1 Заголовок "ОТЧЕТ О ВЫПОЛНЕННЫХ РАБОТАХ №" - центр, X=105мм, Y=20мм
        val titlePaint = Paint().apply {
            typeface = fontBold
            textSize = 16f * MM
            color = COLOR_BLACK
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val centerX = 105f * MM
        canvas.drawText("ОТЧЕТ О ВЫПОЛНЕННЫХ РАБОТАХ №", centerX, 20f * MM, titlePaint)

        // 1.2 "№ задания ХК-001835"
        val normal12 = Paint().apply {
            typeface = fontRegular
            textSize = 12f * MM
            color = COLOR_BLACK
            isAntiAlias = true
        }
        canvas.drawText("№ задания ${report.incidentNumber}", MARGIN_LEFT, 32f * MM, normal12)

        // 1.3 Дата моноширинная справа
        val datePaint = Paint().apply {
            typeface = fontMono
            textSize = 11f * MM
            color = COLOR_BLACK
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        val dateStr = SimpleDateFormat("dd MM yyyy", Locale.getDefault()).format(report.date)
        // Форматируем дату как "1 3 0 3 2 0 2 6" с пробелами между цифрами
        val spacedDate = dateStr.replace(" ", "  ").toCharArray().joinToString(" ")
        canvas.drawText(spacedDate, 160f * MM, 32f * MM, datePaint)

        return 40f * MM
    }

    // ======================== 2. БЛОК ИНФОРМАЦИИ ========================
    private fun drawEmployeeInfo(canvas: Canvas, report: IncidentReport, startY: Float): Float {
        var y = startY  // 40mm

        // 2.1 ФИО
        val bold11 = Paint().apply {
            typeface = fontBold
            textSize = 11f * MM
            color = COLOR_BLACK
            isAntiAlias = true
        }
        canvas.drawText("КА ${report.masterName}", MARGIN_LEFT, y + 8f * MM, bold11)
        y += 10f * MM  // 50mm

        // 2.2 Время прибытия и время работы - две колонки на одной линии
        val label9 = Paint().apply {
            typeface = fontRegular
            textSize = 9f * MM
            color = COLOR_BLACK
            isAntiAlias = true
        }
        val underlinePaint = Paint().apply {
            color = COLOR_BLACK
            strokeWidth = 0.3f * MM / 2.8346f
        }

        canvas.drawText("Время прибытия на объект", MARGIN_LEFT, y + 8f * MM, label9)
        canvas.drawLine(MARGIN_LEFT, y + 20f * MM, MARGIN_LEFT + 40f * MM, y + 20f * MM, underlinePaint)

        canvas.drawText("Время, затраченное на работу", 90f * MM, y + 8f * MM, label9)
        canvas.drawLine(90f * MM, y + 20f * MM, 90f * MM + 40f * MM, y + 20f * MM, underlinePaint)

        y += 24f * MM  // 74mm

        // 2.3 № объекта и Адрес
        canvas.drawText("№ объекта", MARGIN_LEFT, y + 8f * MM, label9)
        // Объект "19785 31AT" - разбиваем на символы через пробел
        val objText = report.objectNumber.replace(" ", "  ").toCharArray().joinToString(" ")
        val objVal = Paint().apply {
            typeface = fontMono
            textSize = 11f * MM
            color = COLOR_BLACK
            isAntiAlias = true
        }
        canvas.drawText(objText, MARGIN_LEFT, y + 18f * MM, objVal)
        canvas.drawLine(MARGIN_LEFT, y + 20f * MM, MARGIN_LEFT + 30f * MM, y + 20f * MM, underlinePaint)

        canvas.drawText("Адрес объекта", 80f * MM, y + 8f * MM, label9)
        val addrVal = Paint().apply {
            typeface = fontRegular
            textSize = 9f * MM
            color = COLOR_BLACK
            isAntiAlias = true
        }
        canvas.drawText(report.address, 80f * MM, y + 18f * MM, addrVal)
        canvas.drawLine(80f * MM, y + 20f * MM, 80f * MM + 100f * MM, y + 20f * MM, underlinePaint)

        y += 24f * MM  // 98mm

        // 2.4 Дом / Корпус / Строение
        canvas.drawText("Дом", MARGIN_LEFT, y + 8f * MM, label9)
        canvas.drawLine(MARGIN_LEFT, y + 12f * MM, MARGIN_LEFT + 35f * MM, y + 12f * MM, underlinePaint)

        canvas.drawText("Корпус", 65f * MM, y + 8f * MM, label9)
        canvas.drawLine(65f * MM, y + 12f * MM, 65f * MM + 35f * MM, y + 12f * MM, underlinePaint)

        canvas.drawText("Строение", 115f * MM, y + 8f * MM, label9)
        canvas.drawLine(115f * MM, y + 12f * MM, 115f * MM + 35f * MM, y + 12f * MM, underlinePaint)

        return 115f * MM
    }

    // ======================== ОТРИСОВКА ТАБЛИЦЫ С ЗАГОЛОВКОМ ========================
    private data class TableDef(
        val title: String,
        val cols: List<Col>,
        val yStart: Float
    )

    private fun drawTableHeader(canvas: Canvas, cols: List<Col>, y: Float, rowH: Float) {
        // Заливка
        canvas.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + CONTENT_W, y + rowH, FILL_LIGHT)
        // Текст заголовков
        val headerPaint = Paint().apply {
            typeface = fontBold
            textSize = 9f * MM
            color = COLOR_BLACK
            isAntiAlias = true
        }
        for (col in cols) {
            canvas.drawText(col.label, col.x + 2f * MM, y + rowH - 4f * MM, headerPaint)
        }
        // Рамка заголовка
        drawTableBorder(canvas, cols, y, rowH)
    }

    private fun drawTableBorder(canvas: Canvas, cols: List<Col>, y: Float, rowH: Float) {
        val line = Paint().apply {
            color = COLOR_BLACK
            strokeWidth = 0.5f
            style = Paint.Style.STROKE
        }
        // Внешняя рамка
        canvas.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + CONTENT_W, y + rowH, line)
        // Вертикальные разделители
        for (col in cols.dropLast(1)) {
            val x = col.x + col.width
            canvas.drawLine(x, y, x, y + rowH, line)
        }
    }

    private fun drawDataRow(canvas: Canvas, cols: List<Col>, values: List<String>, y: Float, rowH: Float) {
        val line = Paint().apply {
            color = COLOR_BLACK
            strokeWidth = 0.5f
            style = Paint.Style.STROKE
        }
        val cellPaint = Paint().apply {
            typeface = fontRegular
            textSize = 9f * MM
            color = COLOR_BLACK
            isAntiAlias = true
        }

        // Внешняя рамка
        canvas.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + CONTENT_W, y + rowH, line)
        // Вертикальные разделители
        for (col in cols.dropLast(1)) {
            val x = col.x + col.width
            canvas.drawLine(x, y, x, y + rowH, line)
        }

        // Текст ячеек с переносом
        for ((i, col) in cols.withIndex()) {
            val text = values.getOrElse(i) { "" }
            if (text.isNotBlank()) {
                val availWidth = col.width - 4f * MM
                val lines = splitText(text, availWidth, cellPaint)
                var textY = y + cellPaint.textSize + 2f * MM
                for (lineText in lines) {
                    if (textY + cellPaint.textSize > y + rowH - 2f * MM) break
                    canvas.drawText(lineText, col.x + 2f * MM, textY, cellPaint)
                    textY += cellPaint.textSize + 1f * MM
                }
            }
        }
    }

    // Адаптивная высота строки под перенос текста
    private fun calcRowHeight(cols: List<Col>, values: List<String>): Float {
        val paint = Paint().apply {
            typeface = fontRegular
            textSize = 9f * MM
            isAntiAlias = true
        }
        var maxLines = 1
        for ((i, col) in cols.withIndex()) {
            val text = values.getOrElse(i) { "" }
            val availWidth = col.width - 4f * MM
            val lines = splitText(text, availWidth, paint).size
            if (lines > maxLines) maxLines = lines
        }
        return maxLines * (paint.textSize + 1f * MM) + 4f * MM
    }

    private fun drawTableBorderFull(canvas: Canvas, cols: List<Col>, y: Float, rowH: Float) {
        val line = Paint().apply {
            color = COLOR_BLACK
            strokeWidth = 0.5f
            style = Paint.Style.STROKE
        }
        canvas.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + CONTENT_W, y + rowH, line)
        for (col in cols.dropLast(1)) {
            val x = col.x + col.width
            canvas.drawLine(x, y, x, y + rowH, line)
        }
    }

    // ======================== 3. ТАБЛИЦА НЕИСПРАВНОСТЕЙ ========================
    private fun drawFaultTable(canvas: Canvas, document: PdfDocument, page: PdfDocument.Page,
                               faults: List<FaultItem>, startY: Float): Float {
        var currentPage = page
        var c = canvas
        var y = startY  // 115mm

        // Заголовок таблицы (рамка, светло-серая заливка)
        val titlePaint = Paint().apply {
            typeface = fontBold
            textSize = 9f * MM
            color = COLOR_BLACK
            isAntiAlias = true
        }
        val rowH = 12f * MM

        // Рамка + заливка для заголовка
        c.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + CONTENT_W, y + rowH, FILL_LIGHT)
        val cols = listOf(
            Col("№ заявки / причина вызова", MARGIN_LEFT, 60f * MM),
            Col("Тип / марка оборудования", MARGIN_LEFT + 60f * MM, 45f * MM),
            Col("Причина неисправности", MARGIN_LEFT + 105f * MM, 55f * MM),
            Col("Инвентарный номер", MARGIN_LEFT + 160f * MM, 35f * MM)
        )
        // Текст заголовка
        for (col in cols) {
            c.drawText(col.label, col.x + 2f * MM, y + rowH - 4f * MM, titlePaint)
        }
        // Рамка заголовка
        drawTableBorderFull(c, cols, y, rowH)
        y += rowH  // 127mm

        // Строки данных
        for (fault in faults) {
            val values = listOf(
                fault.requestNumber,
                fault.equipmentType,
                fault.faultReason,
                fault.inventoryNumber
            )
            val rowHData = calcRowHeight(cols, values).coerceAtLeast(12f * MM)
            if (y + rowHData > PAGE_H - MARGIN_BOTTOM) {
                document.finishPage(currentPage)
                currentPage = document.startPage(
                    PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), document.pages.size + 1).create()
                )
                c = currentPage.canvas
                y = MARGIN_TOP
                // Повторяем заголовок на новой странице
                c.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + CONTENT_W, y + rowH, FILL_LIGHT)
                for (col in cols) {
                    c.drawText(col.label, col.x + 2f * MM, y + rowH - 4f * MM, titlePaint)
                }
                drawTableBorderFull(c, cols, y, rowH)
                y += rowH
            }
            drawDataRow(c, cols, values, y, rowHData)
            y += rowHData
        }
        y += 5f * MM
        return y
    }

    // ======================== 4. ТАБЛИЦА РАБОТ ========================
    private fun drawWorkTable(canvas: Canvas, document: PdfDocument, page: PdfDocument.Page,
                              works: List<WorkItem>, startY: Float): Float {
        var currentPage = page
        var c = canvas
        var y = startY

        val titlePaint = Paint().apply {
            typeface = fontBold
            textSize = 9f * MM
            color = COLOR_BLACK
            isAntiAlias = true
        }
        val rowH = 12f * MM

        val cols = listOf(
            Col("№ в тарификаторе", MARGIN_LEFT, 25f * MM),
            Col("Перечень проведенных работ", MARGIN_LEFT + 25f * MM, 105f * MM),
            Col("Ед. изм.", MARGIN_LEFT + 130f * MM, 25f * MM),
            Col("Кол-во", MARGIN_LEFT + 155f * MM, 25f * MM)
        )

        // Заголовок
        c.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + CONTENT_W, y + rowH, FILL_LIGHT)
        for (col in cols) {
            c.drawText(col.label, col.x + 2f * MM, y + rowH - 4f * MM, titlePaint)
        }
        drawTableBorderFull(c, cols, y, rowH)
        y += rowH

        // Строки данных
        for (work in works) {
            val values = listOf(work.id.toString(), work.name, work.unit, work.quantity.toString())
            val rowHData = calcRowHeight(cols, values).coerceAtLeast(12f * MM)
            if (y + rowHData > PAGE_H - MARGIN_BOTTOM) {
                document.finishPage(currentPage)
                currentPage = document.startPage(
                    PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), document.pages.size + 1).create()
                )
                c = currentPage.canvas
                y = MARGIN_TOP
                c.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + CONTENT_W, y + rowH, FILL_LIGHT)
                for (col in cols) {
                    c.drawText(col.label, col.x + 2f * MM, y + rowH - 4f * MM, titlePaint)
                }
                drawTableBorderFull(c, cols, y, rowH)
                y += rowH
            }
            drawDataRow(c, cols, values, y, rowHData)
            y += rowHData
        }
        y += 5f * MM
        return y
    }

    // ======================== 5. ТАБЛИЦА МАТЕРИАЛОВ ========================
    private fun drawMaterialsTable(canvas: Canvas, document: PdfDocument, page: PdfDocument.Page,
                                    materials: List<MaterialItem>, startY: Float): Float {
        var currentPage = page
        var c = canvas
        var y = startY

        val titlePaint = Paint().apply {
            typeface = fontBold
            textSize = 9f * MM
            color = COLOR_BLACK
            isAntiAlias = true
        }
        val rowH = 12f * MM

        val cols = listOf(
            Col("№ в тарификаторе", MARGIN_LEFT, 25f * MM),
            Col("Затраченные материалы и запчасти", MARGIN_LEFT + 25f * MM, 105f * MM),
            Col("Ед. изм.", MARGIN_LEFT + 130f * MM, 25f * MM),
            Col("Кол-во", MARGIN_LEFT + 155f * MM, 25f * MM)
        )

        c.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + CONTENT_W, y + rowH, FILL_LIGHT)
        for (col in cols) {
            c.drawText(col.label, col.x + 2f * MM, y + rowH - 4f * MM, titlePaint)
        }
        drawTableBorderFull(c, cols, y, rowH)
        y += rowH

        for (mat in materials) {
            val values = listOf(mat.id.toString(), mat.name, mat.unit, mat.quantity.toString())
            val rowHData = calcRowHeight(cols, values).coerceAtLeast(12f * MM)
            if (y + rowHData > PAGE_H - MARGIN_BOTTOM) {
                document.finishPage(currentPage)
                currentPage = document.startPage(
                    PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), document.pages.size + 1).create()
                )
                c = currentPage.canvas
                y = MARGIN_TOP
                c.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + CONTENT_W, y + rowH, FILL_LIGHT)
                for (col in cols) {
                    c.drawText(col.label, col.x + 2f * MM, y + rowH - 4f * MM, titlePaint)
                }
                drawTableBorderFull(c, cols, y, rowH)
                y += rowH
            }
            drawDataRow(c, cols, values, y, rowHData)
            y += rowHData
        }
        y += 5f * MM
        return y
    }

    // ======================== 6. НИЖНЯЯ ЧАСТЬ ========================
    private fun drawFooter(canvas: Canvas, startY: Float): Float {
        var y = startY

        val labelPaint = Paint().apply {
            typeface = fontRegular
            textSize = 9f * MM
            color = COLOR_BLACK
            isAntiAlias = true
        }
        val linePaint = Paint().apply {
            color = COLOR_BLACK
            strokeWidth = 0.5f
        }
        val smallPaint = Paint().apply {
            typeface = fontRegular
            textSize = 8f * MM
            color = COLOR_GRAY_MEDIUM
            isAntiAlias = true
        }

        // 6.1 Работу принял
        canvas.drawText("Работу принял:", MARGIN_LEFT, y, labelPaint)
        canvas.drawLine(45f * MM, y + 2f * MM, 140f * MM, y + 2f * MM, linePaint)
        canvas.drawText("(ФИО/должность)", 60f * MM, y + 8f * MM, smallPaint)
        y += 20f * MM

        // 6.2 Примечание
        canvas.drawText("Примечание:", MARGIN_LEFT, y, labelPaint)
        y += 4f * MM
        val noteRect = RectF(MARGIN_LEFT, y, MARGIN_LEFT + 165f * MM, y + 25f * MM)
        canvas.drawRect(noteRect, BORDER_PAINT)
        y += 30f * MM

        // 6.3 Претензии к выполненным работам
        canvas.drawText("Претензии к выполненным работам", MARGIN_LEFT, y, labelPaint)
        canvas.drawLine(80f * MM, y, 80f * MM + 40f * MM, y, linePaint)

        // Чекбокс "да"
        val cbPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.5f; color = COLOR_BLACK }
        canvas.drawRect(140f * MM, y - 4f * MM, 140f * MM + 5f * MM, y + 1f * MM, cbPaint)
        canvas.drawText("да", 147f * MM, y + 1f * MM, labelPaint)

        // Чекбокс "нет"
        canvas.drawRect(165f * MM, y - 4f * MM, 165f * MM + 5f * MM, y + 1f * MM, cbPaint)
        canvas.drawText("нет", 172f * MM, y + 1f * MM, labelPaint)
        y += 16f * MM

        // 6.4 ЗАПОЛНЯЕТСЯ КА
        val boldUnderline = Paint().apply {
            typeface = fontBold
            textSize = 10f * MM
            color = COLOR_BLACK
            isAntiAlias = true
        }
        canvas.drawText("ЗАПОЛНЯЕТСЯ КА", MARGIN_LEFT, y, boldUnderline)
        y += 8f * MM

        // Оборудование на гарантии
        canvas.drawText("Оборудование на гарантии:", MARGIN_LEFT, y, labelPaint)
        canvas.drawRect(65f * MM, y - 4f * MM, 65f * MM + 5f * MM, y + 1f * MM, cbPaint)
        canvas.drawText("да", 72f * MM, y + 1f * MM, labelPaint)
        canvas.drawRect(90f * MM, y - 4f * MM, 90f * MM + 5f * MM, y + 1f * MM, cbPaint)
        canvas.drawText("нет", 97f * MM, y + 1f * MM, labelPaint)
        y += 10f * MM

        // Гарантийный ремонт
        canvas.drawText("Гарантийный ремонт:", MARGIN_LEFT, y, labelPaint)
        canvas.drawRect(65f * MM, y - 4f * MM, 65f * MM + 5f * MM, y + 1f * MM, cbPaint)
        canvas.drawText("да", 72f * MM, y + 1f * MM, labelPaint)
        canvas.drawRect(90f * MM, y - 4f * MM, 90f * MM + 5f * MM, y + 1f * MM, cbPaint)
        canvas.drawText("нет", 97f * MM, y + 1f * MM, labelPaint)

        return y + 10f * MM
    }

    // ======================== ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ========================
    private fun splitText(text: String, maxWidth: Float, paint: Paint): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()
        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) <= maxWidth) {
                currentLine = StringBuilder(testLine)
            } else {
                if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
        return if (lines.isEmpty()) listOf(text) else lines
    }
}