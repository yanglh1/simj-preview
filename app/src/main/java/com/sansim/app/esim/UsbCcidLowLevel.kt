package com.sansim.app.esim

import android.hardware.usb.*
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbTransportException(message: String) : Exception(message)

val UsbDevice.interfaces: Iterable<UsbInterface> get() = (0 until interfaceCount).map(::getInterface)
val Iterable<UsbInterface>.smartCard: UsbInterface? get() = find { it.interfaceClass == UsbConstants.USB_CLASS_CSCID }
val UsbInterface.endpoints: Iterable<UsbEndpoint> get() = (0 until endpointCount).map(::getEndpoint)
val Iterable<UsbEndpoint>.bulkPair: Pair<UsbEndpoint?, UsbEndpoint?> get() {
    val eps = filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
    return Pair(eps.find { it.direction == UsbConstants.USB_DIR_IN }, eps.find { it.direction == UsbConstants.USB_DIR_OUT })
}

@Suppress("unused")
data class UsbCcidDescription(
    private val bMaxSlotIndex: Byte,
    private val bVoltageSupport: Byte,
    private val dwProtocols: Int,
    private val dwFeatures: Int,
    val dwMaxIFSD: Int
) {
    companion object {
        private const val DESCRIPTOR_LENGTH: Byte = 0x36
        private const val DESCRIPTOR_TYPE: Byte = 0x21
        private const val FEATURE_AUTOMATIC_VOLTAGE = 0x00008
        private const val FEATURE_AUTOMATIC_PPS = 0x00080
        private const val FEATURE_EXCHANGE_LEVEL_TPDU = 0x10000
        private const val FEATURE_EXCHANGE_LEVEL_SHORT_APDU = 0x20000
        private const val FEATURE_EXCHANGE_LEVEL_EXTENDED_APDU = 0x40000
        private const val VOLTAGE_5V0: Byte = 1
        private const val VOLTAGE_3V0: Byte = 2
        private const val VOLTAGE_1V8: Byte = 4
        private const val SLOT_OFFSET = 4
        private const val FEATURES_OFFSET = 40
        private const val MAX_IFSD_OFFSET = 28
        private const val MASK_T0_PROTO = 1
        private const val MASK_T1_PROTO = 2

        fun fromRawDescriptors(desc: ByteArray): UsbCcidDescription? {
            var protocols = 0
            var features = 0
            var maxSlot: Byte = 0
            var voltage: Byte = 0
            var maxIfsd = 0
            var found = false
            val bb = ByteBuffer.wrap(desc).order(ByteOrder.LITTLE_ENDIAN)
            while (bb.hasRemaining()) {
                if (bb.remaining() < 2) break
                bb.mark()
                val len = bb.get()
                val type = bb.get()
                if (len <= 0 || len - 2 > bb.remaining()) break
                if (type == DESCRIPTOR_TYPE && len == DESCRIPTOR_LENGTH) {
                    bb.reset(); bb.position(bb.position() + SLOT_OFFSET)
                    maxSlot = bb.get()
                    voltage = bb.get()
                    protocols = bb.int
                    bb.reset(); bb.position(bb.position() + MAX_IFSD_OFFSET)
                    maxIfsd = bb.int
                    bb.reset(); bb.position(bb.position() + FEATURES_OFFSET)
                    features = bb.int
                    found = true
                    break
                } else {
                    bb.position(bb.position() + len - 2)
                }
            }
            return if (found) UsbCcidDescription(maxSlot, voltage, protocols, features, maxIfsd) else null
        }
    }

    enum class Voltage(powerOnValue: Int, mask: Int) {
        AUTO(0, 0), V50(1, VOLTAGE_5V0.toInt()), V30(2, VOLTAGE_3V0.toInt()), V18(3, VOLTAGE_1V8.toInt());
        val mask = mask.toByte()
        val powerOnValue = powerOnValue.toByte()
    }

    private fun hasFeature(feature: Int) = (dwFeatures and feature) != 0
    val voltages: List<Voltage> get() = if (hasFeature(FEATURE_AUTOMATIC_VOLTAGE)) listOf(Voltage.AUTO) else Voltage.entries.filter { (it.mask.toInt() and bVoltageSupport.toInt()) != 0 }
    val hasAutomaticPps: Boolean get() = hasFeature(FEATURE_AUTOMATIC_PPS)
    val hasT0Protocol: Boolean get() = (dwProtocols and MASK_T0_PROTO) != 0
    val hasT1Protocol: Boolean get() = (dwProtocols and MASK_T1_PROTO) != 0
    val useTpdu: Boolean get() = hasFeature(FEATURE_EXCHANGE_LEVEL_TPDU)
    val useShortApdu: Boolean get() = hasFeature(FEATURE_EXCHANGE_LEVEL_SHORT_APDU)
    val useExtendedApdu: Boolean get() = hasFeature(FEATURE_EXCHANGE_LEVEL_EXTENDED_APDU)
    fun summary(): String = "protocols=0x${dwProtocols.toString(16)} features=0x${dwFeatures.toString(16)} maxSlot=${bMaxSlotIndex.toInt() and 0xff} maxIFSD=$dwMaxIFSD voltages=${voltages.joinToString { it.name }} autoPps=$hasAutomaticPps tpdu=$useTpdu shortApdu=$useShortApdu extendedApdu=$useExtendedApdu"
}

