package com.mrc.warehouse.util

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.mrc.warehouse.api.BalanceItem
import com.mrc.warehouse.api.StorageItem
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mrc.warehouse.R

object PdfExportHelper {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH-mm", Locale.getDefault())

    data class Section(
        val title: String,
        val items: List<BalanceItem>
    )

    fun exportToPdf(
        context: Context,
        storage: StorageItem,
        allBalances: List<BalanceItem>,
        authority: String
    ): File {
        val equipment = allBalances.filter { !it.seriesName.isNullOrBlank() }
        val zip = allBalances.filter { it.seriesName.isNullOrBlank() }

        val sections = listOf(
            Section("Товары", equipment),
            Section("ЗИП", zip)
        )

        val document = PdfDocument()
        val pageWidth = 595  // A4 width in points
        val pageHeight = 842 // A4 height in points
        val marginLeft = 36f
        val marginRight = 36f
        val marginTop = 36f
        val marginBottom = 36f
        val contentWidth = pageWidth - marginLeft - marginRight
        val itemHeight = 40f

        // Paints
        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val headerPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val sectionPaint = Paint().apply {
            color = Color.rgb(0, 105, 217)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val namePaint = Paint().apply {
            color = Color.BLACK
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val detailPaint = Paint().apply {
            color = Color.rgb(100, 100, 100)
            textSize = 9f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        val balancePaint = Paint().apply {
            color = Color.rgb(0, 60, 120)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val linePaint = Paint().apply {
            color = Color.rgb(220, 220, 220)
            strokeWidth = 0.5f
        }

        // Helper to create a new page
        fun createNewPage(): PdfDocument.Page {
            val pageInfo = PdfDocument.PageInfo.Builder(
                pageWidth, pageHeight,
                document.pages.size + 1
            ).create()
            return document.startPage(pageInfo)
        }

        // Draw header on first page
        var currentPage = createNewPage()
        var canvas = currentPage.canvas
        var yOffset = marginTop

        val storageName = storage.name ?: "Склад"
        canvas.drawText("Склад: $storageName", marginLeft, yOffset, titlePaint)
        yOffset += 20f

        val dateStr = dateFormat.format(Date())
        val timeStr = timeFormat.format(Date())
        canvas.drawText("Дата: $dateStr  Время: $timeStr", marginLeft, yOffset, headerPaint)
        yOffset += 12f
        canvas.drawText("Всего записей: ${allBalances.size}", marginLeft, yOffset, headerPaint)
        yOffset += 6f
        canvas.drawLine(marginLeft, yOffset, marginLeft + contentWidth, yOffset, linePaint)
        yOffset += 10f

        for (section in sections) {
            if (section.items.isEmpty()) continue

            // Check if we need a new page for the section header
            if (yOffset + 22f > pageHeight - marginBottom) {
                document.finishPage(currentPage)
                currentPage = createNewPage()
                canvas = currentPage.canvas
                yOffset = marginTop
            }

            canvas.drawText(section.title, marginLeft, yOffset, sectionPaint)
            yOffset += 16f

            for (item in section.items) {
                // Check if we need a new page for this item
                if (yOffset + itemHeight > pageHeight - marginBottom) {
                    document.finishPage(currentPage)
                    currentPage = createNewPage()
                    canvas = currentPage.canvas
                    yOffset = marginTop
                }

                // Draw item row
                val leftColEnd = marginLeft + contentWidth - 70f
                val balanceX = leftColEnd + 15f
                val verticalCenter = yOffset + itemHeight / 2f + 4f

                val name = item.productName ?: "Без названия"
                val series = item.seriesName ?: "—"
                val inventory = item.inventoryNumber ?: "—"

                canvas.drawText(name, marginLeft, yOffset + 14f, namePaint)
                canvas.drawText("Серия: $series", marginLeft, yOffset + 26f, detailPaint)
                canvas.drawText("Инв. номер: $inventory", marginLeft, yOffset + 36f, detailPaint)

                val balanceText = item.balance?.toString() ?: "0"
                val textWidth = balancePaint.measureText(balanceText)
                canvas.drawText(balanceText, balanceX - textWidth / 2f, verticalCenter, balancePaint)

                canvas.drawLine(marginLeft, yOffset + itemHeight - 1f, marginLeft + contentWidth, yOffset + itemHeight - 1f, linePaint)

                yOffset += itemHeight
            }
        }

        document.finishPage(currentPage)
/*
        currentPage = createNewPage()
        canvas = currentPage.canvas
        yOffset = marginTop
        val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.blank_form)
        canvas.drawBitmap(bitmap, null,
            android.graphics.Rect(0, 0, pageWidth, pageHeight),
            null)
// ---------- Наложение текста на бланк ----------
        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 10f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        val boldPaint = Paint().apply {
            color = Color.BLACK
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val smallPaint = Paint().apply {
            color = Color.BLACK
            textSize = 9f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

// 1. Шапка
        canvas.drawText("№ задания", 440f, 31f, boldPaint)   // или ваш номер
        canvas.drawText("№ инцидента", 440f, 60f, smallPaint)

        canvas.drawText("1", 140f, 60f, textPaint)          // число
        canvas.drawText("5", 150f, 60f, textPaint)          // число
        canvas.drawText("05", 190f, 60f, textPaint)         // месяц
        canvas.drawText("2026", 220f, 60f, textPaint)       // год

// 2. КА и время
        canvas.drawText("Колебанов С.В.", 60f, 120f, textPaint)
        canvas.drawText("09:30", 60f, 150f, textPaint)          // время прибытия
        canvas.drawText("2.5", 300f, 150f, textPaint)           // время работы

// 3. № объекта (два поля)
        canvas.drawText("12345", 60f, 185f, textPaint)
        canvas.drawText("67890", 260f, 185f, textPaint)         // второй № объекта

// 4. Адрес
        canvas.drawText("ст-ца Дмитриевская 23", 60f, 215f, textPaint)

// 5. Тип вызова и доп. работы
        canvas.drawText("РВР", 60f, 245f, textPaint)
        canvas.drawText("Нет", 300f, 245f, textPaint)

// 6. Дом, корпус, строение
        canvas.drawText("1", 60f, 275f, textPaint)
        canvas.drawText("А", 150f, 275f, textPaint)
        canvas.drawText("2", 260f, 275f, textPaint)

// ========== Таблица 1 (4 строки, 4 колонки) ==========
        val table1StartY = 310f
        val rowHeight = 28f
        val col1x = 60f   // № заявки/причина
        val col2x = 180f  // тип/марка
        val col3x = 320f  // причина неисправности
        val col4x = 480f  // инв. номер

        for (row in 0..3) {
            val y = table1StartY + row * rowHeight + 18f
            canvas.drawText("Заявка ${row+1}", col1x, y, smallPaint)
            canvas.drawText("Модель ${row+1}", col2x, y, smallPaint)
            canvas.drawText("Проблема ${row+1}", col3x, y, smallPaint)
            canvas.drawText("ИНВ-${row+1}", col4x, y, smallPaint)
        }

// ========== Таблица 2 (перечень работ, 4 строки) ==========
        val table2StartY = 440f
        for (row in 0..3) {
            val y = table2StartY + row * rowHeight + 18f
            canvas.drawText((row+1).toString(), col1x, y, textPaint)          // №
            canvas.drawText("Работа ${row+1}", col2x, y, textPaint)           // наименование
            canvas.drawText("шт", col3x, y, textPaint)                        // ед.изм
            canvas.drawText((row+1).toString(), col4x, y, textPaint)          // кол-во
        }

// ========== Таблица 3 (материалы, 4 строки) ==========
        val table3StartY = 570f
        for (row in 0..3) {
            val y = table3StartY + row * rowHeight + 18f
            canvas.drawText((row+1).toString(), col1x, y, textPaint)
            canvas.drawText("Материал ${row+1}", col2x, y, textPaint)
            canvas.drawText("шт", col3x, y, textPaint)
            canvas.drawText((row+1).toString(), col4x, y, textPaint)
        }

// 7. Работу принял, примечание
        canvas.drawText("Иванов И.И.", 200f, 710f, textPaint)
        canvas.drawText("Замечаний нет", 200f, 740f, textPaint)

// 8. Претензии: крестик в квадрате "да"
        canvas.drawText("X", 220f, 780f, boldPaint)   // напротив "да"
// canvas.drawText("X", 270f, 780f, boldPaint) // если "нет" — раскомментировать

// 9. Блок "ЗАПОЛНЯЕТСЯ КА"
        canvas.drawText("X", 440f, 810f, textPaint)   // обнаружение на гарантии: да
        canvas.drawText("X", 520f, 810f, textPaint)   // гарантийный ремонт: да
        // ----------------------------------------------------
        document.finishPage(currentPage)
*/
        
        // Build filename
        val safeStorageName = (storage.name ?: "склад")
            .replace(Regex("[^a-zA-Zа-яА-Я0-9_\\- ]"), "")
            .replace(" ", "_")
        val pdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val pdfTime = SimpleDateFormat("HH-mm", Locale.getDefault()).format(Date())
        val fileName = "Склад_${safeStorageName}_${pdfDate}_${pdfTime}.pdf"

        val cacheDir = File(context.cacheDir, "pdf_exports")
        cacheDir.mkdirs()
        val file = File(cacheDir, fileName)

        FileOutputStream(file).use { out ->
            document.writeTo(out)
        }
        document.close()

        return file
    }

    fun sharePdf(context: Context, file: File, authority: String) {
        val uri: Uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Отправить PDF"))
    }
}