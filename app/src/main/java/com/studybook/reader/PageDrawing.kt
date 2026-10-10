package com.studybook.reader

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.LocaleList
import androidx.core.graphics.PathParser
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Draws a worksheet page onto a PDF page as lines, shapes and real text, so the shared PDF stays sharp when printed
 * or zoomed. The web page sends the page as a list of shapes in its own units (millimetres), see pageDrawing() in app.js:
 * `{ w, h, lang, paths: [d, ...], ops: [{ k: "rect" | "line" | "path" | "text", m: [a, b, c, d, e, f], ... }] }`.
 */
object PageDrawing {
    fun draw(canvas: Canvas, json: String, pageWidth: Float) {
        val page = JSONObject(json)
        val locale = when (page.optString("lang")) {
            "cmn" -> Locale.SIMPLIFIED_CHINESE
            "ja" -> Locale.JAPANESE
            "ko" -> Locale.KOREAN
            else -> Locale("zh", "HK")
        }
        val pathData = page.getJSONArray("paths")
        val paths = arrayOfNulls<Path>(pathData.length())
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textLocales = LocaleList(locale) }
        val matrix = Matrix()
        val values = FloatArray(9)

        canvas.save()
        canvas.scale(pageWidth / page.getDouble("w").toFloat(), pageWidth / page.getDouble("w").toFloat())
        val ops = page.getJSONArray("ops")
        for (i in 0 until ops.length()) {
            val op = ops.getJSONObject(i)
            canvas.save()
            op.optJSONArray("m")?.let { m ->
                // SVG [a b c d e f] → Android's row-major 3 × 3.
                values[0] = m.f(0); values[1] = m.f(2); values[2] = m.f(4)
                values[3] = m.f(1); values[4] = m.f(3); values[5] = m.f(5)
                values[6] = 0f; values[7] = 0f; values[8] = 1f
                matrix.setValues(values)
                canvas.concat(matrix)
            }
            when (op.getString("k")) {
                "rect" -> color(op, "fill")?.let {
                    fill.color = it
                    canvas.drawRect(op.f("x"), op.f("y"), op.f("x") + op.f("w"), op.f("y") + op.f("h"), fill)
                }
                "line" -> color(op, "stroke")?.let {
                    setStroke(stroke, op, it)
                    canvas.drawLine(op.f("x1"), op.f("y1"), op.f("x2"), op.f("y2"), stroke)
                }
                "path" -> {
                    val index = op.getInt("d")
                    val path = paths[index] ?: PathParser.createPathFromPathData(pathData.getString(index)).also { paths[index] = it }
                    color(op, "fill")?.let {
                        fill.color = it
                        canvas.drawPath(path, fill)
                    }
                    color(op, "stroke")?.let {
                        setStroke(stroke, op, it)
                        canvas.drawPath(path, stroke)
                    }
                }
                "text" -> {
                    text.color = color(op, "fill") ?: Color.BLACK
                    text.textSize = op.f("size")
                    text.typeface = if (op.optString("font") == "sans") Typeface.SANS_SERIF else Typeface.SERIF
                    text.textAlign = when (op.optString("anchor")) {
                        "middle" -> Paint.Align.CENTER
                        "end" -> Paint.Align.RIGHT
                        else -> Paint.Align.LEFT
                    }
                    canvas.drawText(op.getString("s"), op.f("x"), op.f("y"), text)
                }
            }
            canvas.restore()
        }
        canvas.restore()
    }

    private fun setStroke(paint: Paint, op: JSONObject, color: Int) {
        paint.color = color
        paint.strokeWidth = op.optDouble("sw", 1.0).toFloat()
        val round = op.optString("cap") == "round"
        paint.strokeCap = if (round) Paint.Cap.ROUND else Paint.Cap.BUTT
        paint.strokeJoin = if (round) Paint.Join.ROUND else Paint.Join.MITER
        paint.pathEffect = op.optJSONArray("dash")?.let { d -> DashPathEffect(FloatArray(d.length()) { d.f(it) }, 0f) }
    }

    /** "#rgb" or "#rrggbb"; null for no colour. */
    private fun color(op: JSONObject, key: String): Int? {
        val v = op.optString(key).takeIf { it.startsWith("#") } ?: return null
        val hex = if (v.length == 4) "#" + v.drop(1).map { "$it$it" }.joinToString("") else v
        return runCatching { Color.parseColor(hex) }.getOrNull()
    }

    private fun JSONObject.f(key: String) = getDouble(key).toFloat()
    private fun JSONArray.f(index: Int) = getDouble(index).toFloat()
}
