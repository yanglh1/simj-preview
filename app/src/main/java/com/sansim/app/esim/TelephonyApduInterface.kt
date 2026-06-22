package com.sansim.app.esim

import android.telephony.IccOpenLogicalChannelResponse
import android.telephony.TelephonyManager
import android.util.Log
import java.lang.reflect.Method

/**
 * Telephony API 适配器 - 通过系统隐藏 API 访问 eSIM
 * 需要 Root + 系统特权权限（Magisk 模块）
 * 绕过 ARA-M 白名单限制
 */
class TelephonyApduInterface(
    private val tm: TelephonyManager,
    private val slotId: Int = 0,
    private val portId: Int = 0
) {
    companion object {
        const val TAG = "TelephonyApdu"
        
        // 隐藏 API 反射方法
        private val iccOpenLogicalChannelBySlot: Method by lazy {
            TelephonyManager::class.java.getMethod(
                "iccOpenLogicalChannelBySlot",
                Int::class.java, String::class.java, Int::class.java
            )
        }
        
        private val iccOpenLogicalChannelByPort: Method? by lazy {
            try {
                TelephonyManager::class.java.getMethod(
                    "iccOpenLogicalChannelByPort",
                    Int::class.java, Int::class.java, String::class.java, Int::class.java
                )
            } catch (e: Exception) {
                LogCollector.e(TAG, "iccOpenLogicalChannelByPort not found", e)
                null
            }
        }
        
        private val iccCloseLogicalChannelBySlot: Method by lazy {
            TelephonyManager::class.java.getMethod(
                "iccCloseLogicalChannelBySlot",
                Int::class.java, Int::class.java
            )
        }
        
        private val iccCloseLogicalChannelByPort: Method? by lazy {
            try {
                TelephonyManager::class.java.getMethod(
                    "iccCloseLogicalChannelByPort",
                    Int::class.java, Int::class.java, Int::class.java
                )
            } catch (e: Exception) {
                LogCollector.e(TAG, "iccCloseLogicalChannelByPort not found", e)
                null
            }
        }
        
        private val iccTransmitApduLogicalChannelBySlot: Method by lazy {
            TelephonyManager::class.java.getMethod(
                "iccTransmitApduLogicalChannelBySlot",
                Int::class.java, Int::class.java, Int::class.java, Int::class.java,
                Int::class.java, Int::class.java, Int::class.java, String::class.java
            )
        }
        
        private val iccTransmitApduLogicalChannelByPort: Method? by lazy {
            try {
                TelephonyManager::class.java.getMethod(
                    "iccTransmitApduLogicalChannelByPort",
                    Int::class.java, Int::class.java, Int::class.java, Int::class.java,
                    Int::class.java, Int::class.java, Int::class.java, Int::class.java,
                    String::class.java
                )
            } catch (e: Exception) {
                LogCollector.e(TAG, "iccTransmitApduLogicalChannelByPort not found", e)
                null
            }
        }
    }
    
    private var channelHandle: Int = -1
    
    /**
     * 打开逻辑通道
     * @param aid AID (十六进制字符串)
     * @return 通道号，失败返回 -1
     */
    fun openLogicalChannel(aid: String): Int {
        return try {
            LogCollector.d(TAG, "Opening logical channel: slot=$slotId, port=$portId, aid=$aid")
            
            // 优先使用 ByPort，如果不支持则使用 BySlot
            val response = if (iccOpenLogicalChannelByPort != null) {
                LogCollector.d(TAG, "Using iccOpenLogicalChannelByPort")
                iccOpenLogicalChannelByPort!!.invoke(tm, slotId, portId, aid, 0)
            } else {
                LogCollector.d(TAG, "Using iccOpenLogicalChannelBySlot")
                iccOpenLogicalChannelBySlot.invoke(tm, slotId, aid, 0)
            } as IccOpenLogicalChannelResponse
            
            LogCollector.d(TAG, "Response: status=${response.status}, channel=${response.channel}")
            
            if (response.status == IccOpenLogicalChannelResponse.STATUS_NO_ERROR && 
                response.channel != IccOpenLogicalChannelResponse.INVALID_CHANNEL) {
                channelHandle = response.channel
                LogCollector.d(TAG, "Channel opened: $channelHandle")
                channelHandle
            } else {
                LogCollector.e(TAG, "Failed to open channel: status=${response.status}, channel=${response.channel}")
                -1
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "openLogicalChannel failed: ${e.javaClass.simpleName}: ${e.message}")
            // 打印根本原因
            var cause = e.cause
            while (cause != null) {
                LogCollector.e(TAG, "  Caused by: ${cause.javaClass.simpleName}: ${cause.message}")
                cause = cause.cause
            }
            -1
        }
    }
    
    /**
     * 关闭逻辑通道
     */
    fun closeLogicalChannel() {
        if (channelHandle < 0) return
        
        try {
            LogCollector.d(TAG, "Closing channel: $channelHandle")
            if (iccCloseLogicalChannelByPort != null) {
                iccCloseLogicalChannelByPort!!.invoke(tm, slotId, portId, channelHandle)
            } else {
                iccCloseLogicalChannelBySlot.invoke(tm, slotId, channelHandle)
            }
            channelHandle = -1
        } catch (e: Exception) {
            LogCollector.e(TAG, "closeLogicalChannel failed", e)
        }
    }
    
    /**
     * 发送 APDU 命令
     * @param cla CLA 字节
     * @param ins INS 字节
     * @param p1 P1 字节
     * @param p2 P2 字节
     * @param p3 P3 字节
     * @param data 数据 (十六进制字符串)
     * @return 响应数据 (十六进制字符串)，失败返回 null
     */
    fun transmitApdu(
        cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, data: String?
    ): String? {
        if (channelHandle < 0) {
            LogCollector.e(TAG, "No open channel")
            return null
        }
        
        return try {
            LogCollector.d(TAG, "Transmit APDU: cla=$cla ins=$ins p1=$p1 p2=$p2 p3=$p3 data=$data")
            
            val result = if (iccTransmitApduLogicalChannelByPort != null) {
                iccTransmitApduLogicalChannelByPort!!.invoke(
                    tm, slotId, portId, channelHandle,
                    cla, ins, p1, p2, p3, data
                )
            } else {
                iccTransmitApduLogicalChannelBySlot.invoke(
                    tm, slotId, channelHandle,
                    cla, ins, p1, p2, p3, data
                )
            } as? String
            
            LogCollector.d(TAG, "APDU response: $result")
            result
        } catch (e: Exception) {
            LogCollector.e(TAG, "transmitApdu failed", e)
            null
        }
    }
    
    /**
     * 发送原始 APDU 命令
     * @param apdu 完整 APDU 命令 (十六进制字符串)
     * @return 响应数据 (十六进制字符串)
     */
    fun transmitRawApdu(apdu: String): String? {
        if (apdu.length < 10) return null
        
        // 解析 APDU: CLA INS P1 P2 P3 [Data]
        val cla = apdu.substring(0, 2).toInt(16)
        val ins = apdu.substring(2, 4).toInt(16)
        val p1 = apdu.substring(4, 6).toInt(16)
        val p2 = apdu.substring(6, 8).toInt(16)
        val p3 = apdu.substring(8, 10).toInt(16)
        val data = if (apdu.length > 10) apdu.substring(10) else null
        
        return transmitApdu(cla, ins, p1, p2, p3, data)
    }
    
    /**
     * 检查是否可用
     */
    fun isAvailable(): Boolean {
        return try {
            // 尝试调用隐藏 API 检查是否可用
            iccOpenLogicalChannelBySlot != null
        } catch (e: Exception) {
            LogCollector.e(TAG, "isAvailable failed", e)
            false
        }
    }
}
