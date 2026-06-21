package com.sansim.app.esim

interface ReaderBackend {
    suspend fun connect()
    fun disconnect()
    fun openChannel(aid: ByteArray): Boolean
    fun closeChannel(channel: Int)
    fun transmit(command: ByteArray): ByteArray
    fun isReady(): Boolean
    fun getReaderName(): String
}
