package com.sansim.app.esim

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

class UsbCcReader(private val context: Context, private val device: UsbDevice) : ReaderBackend {
    private val tag = "UsbCcReader"
    private var conn: UsbDeviceConnection? = null
    private var usbIf: UsbInterface? = null
    private var transceiver: UsbCcidTransceiver? = null
    private var currentChannel: Int = -1
    private var atr: ByteArray? = null

    override suspend fun connect() {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        LogCollector.d(tag, "connect device=${device.deviceName} vid=${device.vendorId} pid=${device.productId} hasPermission=${manager.hasPermission(device)} ifaces=${device.interfaceCount}")
        if (!manager.hasPermission(device)) throw IllegalStateException("没有 USB 权限，请先授权读卡器")
        val iface = device.interfaces.smartCard ?: device.interfaces.firstOrNull()
            ?: throw IllegalStateException("USB 设备没有可用接口")
        val (bulkIn, bulkOut) = iface.endpoints.bulkPair
        if (bulkIn == null || bulkOut == null) throw IllegalStateException("USB 读卡器没有 Bulk IN/OUT 端点")
        val c = manager.openDevice(device) ?: throw IllegalStateException("无法打开 USB 设备")
        if (!c.claimInterface(iface, true)) throw IllegalStateException("无法 claim USB interface")
        val desc = UsbCcidDescription.fromRawDescriptors(c.rawDescriptors)
            ?: throw IllegalStateException("未找到 CCID 描述符，可能不是标准 CCID 读卡器")
        LogCollector.d(tag, "CCID desc ${desc.summary()}")
        if (!desc.hasT0Protocol) LogCollector.d(tag, "warning: CCID descriptor says T=0 not supported, continue anyway")
        val t = UsbCcidTransceiver(c, bulkIn, bulkOut, desc)
        val power = t.iccPowerOn()
        atr = power.data
        LogCollector.d(tag, "ICC powered on ATR=${atr?.hex()} bulkIn=${bulkIn.maxPacketSize} bulkOut=${bulkOut.maxPacketSize}")
        conn = c; usbIf = iface; transceiver = t
        sendTerminalCapability()
    }

    private fun sendTerminalCapability() {
        val cmd = buildCmd(0x80.toByte(), 0xAA.toByte(), 0x00, 0x00, "A9088100820101830107".hexToBytes(), null)
        runCatching { transmitApduByChannel(cmd, 0) }
            .onSuccess { LogCollector.d(tag, "terminal capability -> ${it.hex()}") }
            .onFailure { LogCollector.e(tag, "terminal capability failed", it) }
    }

    override fun disconnect() {
        LogCollector.d(tag, "disconnect")
        runCatching { if (currentChannel >= 0) closeChannel(currentChannel) }
        runCatching { transceiver?.iccPowerOff() }
        runCatching { conn?.releaseInterface(usbIf) }
        runCatching { conn?.close() }
        transceiver = null; conn = null; usbIf = null; currentChannel = -1
    }

    override fun openChannel(aid: ByteArray): Boolean {
        LogCollector.d(tag, "openChannel logical AID=${aid.hex()}")
        return try {
            // Default to the same ISO logical-channel model as Android OMAPI.
            // Keep native CLA/channel bits intact instead of forcing basic channel.
            val openResp = transmitApduRaw(byteArrayOf(0x00, 0x70, 0x00, 0x00, 0x01))
            if (!isSuccess(openResp) || openResp.size < 3) {
                LogCollector.e(tag, "MANAGE CHANNEL open failed ${openResp.hex()}")
                currentChannel = -1
                return false
            }
            val ch = openResp[0].toInt() and 0xff
            currentChannel = ch
            val select = buildCmd(ch.toByte(), 0xA4.toByte(), 0x04, 0x00, aid, null)
            val selResp = transmitApduRaw(select)
            if (!isSuccess(selResp)) {
                LogCollector.e(tag, "SELECT ISD-R on logical channel $ch failed ${selResp.hex()}")
                closeChannel(ch)
                return false
            }
            LogCollector.d(tag, "logical channel $ch selected ISD-R select=${selResp.hex()}")
            true
        } catch (e: Throwable) {
            LogCollector.e(tag, "openChannel logical failed", e); false
        }
    }

