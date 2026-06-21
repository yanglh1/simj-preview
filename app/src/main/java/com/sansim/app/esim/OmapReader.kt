package com.sansim.app.esim

import android.annotation.SuppressLint
import android.content.Context
import android.se.omapi.Channel
import android.se.omapi.SEService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OmapReader(private val context: Context) : ReaderBackend {
    private val tag = "OmapReader"
    private var seService: SEService? = null
    private var currentChannel: Channel? = null

    override suspend fun connect() {
        if (seService?.isConnected == true) {
            LogCollector.d(tag, "SEService already connected")
            return
        }
        val latch = CountDownLatch(1)
        LogCollector.d(tag, "Creating SEService")
        try {
            val svc = SEService(context, { it.run() }) {
                LogCollector.d(tag, "SEService onConnected")
                latch.countDown()
            }
            val ok = latch.await(15, TimeUnit.SECONDS)
            seService = svc
            LogCollector.d(tag, "SEService await=$ok connected=${svc.isConnected} readers=${runCatching { svc.readers.size }.getOrDefault(-1)}")
        } catch (e: Throwable) {
            LogCollector.e(tag, "SEService connect failed", e)
        }
    }

    override fun disconnect() {
        LogCollector.d(tag, "disconnect")
        runCatching { currentChannel?.close() }
        currentChannel = null
        runCatching { seService?.shutdown() }
        seService = null
    }

    @SuppressLint("NewApi")
    override fun openChannel(aid: ByteArray): Boolean {
        val service = seService ?: run { LogCollector.e(tag, "openChannel: service null"); return false }
        if (!service.isConnected) { LogCollector.e(tag, "openChannel: service not connected"); return false }
        LogCollector.d(tag, "openChannel AID=${aid.hex()} readers=${service.readers.size}")
        for ((idx, reader) in service.readers.withIndex()) {
            val present = runCatching { reader.isSecureElementPresent }.getOrDefault(false)
            LogCollector.d(tag, "reader[$idx] ${reader.name} present=$present")
            if (!present) continue
            try {
                val session = reader.openSession()
                LogCollector.d(tag, "reader[$idx] session opened atr=${session.atr?.hex()}")
                val ch = session.openLogicalChannel(aid)
                if (ch != null) {
                    currentChannel = ch
                    LogCollector.d(tag, "reader[$idx] logical channel opened")
                    return true
                }
                LogCollector.e(tag, "reader[$idx] openLogicalChannel returned null")
            } catch (e: Throwable) {
                LogCollector.e(tag, "reader[$idx] channel failed", e)
            }
        }
        return false
    }

    override fun closeChannel(channel: Int) {
        LogCollector.d(tag, "closeChannel $channel")
        runCatching { currentChannel?.close() }
        currentChannel = null
    }

    override fun transmit(command: ByteArray): ByteArray {
        val ch = currentChannel ?: run { LogCollector.e(tag, "transmit without channel"); return byteArrayOf() }
        return try {
            val resp = ch.transmit(command)
            LogCollector.d(tag, "APDU ${command.hex()} -> ${resp.hex()}")
            resp
        } catch (e: Throwable) {
            LogCollector.e(tag, "transmit failed", e)
            byteArrayOf()
        }
    }

    override fun isReady(): Boolean = seService?.isConnected == true
    override fun getReaderName(): String = "内置 eSIM (OMAPI)"
}

fun ByteArray.hex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
