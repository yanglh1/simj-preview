package com.sansim.app.esim

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class EuiccProfile(
    val iccid: String,
    val state: String,
    val name: String,
    val nickname: String,
    val serviceProvider: String,
    val profileClass: Int = 0
)

data class ConfiguredAddresses(
    val defaultDpAddress: String,
    val rootDsAddress: String
)

class EuiccManager(private val context: Context) {
    companion object {
        private const val TAG = "EuiccManager"
        @Volatile var libLoaded = false
            private set
        @Volatile var libError: String? = null
            private set

        fun loadLibrary() {
            if (libLoaded) return
            try {
                System.loadLibrary("euicc_jni")
                libLoaded = true
                libError = null
                LogCollector.d(TAG, "libeuicc_jni loaded")
            } catch (e: Throwable) {
                libLoaded = false
                libError = e.message
                LogCollector.e(TAG, "loadLibrary failed", e)
            }
        }
    }

    init { loadLibrary() }

    private external fun nativeInit(): Long
    private external fun nativeFini(handle: Long)
    private external fun nativeGetEid(handle: Long): String
    private external fun nativeGetProfiles(handle: Long): String
    private external fun nativeEnableProfile(handle: Long, iccid: String): Int
    private external fun nativeDisableProfile(handle: Long, iccid: String): Int
    private external fun nativeDeleteProfile(handle: Long, iccid: String): Int
    private external fun nativeSetNickname(handle: Long, iccid: String, nickname: String): Int
    private external fun nativeGetConfiguredAddresses(handle: Long): String
    private external fun nativeSetDefaultDpAddress(handle: Long, smdp: String): Int
    private external fun nativeAuthenticateProfile(handle: Long, smdp: String, matchingId: String, imei: String): String
    private external fun nativeDownloadProfile(handle: Long, confirmationCode: String): String
    private external fun nativeCancelSession(handle: Long): Int

    private var backend: ReaderBackend? = null
    private var nativeHandle: Long = 0L

    fun setBackend(b: ReaderBackend) {
        if (nativeHandle != 0L) release()
        backend = b
        LogCollector.d(TAG, "backend set: ${b.getReaderName()}")
    }

    suspend fun init() {
        loadLibrary()
        LogCollector.d(TAG, "init start libLoaded=$libLoaded libError=$libError")
        if (!libLoaded) throw IllegalStateException("Native library not loaded: $libError")
        initTrustAllSsl()
        val b = backend ?: throw IllegalStateException("未选择读卡器")
        b.connect()
        val ready = b.isReady()
        LogCollector.d(TAG, "backend ready=$ready")
        if (!ready) throw IllegalStateException("读卡器连接失败")
        nativeHandle = nativeInit()
        LogCollector.d(TAG, "nativeInit returned $nativeHandle")
        if (nativeHandle == 0L) throw IllegalStateException("nativeInit() failed – no eUICC or channel error")
    }

    fun release() {
        LogCollector.d(TAG, "release")
        if (nativeHandle != 0L) runCatching { nativeFini(nativeHandle) }
        nativeHandle = 0L
        runCatching { backend?.disconnect() }
    }

    @Suppress("unused") fun apduConnect() {
        LogCollector.d(TAG, "JNI apduConnect")
        val b = backend ?: throw IllegalStateException("No reader backend")
        if (!b.isReady()) throw IllegalStateException("Reader backend not ready")
    }
    @Suppress("unused") fun apduDisconnect() { LogCollector.d(TAG, "JNI apduDisconnect"); backend?.disconnect() }
    @Suppress("unused") fun apduOpenChannel(aid: ByteArray): Boolean {
        LogCollector.d(TAG, "JNI apduOpenChannel aid=${aid.hex()}")
        val ok = backend?.openChannel(aid) == true
        LogCollector.d(TAG, "JNI apduOpenChannel result=$ok")
        return ok
    }
    @Suppress("unused") fun apduCloseChannel(channel: Int) { LogCollector.d(TAG, "JNI apduCloseChannel $channel"); backend?.closeChannel(channel) }
    @Suppress("unused") fun apduTransmit(command: ByteArray): ByteArray {
        LogCollector.d(TAG, "JNI apduTransmit ${command.hex()}")
        return backend?.transmit(command) ?: byteArrayOf()
    }

    @Suppress("unused") fun httpPost(url: String, body: String): String {
        LogCollector.d(TAG, "HTTP POST $url body=${body.length}B")
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "gsma-rsp-lpad")
            conn.setRequestProperty("X-Admin-Protocol", "gsma/rsp/v2.2.0")
            conn.setRequestProperty("Connection", "close")
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.outputStream.use { os: OutputStream -> os.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
            LogCollector.d(TAG, "HTTP $code resp=${resp.length}B")
            resp
        } finally { conn.disconnect() }
    }

    fun getEid(): String {
        checkHandle()
        val obj = JSONObject(nativeGetEid(nativeHandle))
        if (!obj.optBoolean("success", false)) throw RuntimeException(obj.optString("error", "getEid failed"))
        return obj.optString("eid", "")
    }

    fun getProfiles(): List<EuiccProfile> {
        checkHandle()
        val obj = JSONObject(nativeGetProfiles(nativeHandle))
        if (!obj.optBoolean("success", false)) throw RuntimeException(obj.optString("error", "getProfiles failed"))
        val arr = obj.optJSONArray("profiles") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val stateInt = p.optInt("profileState", 0)
            EuiccProfile(
                iccid = p.optString("iccid", ""),
                state = if (stateInt == 1) "enabled" else "disabled",
                name = p.optString("profileName", ""),
                nickname = p.optString("profileNickname", ""),
                serviceProvider = p.optString("serviceProviderName", ""),
                profileClass = p.optInt("profileClass", 0)
            )
        }
    }

    fun getConfiguredAddresses(): ConfiguredAddresses {
        checkHandle()
        val obj = JSONObject(nativeGetConfiguredAddresses(nativeHandle))
        if (!obj.optBoolean("success", false)) throw RuntimeException(obj.optString("error", "getConfiguredAddresses failed"))
        return ConfiguredAddresses(obj.optString("defaultDpAddress", ""), obj.optString("rootDsAddress", ""))
    }

    fun enableProfile(iccid: String): Int { checkHandle(); LogCollector.d(TAG, "enableProfile $iccid"); return nativeEnableProfile(nativeHandle, iccid) }
    fun disableProfile(iccid: String): Int { checkHandle(); LogCollector.d(TAG, "disableProfile $iccid"); return nativeDisableProfile(nativeHandle, iccid) }
    fun deleteProfile(iccid: String): Int { checkHandle(); return nativeDeleteProfile(nativeHandle, iccid) }
    fun setNickname(iccid: String, nickname: String): Int { checkHandle(); return nativeSetNickname(nativeHandle, iccid, nickname) }
    fun setDefaultDpAddress(smdp: String): Int { checkHandle(); return nativeSetDefaultDpAddress(nativeHandle, smdp) }
    fun authenticateProfile(smdp: String, matchingId: String, imei: String = ""): String { checkHandle(); return nativeAuthenticateProfile(nativeHandle, smdp, matchingId, imei) }
    fun downloadProfile(confirmationCode: String = ""): String { checkHandle(); return nativeDownloadProfile(nativeHandle, confirmationCode) }
    fun cancelSession(): Int { checkHandle(); return nativeCancelSession(nativeHandle) }

    private fun checkHandle() { if (nativeHandle == 0L) throw IllegalStateException("EuiccManager not initialized – call init() first") }

    private fun initTrustAllSsl() {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
    }
}