    override fun closeChannel(channel: Int) {
        if (channel <= 0) {
            LogCollector.d(tag, "close basic/no channel: no-op requested=$channel current=$currentChannel")
            if (currentChannel == channel) currentChannel = -1
            return
        }
        runCatching {
            val resp = transmitApduRaw(byteArrayOf(channel.toByte(),0x70,0x80.toByte(),channel.toByte()))
            LogCollector.d(tag, "close logical channel $channel -> ${resp.hex()}")
        }
        if (currentChannel == channel) currentChannel = -1
    }

    override fun transmit(command: ByteArray): ByteArray {
        if (currentChannel < 0) return byteArrayOf()
        return try { transmitApduRaw(command) } catch (e: Throwable) { LogCollector.e(tag, "transmit failed", e); byteArrayOf() }
    }

    override fun isReady(): Boolean = transceiver != null
    override fun getReaderName(): String = "USB: ${device.productName ?: device.deviceName}"

    private fun isSuccess(resp: ByteArray): Boolean = resp.size >= 2 && resp[resp.size-2] == 0x90.toByte() && resp[resp.size-1] == 0x00.toByte()

    private fun buildCmd(cla: Byte, ins: Byte, p1: Byte, p2: Byte, data: ByteArray?, le: Byte?): ByteArray {
        var out = byteArrayOf(cla, ins, p1, p2)
        if (data != null) out = out + data.size.toByte() + data
        if (le != null) out += le
        return out
    }

    private fun transmitApduByChannel(tx: ByteArray, channel: Int): ByteArray {
        val real = tx.copyOf()
        real[0] = ((real[0].toInt() and 0xFC) or (channel and 0x03)).toByte()
        return transmitApduRaw(real)
    }

    private fun transmitApduRaw(tx: ByteArray): ByteArray {
        val t = transceiver ?: throw IllegalStateException("USB transceiver not connected")
        val real = tx.copyOf()
        LogCollector.d(tag, "APDU >> ${real.hex()} len=${real.size}")
        var block = t.sendXfrBlock(real)
        LogCollector.d(tag, "CCID << len=${block.dwLength} status=${"%02X".format(block.bStatus.toInt() and 0xff)} err=${"%02X".format(block.bError.toInt() and 0xff)} chain=${"%02X".format(block.bChainParameter.toInt() and 0xff)}")
        var resp = block.data ?: byteArrayOf()
        if (resp.size < 2) throw IllegalStateException("APDU response < 2")
        var sw1 = resp[resp.size-2].toInt() and 0xff
        var sw2 = resp[resp.size-1].toInt() and 0xff
        if (sw1 == 0x6C) {
            real[real.size-1] = sw2.toByte()
            block = t.sendXfrBlock(real)
            LogCollector.d(tag, "CCID <<(6C retry) len=${block.dwLength} status=${"%02X".format(block.bStatus.toInt() and 0xff)} err=${"%02X".format(block.bError.toInt() and 0xff)} chain=${"%02X".format(block.bChainParameter.toInt() and 0xff)}")
            resp = block.data ?: byteArrayOf()
        } else if (sw1 == 0x61) {
            do {
                val gr = byteArrayOf(real[0], 0xC0.toByte(), 0x00, 0x00, sw2.toByte())
                val grBlock = t.sendXfrBlock(gr)
                LogCollector.d(tag, "CCID <<(GET RESPONSE) len=${grBlock.dwLength} status=${"%02X".format(grBlock.bStatus.toInt() and 0xff)} err=${"%02X".format(grBlock.bError.toInt() and 0xff)} chain=${"%02X".format(grBlock.bChainParameter.toInt() and 0xff)}")
                val tmp = grBlock.data ?: byteArrayOf()
                resp = resp.sliceArray(0 until resp.size-2) + tmp
                sw1 = resp[resp.size-2].toInt() and 0xff
                sw2 = resp[resp.size-1].toInt() and 0xff
            } while (sw1 == 0x61)
        }
        LogCollector.d(tag, "APDU << ${resp.hex()}")
        return resp
    }

    companion object {
        fun findDevices(context: Context): List<UsbDevice> {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val list = manager.deviceList.values.toList()
            LogCollector.d("UsbCcReader", "found USB devices=${list.size}: ${list.joinToString { it.deviceName + " vid=" + it.vendorId + " pid=" + it.productId + " ifaces=" + it.interfaceCount }}")
            return list
        }
    }
}

fun String.hexToBytes(): ByteArray = replace(Regex("[^0-9A-Fa-f]"), "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
