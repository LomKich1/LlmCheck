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
    private val efficiency = 0.6       // llama.cpp достигает ~60% от пропускной способности

    @Volatile private var sink = 0L
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var query: EditText
    private lateinit var btn: Button

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 80, 36, 36) }
        status = TextView(this).apply { textSize = 14f }
        query = EditText(this).apply { hint = "Поиск на HF (qwen, llama, gemma…) или пусто = в тренде" }
        btn = Button(this).apply { text = "Запустить тест"; setOnClickListener { run() } }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(status); root.addView(query); root.addView(btn); root.addView(list)
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
        Thread {
            try {
                ui { status.text = deviceInfo() + "\n\nИзмеряю пропускную способность памяти…" }
                val bw = benchBandwidth()
                ui { status.text = deviceInfo() + "\nПамять: %.1f ГБ/с (замер)\n\nИщу свежие модели на Hugging Face…".format(bw) }
                val models = fetchModels(q)
                val budget = totalRamGb() * 1e9 * ramShare
                val rows = models.map { m ->
                    val size = m.params * bytesPerParam
                    val tps = bw * 1e9 * efficiency / size
                    Triple(m, size, tps)
                }
                val fit = rows.filter { it.second * 1.15 <= budget }.sortedByDescending { it.second }.take(30)
                ui {
                    status.text = deviceInfo() + "\nПамять: %.1f ГБ/с | бюджет под модель: %.1f ГБ\nПодходит %d из %d (моложе %d дн.)"
                        .format(bw, budget / 1e9, fit.size, rows.size, maxAgeDays)
                    fit.forEach { (m, size, tps) ->
                        val mark = if (tps >= 10) "🟢" else if (tps >= 4) "🟡" else "🔴"
                        list.addView(TextView(this).apply {
                            setPadding(0, 24, 0, 0)
                            text = "$mark ${m.id}\n   %.1fB параметров, ~%.1f ГБ (Q4), ≈%.0f ток/с\n   %s | ⬇ %d | ♥ %d"
                                .format(m.params / 1e9, size / 1e9, tps, m.created.take(10), m.downloads, m.likes)
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

    // Многопоточное чтение памяти -> ГБ/с. Токены/с в LLM упираются именно в это.
    private fun benchBandwidth(): Double {
        val n = 4; val size = 64 * 1024 * 1024
        val bufs = List(n) {
            ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()).asLongBuffer()
                .also { b -> for (i in 0 until b.capacity() step 512) b.put(i, 1L) }
        }
        val passes = 4
        val t0 = System.nanoTime()
        val ts = bufs.map { b -> Thread { var s = 0L; repeat(passes) { for (i in 0 until b.capacity()) s += b.get(i) }; sink += s }.also { it.start() } }
        ts.forEach { it.join() }
        val sec = (System.nanoTime() - t0) / 1e9
        return n.toLong() * size * passes / sec / 1e9
    }

    data class M(val id: String, val params: Double, val created: String, val downloads: Long, val likes: Long)

    private fun fetchModels(q: String): List<M> {
        var u = "https://huggingface.co/api/models?filter=gguf&sort=trendingScore&limit=100" +
            "&expand%5B%5D=createdAt&expand%5B%5D=downloads&expand%5B%5D=likes&expand%5B%5D=gguf"
        if (q.isNotEmpty()) u += "&search=" + URLEncoder.encode(q, "UTF-8")
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        val arr = JSONArray(c.inputStream.bufferedReader().readText())
        val cutoff = Instant.now().minus(maxAgeDays, ChronoUnit.DAYS)
        val out = ArrayList<M>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val created = o.optString("createdAt")
            if (created.isEmpty() || Instant.parse(created).isBefore(cutoff)) continue
            val total = o.optJSONObject("gguf")?.optDouble("total", 0.0) ?: 0.0
            if (total < 1e8) continue
            out.add(M(o.getString("id"), total, created, o.optLong("downloads"), o.optLong("likes")))
        }
        return out
    }
}
