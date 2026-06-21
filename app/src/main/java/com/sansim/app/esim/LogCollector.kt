package com.sansim.app.esim

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object LogCollector {
    data class Entry(val time: Long, val tag: String, val msg: String) {
        fun format(): String = "[${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(time))}] $tag: $msg"
    }

    private val entries = CopyOnWriteArrayList<Entry>()
    private const val MAX = 3000

    fun d(tag: String, msg: String) {
        entries.add(Entry(System.currentTimeMillis(), tag, msg))
        if (entries.size > MAX) entries.subList(0, entries.size - MAX).clear()
        Log.d(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val text = if (tr == null) msg else "$msg: ${tr.javaClass.simpleName}: ${tr.message}"
        entries.add(Entry(System.currentTimeMillis(), tag, text))
        if (entries.size > MAX) entries.subList(0, entries.size - MAX).clear()
        Log.e(tag, text, tr)
    }

    fun all(): String = entries.joinToString("\n") { it.format() }
    fun clear() = entries.clear()

    fun export(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "logs")
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "simjiang_esim_log_$ts.txt")
        f.writeText(all().ifBlank { "暂无日志" })
        return f
    }
}
