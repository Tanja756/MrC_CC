package com.mrc.warehouse.util

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.mrc.warehouse.api.TaskItem
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object TasksReportExporter {

    // A4
    private const val PAGE_W = 595f
    private const val PAGE_H = 842f
    private const val MM = 2.8346f

    // Поля страницы
    private val ML = 30f          // margin left  (30pt ≈ 10.6мм)
    private val MT = 30f          // margin top
    private val MB = 40f          // margin bottom (подвал)
    private val CW = PAGE_W - ML - 30f   // content width ≈ 535pt

    // Цвета
    private val C_PRIMARY    = Color.rgb(33, 81, 146)
    private val C_PRIM_LIGHT = Color.rgb(66, 122, 200)
    private val C_ACCENT     = Color.rgb(242, 146, 34)
    private val C_HDR_BG     = Color.rgb(33, 81, 146)
    private val C_HDR_TEXT   = Color.WHITE
    private val C_EVEN       = Color.rgb(240, 246, 253)
    private val C_ODD        = Color.WHITE
    private val C_URGENT     = Color.rgb(255, 235, 235)
    private val C_WARN       = Color.rgb(255, 248, 225)
    private val C_TEXT       = Color.rgb(51, 51, 51)
    private val C_TEXT2      = Color.rgb(130, 130, 130)
    private val C_BORDER     = Color.rgb(200, 210, 220)
    private val C_RED        = Color.rgb(220, 38, 38)
    private val C_YELLOW     = Color.rgb(245, 158, 11)

    private val fRegular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val fBold    = Typeface.create("sans-serif", Typeface.BOLD)

    private val dateFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru"))
    private val dateOnly = SimpleDateFormat("dd.MM.yyyy", Locale("ru"))

    private data class Col(
        val label: String,
        val x: Float,
        val w: Float,
        val align: Paint.Align = Paint.Align.LEFT
    )
    private data class TRes(val page: PdfDocument.Page, val y: Float, val pn: Int)

    // ====================================================================
    //  ПУБЛИЧНЫЕ МЕТОДЫ
    // ====================================================================

    fun exportTasksReport(
        context: Context,
        tasks: List<TaskItem>,
        clients: Map<String, String>,
        priorities: Map<Int, String>,
        authority: String
    ): File {
        val doc = PdfDocument()
        val info = PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), 1).create()
        var pg = doc.startPage(info)
        var cv = pg.canvas
        var y = MT
        var pn = 1

        y = drawHeader(cv, tasks.size, y)
        y += 8f

        val r = drawTable(cv, doc, pg, tasks, clients, priorities, y, pn)
        pg = r.page
        cv = pg.canvas
        y = r.y
        pn = r.pn

        drawFooter(cv, pn)
        doc.finishPage(pg)

        val fname = "Заявки_в_работе_${SimpleDateFormat("yyyyMMdd_HHmm", Locale("ru")).format(Date())}.pdf"
        val dir = File(context.cacheDir, "pdf_exports").also { it.mkdirs() }
        val file = File(dir, fname)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun sharePdf(ctx: Context, file: File, authority: String) {
        val uri = FileProvider.getUriForFile(ctx, authority, file)
        ctx.startActivity(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    // ====================================================================
    //  ЗАГОЛОВОК
    // ====================================================================

    private fun drawHeader(cv: Canvas, cnt: Int, sy: Float): Float {
        var y = sy

        // оранжевая полоса
        Paint().apply {
            color = C_ACCENT; style = Paint.Style.FILL
        }.let { cv.drawRect(0f, y, PAGE_W, y + 6f, it) }
        y += 14f

        // название
        Paint().apply {
            typeface = fBold; textSize = 18f; color = C_PRIMARY; isAntiAlias = true
        }.let { cv.drawText("Отчет по заявкам в работе", ML, y, it) }
        y += 22f

        // подзаголовок
        val now = Date()
        Paint().apply {
            typeface = fRegular; textSize = 10f; color = C_TEXT2; isAntiAlias = true
        }.let { p ->
            cv.drawText("Сформирован: ${dateFmt.format(now)}", ML, y, p)
            cv.drawText("Всего заявок: $cnt", ML + 280f, y, p)
        }
        y += 8f

        // линия
        Paint().apply { color = C_PRIM_LIGHT; strokeWidth = 1f }
            .let { cv.drawLine(ML, y, ML + CW, y, it) }
        y += 10f
        return y
    }

    // ====================================================================
    //  ТАБЛИЦА
    // ====================================================================

    private fun drawTable(
        cv: Canvas, doc: PdfDocument, pg: PdfDocument.Page,
        tasks: List<TaskItem>,
        clients: Map<String, String>,
        priorities: Map<Int, String>,
        sy: Float, spn: Int
    ): TRes {
        var curPg = pg
        var c = cv
        var y = sy
        var pn = spn
        val hh = 22f   // header height

        // колонки (ширина всей таблицы CW = ~535pt)
        val cols = listOf(
            Col("№",     ML,        24f,   Paint.Align.CENTER),
            Col("Номер", ML + 24f,  60f),
            Col("Название заявки", ML + 84f, 170f),
            Col("Клиент", ML + 254f, 100f),
            Col("Приор.", ML + 354f, 52f, Paint.Align.CENTER),
            Col("Срок",  ML + 406f, 70f,  Paint.Align.CENTER),
            Col("Статус", ML + 476f, 59f, Paint.Align.CENTER)
        )

        tableHeader(c, cols, y, hh)
        y += hh

        var idx = 0
        for (t in tasks) {
            idx++
            val vals = listOf(
                idx.toString(),
                t.number ?: "—",
                t.name ?: "Без названия",
                clients[t.guidClient] ?: t.guidClient ?: "—",
                priorities[t.priority] ?: t.priority?.toString() ?: "—",
                fmtDate(t.period),
                t.status ?: "—"
            )
            val rh = calcH(cols, vals).coerceAtLeast(18f)

            if (y + rh > PAGE_H - MB - 15f) {
                drawFooter(c, pn)
                doc.finishPage(curPg)
                pn++
                curPg = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), pn).create())
                c = curPg.canvas
                y = MT
                tableHeader(c, cols, y, hh)
                y += hh
            }

            val urg = urgency(t.period)
            val bg = when {
                urg >= 2 -> C_URGENT
                urg == 1 -> C_WARN
                idx % 2 == 0 -> C_EVEN
                else -> C_ODD
            }
            drawRow(c, cols, vals, y, rh, bg, urg)
            y += rh
        }

        y += 6f
        Paint().apply {
            typeface = fBold; textSize = 11f; color = C_PRIMARY; isAntiAlias = true
        }.let { c.drawText("Итого: $idx заявок", ML, y, it) }

        return TRes(curPg, y, pn)
    }

    // ====================================================================
    //  ЗАГОЛОВОК ТАБЛИЦЫ (скруглённый)
    // ====================================================================

    private fun tableHeader(cv: Canvas, cols: List<Col>, y: Float, h: Float) {
        Paint().apply { color = C_HDR_BG; style = Paint.Style.FILL }
            .let { cv.drawRoundRect(ML, y, ML + CW, y + h, 4f, 4f, it) }

        val p = Paint().apply {
            typeface = fBold; textSize = 9f; color = C_HDR_TEXT; isAntiAlias = true
        }
        for (c in cols) {
            val tx = when (c.align) {
                Paint.Align.CENTER -> c.x + c.w / 2f
                Paint.Align.RIGHT  -> c.x + c.w - 4f
                else -> c.x + 4f
            }
            val ap = Paint(p).apply { textAlign = c.align }
            cv.drawText(c.label, tx, y + h - 5f, ap)
        }
    }

    // ====================================================================
    //  СТРОКА ДАННЫХ
    // ====================================================================

    private fun drawRow(
        cv: Canvas, cols: List<Col>, vals: List<String>,
        y: Float, h: Float, bg: Int, urg: Int
    ) {
        // фон
        Paint().apply { color = bg; style = Paint.Style.FILL }
            .let { cv.drawRect(ML, y, ML + CW, y + h, it) }

        // цветная полоска слева
        when {
            urg >= 2 -> Paint().apply { color = C_RED; style = Paint.Style.FILL }
                .let { cv.drawRect(ML, y, ML + 4f, y + h, it) }
            urg == 1 -> Paint().apply { color = C_YELLOW; style = Paint.Style.FILL }
                .let { cv.drawRect(ML, y, ML + 4f, y + h, it) }
        }

        // нижняя линия
        Paint().apply { color = C_BORDER; strokeWidth = 0.5f }
            .let { cv.drawLine(ML, y + h, ML + CW, y + h, it) }

        val cp = Paint().apply {
            typeface = fRegular; textSize = 8f; color = C_TEXT; isAntiAlias = true
        }
        for ((i, c) in cols.withIndex()) {
            val txt = vals.getOrElse(i) { "" }
            if (txt.isBlank()) continue

            when (c.align) {
                Paint.Align.CENTER -> {
                    val p = Paint(cp).apply { textAlign = Paint.Align.CENTER }
                    cv.drawText(txt, c.x + c.w / 2f, y + 12f, p)
                }
                Paint.Align.RIGHT -> {
                    val p = Paint(cp).apply { textAlign = Paint.Align.RIGHT }
                    cv.drawText(txt, c.x + c.w - 4f, y + 12f, p)
                }
                else -> {
                    val aw = c.w - 6f
                    val lines = wrap(txt, aw, cp)
                    var ty = y + 12f
                    for (ln in lines) {
                        if (ty + cp.textSize > y + h - 2f) break
                        cv.drawText(ln, c.x + 4f, ty, cp)
                        ty += cp.textSize + 1f
                    }
                }
            }
        }
    }

    // ====================================================================
    //  ПОДВАЛ
    // ====================================================================

    private fun drawFooter(cv: Canvas, pn: Int) {
        val y = PAGE_H - MB + 10f
        Paint().apply { color = C_PRIM_LIGHT; strokeWidth = 0.5f }
            .let { cv.drawLine(ML, y, ML + CW, y, it) }

        Paint().apply {
            typeface = fRegular; textSize = 7f; color = C_TEXT2; isAntiAlias = true
        }.let { cv.drawText("MrCheck — Отчет по заявкам в работе", ML, y + 10f, it) }

        Paint().apply {
            typeface = fRegular; textSize = 7f; color = C_TEXT2
            textAlign = Paint.Align.RIGHT; isAntiAlias = true
        }.let { cv.drawText("Страница $pn", ML + CW, y + 10f, it) }
    }

    // ====================================================================
    //  УТИЛИТЫ
    // ====================================================================

    private fun wrap(text: String, maxW: Float, p: Paint): List<String> {
        if (p.measureText(text) <= maxW) return listOf(text)
        val words = text.split(" ")
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (w in words) {
            val test = if (cur.isEmpty()) w else "$cur $w"
            if (p.measureText(test) <= maxW) { cur = StringBuilder(test) }
            else { if (cur.isNotEmpty()) out.add(cur.toString()); cur = StringBuilder(w) }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out.ifEmpty { listOf(text) }
    }

    private fun calcH(cols: List<Col>, vals: List<String>): Float {
        val p = Paint().apply { typeface = fRegular; textSize = 8f; isAntiAlias = true }
        var mx = 1
        for ((i, c) in cols.withIndex()) {
            val t = vals.getOrElse(i) { "" }
            if (c.align != Paint.Align.LEFT) continue
            val n = wrap(t, c.w - 6f, p).size
            if (n > mx) mx = n
        }
        return mx * (p.textSize + 1f) + 6f
    }

    private fun fmtDate(period: String?): String {
        if (period.isNullOrBlank()) return "—"
        return try {
            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)
            val d = sdf.parse(period)
            if (d != null) dateOnly.format(d) else period.take(10)
        } catch (_: Exception) { period.take(10) }
    }

    private fun urgency(period: String?): Int {
        if (period.isNullOrBlank()) return 0
        return try {
            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("Europe/Moscow")
            val dl = sdf.parse(period) ?: return 0
            val diff = dl.time - System.currentTimeMillis()
            when {
                diff <= 0 -> 3
                diff < 2 * 3600_000L -> 2
                diff < 4 * 3600_000L -> 1
                else -> 0
            }
        } catch (_: Exception) { 0 }
    }
}