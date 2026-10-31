package com.example.llmcheck

import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.temporal.ChronoUnit

class MainActivity : Activity() {
    // --- настройки ---
    private val maxAgeDays = 270L      // модели старше не показываем
    private val bytesPerParam = 0.6    // ~Q4_K_M
    private val ramShare = 0.55        // какую долю RAM реально можно отдать модели
    private val allowedTags = setOf("text-generation", "image-text-to-text") // только LLM, без TTS/ASR
    private val refRealTps = 11.2      // реальный замер на прогретом телефоне: deepseek-r1 1.5B, ollama

    @Volatile private var sink = 0L
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var query: EditText
    private lateinit var btn: Button
    private lateinit var kField: EditText

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 80, 36, 36) }
        status = TextView(this).apply { textSize = 14f }
        query = EditText(this).apply { hint = "Поиск на HF (qwen, llama, gemma…) или пусто = в тренде" }
        kField = EditText(this).apply {
            hint = "Коэфф. калибровки (по умолч. 1.0)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(getSharedPreferences("p", MODE_PRIVATE).getString("k", "1.0"))
        }
        btn = Button(this).apply { text = "Запустить тест"; setOnClickListener { run() } }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(status); root.addView(query); root.addView(kField); root.addView(btn); root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })
        status.text = deviceInfo()
    }

    private fun totalRamGb(): Double {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.totalMem / 1e9
    }

    private fun deviceInfo(): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
        return "${Build.MANUFACTURER} ${Build.MODEL}\nSoC: $soc | ядер: ${Runtime.getRuntime().availableProcessors()}\nRAM: %.1f ГБ".format(totalRamGb())
    }

    private fun run() {
        btn.isEnabled = false
        list.removeAllViews()
        val q = query.text.toString().trim()
        val k = kField.text.toString().toDoubleOrNull() ?: 1.0
        getSharedPreferences("p", MODE_PRIVATE).edit().putString("k", k.toString()).apply()
        Thread {
            try {
                ui { status.text = deviceInfo() + "\n\nИзмеряю пропускную способность памяти…" }
                benchBandwidth() // прогрев: разгоняет частоты CPU
                val bw = benchBandwidth()
                ui { status.text = deviceInfo() + "\nПамять: %.1f ГБ/с (замер)\n\nИщу свежие модели на Hugging Face…".format(bw) }
                val models = fetchModels(q)
                val budget = totalRamGb() * 1e9 * ramShare
                val rows = models.map { m ->
                    val ramNeed = m.total * 1e9 * bytesPerParam * 1.15
                    val tps = bw * k / (m.active * 1e9 * bytesPerParam)
                    Triple(m, ramNeed, tps)
                }
                val ref = bw * k / (1.5e9 * bytesPerParam)
                val fit = rows.filter { it.second <= budget }.sortedByDescending { it.first.total }.take(30)
                ui {
                    status.text = deviceInfo() + "\nТест: %.1f ГБ/с | бюджет RAM: %.1f ГБ\nЭталон 1.5B: ≈%.1f ток/с (реально у вас %.1f → коэфф. = %.2f)\nПодходит %d из %d LLM (моложе %d дн.)"
                        .format(bw, budget / 1e9, ref, refRealTps, k * refRealTps / ref, fit.size, rows.size, maxAgeDays)
                    fit.forEach { (m, _, tps) ->
                        val mark = if (tps >= 10) "🟢" else if (tps >= 4) "🟡" else "🔴"
                        list.addView(TextView(this).apply {
                            setPadding(0, 24, 0, 0)
                            val moe = if (m.active < m.total) " (MoE, активно %.1fB)".format(m.active) else ""
                            val guess = if (m.guess) " ⚠ размер неточный" else ""
                            text = "$mark ${m.id}\n   %.1fB%s, ~%.1f ГБ (Q4), ≈%.1f ток/с%s\n   %s | ⬇ %d | ♥ %d"
                                .format(m.total, moe, m.total * bytesPerParam, tps, guess, m.created.take(10), m.downloads, m.likes)
                        })
                    }
                    btn.isEnabled = true
                }
            } catch (e: Exception) {
                ui { status.text = "Ошибка: ${e.message}"; btn.isEnabled = true }
            }
        }.start()
    }

    private fun ui(f: () -> Unit) = runOnUiThread { f() }

    // Прокси декодирования: поток весов через умножение-накопление (память + вычисления), 4 потока.
    private fun benchBandwidth(): Double {
        val n = 4; val rows = 12288; val cols = 4096          // ~48 МБ на поток
        val w = List(n) { ByteArray(rows * cols).also { java.util.Random(it.toLong()).nextBytes(it) } }
        val x = ByteArray(cols) { (it and 7).toByte() }
        val passes = 3
        val t0 = System.nanoTime()
        val ts = w.map { a -> Thread {
            var s = 0L
            repeat(passes) { for (r in 0 until rows) { var acc = 0; val o = r * cols; for (j in 0 until cols) acc += a[o + j] * x[j]; s += acc } }
            sink += s
        }.also { it.start() } }
        ts.forEach { it.join() }
        return n.toLong() * rows * cols * passes / ((System.nanoTime() - t0) / 1e9) / 1e9
    }

    data class M(val id: String, val total: Double, val active: Double, val guess: Boolean, val created: String, val downloads: Long, val likes: Long)

    private val sizeRe = Regex("(?<![A-Za-z0-9.])(\\d+(?:\\.\\d+)?)([BbMm])(?![A-Za-z])")
    private val actRe = Regex("(?i)(?<![A-Za-z0-9])A(\\d+(?:\\.\\d+)?)B(?![A-Za-z])")

    private fun fetchModels(q: String): List<M> {
        var u = "https://huggingface.co/api/models?filter=gguf&sort=trendingScore&limit=200" +
            "&expand%5B%5D=createdAt&expand%5B%5D=downloads&expand%5B%5D=likes&expand%5B%5D=gguf&expand%5B%5D=pipeline_tag"
        if (q.isNotEmpty()) u += "&search=" + URLEncoder.encode(q, "UTF-8")
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        val arr = JSONArray(c.inputStream.bufferedReader().readText())
        val cutoff = Instant.now().minus(maxAgeDays, ChronoUnit.DAYS)
        val out = ArrayList<M>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("pipeline_tag") !in allowedTags) continue
            val created = o.optString("createdAt")
            if (created.isEmpty() || Instant.parse(created).isBefore(cutoff)) continue
            val id = o.getString("id"); val name = id.substringAfter('/')
            // размер берём из названия: поле gguf.total у репозиториев с несколькими файлами врёт
            var total = sizeRe.find(name)?.let { it.groupValues[1].toDouble() / (if (it.groupValues[2].lowercase() == "m") 1000 else 1) }
            var guess = false
            if (total == null) { total = (o.optJSONObject("gguf")?.optDouble("total", 0.0) ?: 0.0) / 1e9; guess = true }
            if (total < 0.1) continue
            val active = actRe.find(name)?.groupValues?.get(1)?.toDouble() ?: total
            out.add(M(id, total, minOf(active, total), guess, created, o.optLong("downloads"), o.optLong("likes")))
        }
        return out
    }
}