class UsbCcidTransceiver(
    private val usbConnection: UsbDeviceConnection,
    private val usbBulkIn: UsbEndpoint,
    private val usbBulkOut: UsbEndpoint,
    private val usbCcidDescription: UsbCcidDescription
) {
    companion object {
        private const val CCID_HEADER_LENGTH = 10
        private const val MESSAGE_TYPE_RDR_TO_PC_DATA_BLOCK = 0x80
        private const val MESSAGE_TYPE_RDR_TO_PC_PARAMETERS = 0x82
        private const val MESSAGE_TYPE_PC_TO_RDR_SET_PARAMETERS = 0x61
        private const val MESSAGE_TYPE_PC_TO_RDR_ICC_POWER_ON = 0x62
        private const val MESSAGE_TYPE_PC_TO_RDR_ICC_POWER_OFF = 0x63
        private const val MESSAGE_TYPE_PC_TO_RDR_XFR_BLOCK = 0x6f
        private const val COMMAND_STATUS_SUCCESS: Byte = 0
        private const val COMMAND_STATUS_TIME_EXTENSION_REQUESTED: Byte = 2
        const val LEVEL_PARAM_START_SINGLE_CMD_APDU: Short = 0x0000
        const val LEVEL_PARAM_START_MULTI_CMD_APDU: Short = 0x0001
        const val LEVEL_PARAM_END_MULTI_CMD_APDU: Short = 0x0002
        const val LEVEL_PARAM_CONTINUE_MULTI_CMD_APDU: Short = 0x0003
        const val LEVEL_PARAM_CONTINUE_RESPONSE: Short = 0x0010
        private const val SLOT_NUMBER = 0x00
        private const val ICC_STATUS_SUCCESS: Byte = 0
        private const val TIMEOUT = 5000
        private const val SKIP_TIMEOUT = 100
    }

    data class CcidDataBlock(val dwLength: Int, val bSlot: Byte, val bSeq: Byte, val bStatus: Byte, val bError: Byte, val bChainParameter: Byte, val data: ByteArray?) {
        companion object {
            fun parseHeaderFromBytes(headerBytes: ByteArray): CcidDataBlock {
                val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
                val type = buf.get()
                require(type == MESSAGE_TYPE_RDR_TO_PC_DATA_BLOCK.toByte()) { "bad CCID type $type" }
                val len = buf.int
                val slot = buf.get(); val seq = buf.get(); val status = buf.get(); val err = buf.get(); val chain = buf.get()
                return CcidDataBlock(len, slot, seq, status, err, chain, null)
            }
        }
        fun withData(d: ByteArray) = copy(data = d)
        val iccStatus: Byte get() = (bStatus.toInt() and 0x03).toByte()
        val commandStatus: Byte get() = ((bStatus.toInt() shr 6) and 0x03).toByte()
        val isStatusTimeoutExtensionRequest: Boolean get() = commandStatus == COMMAND_STATUS_TIME_EXTENSION_REQUESTED
        val isStatusSuccess: Boolean get() = iccStatus == ICC_STATUS_SUCCESS && commandStatus == COMMAND_STATUS_SUCCESS
        fun brief(): String = "len=$dwLength slot=${bSlot.toInt() and 0xff} seq=${bSeq.toInt() and 0xff} status=${"%02X".format(bStatus.toInt() and 0xff)} err=${"%02X".format(bError.toInt() and 0xff)} chain=${"%02X".format(bChainParameter.toInt() and 0xff)}"
    }
    class UsbCcidErrorException(msg: String, val errorResponse: CcidDataBlock) : Exception(msg)

    val hasAutomaticPps = usbCcidDescription.hasAutomaticPps
    private val inputBuffer = ByteArray(usbBulkIn.maxPacketSize)
    private var currentSequenceNumber: Byte = 0

    private fun sendRaw(data: ByteArray, offset: Int, length: Int) {
        val sent = usbConnection.bulkTransfer(usbBulkOut, data, offset, length, TIMEOUT)
        if (sent != length) throw UsbTransportException("USB error - failed to transmit data ($sent/$length)")
    }

    private fun receiveParamBlock(expectedSequenceNumber: Byte): ByteArray {
        var response: ByteArray
        do { response = receiveParamBlockImmediate(expectedSequenceNumber) } while (response[7] == 0x80.toByte())
        return response
    }

    private fun receiveParamBlockImmediate(expectedSequenceNumber: Byte): ByteArray {
        var attempts = 3
        var read: Int
        do { read = usbConnection.bulkTransfer(usbBulkIn, inputBuffer, inputBuffer.size, TIMEOUT) } while (read <= 0 && attempts-- > 0)
        if (read < CCID_HEADER_LENGTH) throw UsbTransportException("USB-CCID error - failed to receive param header read=$read")
        if (inputBuffer[0] != MESSAGE_TYPE_RDR_TO_PC_PARAMETERS.toByte()) throw UsbTransportException("USB-CCID error - bad param header type=${inputBuffer[0]}")
        if (expectedSequenceNumber != inputBuffer[6]) throw UsbTransportException("USB-CCID error - param seq mismatch expected=$expectedSequenceNumber got=${inputBuffer[6]}")
        return inputBuffer.copyOf(read)
    }

    private fun receiveDataBlock(expectedSequenceNumber: Byte): CcidDataBlock {
        var response: CcidDataBlock
        do { response = receiveDataBlockImmediate(expectedSequenceNumber) } while (response.isStatusTimeoutExtensionRequest)
        if (!response.isStatusSuccess) throw UsbCcidErrorException("USB-CCID error ${response.brief()}", response)
        return response
    }

    private fun receiveDataBlockImmediate(expectedSequenceNumber: Byte): CcidDataBlock {
        var attempts = 3
        var readBytes: Int
        do { readBytes = usbConnection.bulkTransfer(usbBulkIn, inputBuffer, inputBuffer.size, TIMEOUT) } while (readBytes <= 0 && attempts-- > 0)
        if (readBytes < CCID_HEADER_LENGTH) throw UsbTransportException("USB-CCID error - failed to receive CCID header read=$readBytes")
        if (inputBuffer[0] != MESSAGE_TYPE_RDR_TO_PC_DATA_BLOCK.toByte()) throw UsbTransportException("USB-CCID error - bad CCID header type=${inputBuffer[0]}")
        var result = CcidDataBlock.parseHeaderFromBytes(inputBuffer)
        if (expectedSequenceNumber != result.bSeq) throw UsbTransportException("USB-CCID error - expected sequence $expectedSequenceNumber, got ${result.bSeq}")
        val dataBuffer = ByteArray(result.dwLength)
        var bufferedBytes = readBytes - CCID_HEADER_LENGTH
        if (bufferedBytes > 0) System.arraycopy(inputBuffer, CCID_HEADER_LENGTH, dataBuffer, 0, minOf(bufferedBytes, dataBuffer.size))
        while (bufferedBytes < dataBuffer.size) {
            readBytes = usbConnection.bulkTransfer(usbBulkIn, inputBuffer, inputBuffer.size, TIMEOUT)
            if (readBytes < 0) throw UsbTransportException("USB error - failed reading response data! Header: $result")
            System.arraycopy(inputBuffer, 0, dataBuffer, bufferedBytes, minOf(readBytes, dataBuffer.size - bufferedBytes))
            bufferedBytes += readBytes
        }
        result = result.withData(dataBuffer)
        return result
    }

    private fun skipAvailableInput() {
        var ignored: Int
        do { ignored = usbConnection.bulkTransfer(usbBulkIn, inputBuffer, inputBuffer.size, SKIP_TIMEOUT) } while (ignored > 0)
    }

    fun receiveContinuedResponse(): CcidDataBlock = sendXfrBlock(ByteArray(0), LEVEL_PARAM_CONTINUE_RESPONSE)

    fun sendXfrBlock(payload: ByteArray, levelParam: Short = LEVEL_PARAM_START_SINGLE_CMD_APDU): CcidDataBlock {
        val start = SystemClock.elapsedRealtime()
        val l = payload.size
        val sequenceNumber = currentSequenceNumber++
        val header = byteArrayOf(
            MESSAGE_TYPE_PC_TO_RDR_XFR_BLOCK.toByte(), l.toByte(), (l shr 8).toByte(), (l shr 16).toByte(), (l shr 24).toByte(),
            SLOT_NUMBER.toByte(), sequenceNumber, 0x00.toByte(), (levelParam.toInt() and 0xff).toByte(), (levelParam.toInt() shr 8).toByte()
        )
        val data = header + payload
        var sent = 0
        while (sent < data.size) {
            val n = usbBulkOut.maxPacketSize.coerceAtMost(data.size - sent)
            sendRaw(data, sent, n)
            sent += n
        }
        val block = receiveDataBlock(sequenceNumber)
        LogCollector.d("UsbCcidTransceiver", "XfrBlock level=0x${levelParam.toString(16)} tx=${payload.size} ${block.brief()} took=${SystemClock.elapsedRealtime() - start}ms")
        return block
    }

    fun sendParamBlock(payload: ByteArray): ByteArray {
        val sequenceNumber = currentSequenceNumber++
        val l = payload.size
        val header = byteArrayOf(
            MESSAGE_TYPE_PC_TO_RDR_SET_PARAMETERS.toByte(), l.toByte(), (l shr 8).toByte(), (l shr 16).toByte(), (l shr 24).toByte(),
            SLOT_NUMBER.toByte(), sequenceNumber, 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
        )
        val data = header + payload
        var sent = 0
        while (sent < data.size) {
            val n = usbBulkOut.maxPacketSize.coerceAtMost(data.size - sent)
            sendRaw(data, sent, n)
            sent += n
        }
        return receiveParamBlock(sequenceNumber)
    }

    fun iccPowerOn(): CcidDataBlock {
        skipAvailableInput()
        var response: CcidDataBlock? = null
        for (voltage in usbCcidDescription.voltages.ifEmpty { listOf(UsbCcidDescription.Voltage.AUTO) }) {
            response = try { iccPowerOnVoltage(voltage.powerOnValue) } catch (e: UsbCcidErrorException) {
                if (e.errorResponse.bError.toInt() == 7) { runCatching { iccPowerOff() }; continue }
                throw e
            }
            break
        }
        return response ?: throw UsbTransportException("Couldn't power up ICC")
    }

    private fun iccPowerOnVoltage(voltage: Byte): CcidDataBlock {
        val seq = currentSequenceNumber++
        sendRaw(byteArrayOf(MESSAGE_TYPE_PC_TO_RDR_ICC_POWER_ON.toByte(),0,0,0,0,SLOT_NUMBER.toByte(),seq,voltage,0,0), 0, 10)
        return receiveDataBlock(seq)
    }

    fun iccPowerOff() {
        val seq = currentSequenceNumber++
        sendRaw(byteArrayOf(MESSAGE_TYPE_PC_TO_RDR_ICC_POWER_OFF.toByte(),0,0,0,0,SLOT_NUMBER.toByte(),seq,0,0,0), 0, 10)
        runCatching { receiveDataBlock(seq) }
    }
}
