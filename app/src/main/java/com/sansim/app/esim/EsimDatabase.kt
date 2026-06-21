package com.sansim.app.esim

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** SIMKit 2.0 offline eSIM/operator database. */
object EsimDatabase {
    data class MccMncRecord(
        val mcc: String,
        val mnc: String,
        val iso: String,
        val operatorFull: String,
        val operatorShort: String,
        val tadig: String,
        val bands: String
    ) { val plmn: String get() = mcc + mnc }

    data class ApnRecord(
        val plmn: String,
        val name: String,
        val apn: String,
        val user: String,
        val password: String
    )

    data class EuiccSizeRecord(
        val plmn: String,
        val serviceProviderName: String,
        val rsp: String,
        val names: List<String>,
        val referenceSize: Int,
        val eumSizes: Map<String, Int>
    )

    data class TravelInfo(
        val police: String,
        val ambulance: String,
        val fire: String,
        val emergency: String,
        val plugs: String,
        val voltage: String,
        val frequency: String
    )

    data class MatchResult(
        val operatorName: String = "",
        val countryIso: String = "",
        val plmn: String = "",
        val rsp: String = "",
        val profileSizeBytes: Int? = null,
        val apns: List<ApnRecord> = emptyList(),
        val travelInfo: TravelInfo? = null
    )

    private var loaded = false
    private var mccmnc: List<MccMncRecord> = emptyList()
    private var apns: List<ApnRecord> = emptyList()
    private var sizes: List<EuiccSizeRecord> = emptyList()
    private var travel: Map<String, TravelInfo> = emptyMap()
    private var referenceEum: String = ""

    fun ensureLoaded(context: Context) {
        if (loaded) return
        try {
            mccmnc = JSONArray(readAsset(context, "esim/mcc_mnc.json")).let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val a = arr.optJSONArray(i) ?: return@mapNotNull null
                    MccMncRecord(
                        mcc = a.optString(0), mnc = a.optString(1), iso = a.optString(2),
                        operatorFull = a.optString(3), operatorShort = a.optString(4),
                        tadig = a.optString(5), bands = a.optString(6)
                    )
                }
            }
            apns = JSONArray(readAsset(context, "esim/apns.json")).let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val a = arr.optJSONArray(i) ?: return@mapNotNull null
                    ApnRecord(a.optString(0), a.optString(1), a.optString(2), a.optString(3), a.optString(4))
                }
            }
            val sizeRoot = JSONObject(readAsset(context, "esim/euicc_sizes.json"))
            referenceEum = sizeRoot.optString("reference_eum")
            sizes = sizeRoot.optJSONArray("results")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val namesArr = o.optJSONArray("names")
                    val eumObj = o.optJSONObject("eum_sizes")
                    EuiccSizeRecord(
                        plmn = o.optString("plmn"),
                        serviceProviderName = o.optString("serviceProviderName"),
                        rsp = o.optString("rsp"),
                        names = if (namesArr == null) emptyList() else (0 until namesArr.length()).map { namesArr.optString(it) },
                        referenceSize = o.optInt("reference_size", 0),
                        eumSizes = if (eumObj == null) emptyMap() else eumObj.keys().asSequence().associateWith { eumObj.optInt(it, 0) }
                    )
                }
            } ?: emptyList()
            val travelObj = JSONObject(readAsset(context, "esim/country_travel_info.json"))
            travel = travelObj.keys().asSequence().associateWith { iso ->
                val a = travelObj.optJSONArray(iso)
                TravelInfo(
                    police = a?.optString(0).orEmpty(), ambulance = a?.optString(1).orEmpty(),
                    fire = a?.optString(2).orEmpty(), emergency = a?.optString(3).orEmpty(),
                    plugs = a?.optString(4).orEmpty(), voltage = a?.optString(5).orEmpty(), frequency = a?.optString(6).orEmpty()
                )
            }
            loaded = true
            LogCollector.d("EsimDatabase", "loaded mccmnc=${mccmnc.size} apns=${apns.size} sizes=${sizes.size} countries=${travel.size}")
        } catch (e: Throwable) {
            LogCollector.e("EsimDatabase", "load failed", e)
        }
    }

    fun stats(context: Context): String {
        ensureLoaded(context)
        return "运营商 ${mccmnc.size} 条 · APN ${apns.size} 条 · eSIM容量 ${sizes.size} 条 · 国家旅行信息 ${travel.size} 个"
    }

    fun match(context: Context, profile: EuiccProfile, eid: String = ""): MatchResult {
        ensureLoaded(context)
        val text = listOf(profile.serviceProvider, profile.name, profile.nickname).joinToString(" ").lowercase()
        val byName = sizes.firstOrNull { s ->
            s.serviceProviderName.lowercase() in text ||
                s.names.any { it.isNotBlank() && it.lowercase() in text } ||
                (s.rsp.isNotBlank() && s.rsp.lowercase() in text)
        }
        val plmn = byName?.plmn ?: inferPlmnFromIccid(profile.iccid)
        val op = mccmnc.firstOrNull { it.plmn == plmn } ?: mccmnc.firstOrNull { r ->
            val n = (r.operatorShort + " " + r.operatorFull).lowercase()
            text.split(" ").filter { it.length >= 3 }.any { it in n }
        }
        val size = byName ?: sizes.firstOrNull { it.plmn == op?.plmn }
        val eumPrefix = inferEumPrefix(eid)
        val estimatedSize = when {
            size == null -> null
            eumPrefix != null && size.eumSizes[eumPrefix] != null -> size.eumSizes[eumPrefix]
            size.referenceSize > 0 -> size.referenceSize
            else -> null
        }
        val finalPlmn = op?.plmn ?: size?.plmn ?: plmn
        val iso = op?.iso.orEmpty()
        return MatchResult(
            operatorName = size?.serviceProviderName?.ifBlank { null } ?: op?.operatorShort?.ifBlank { op.operatorFull } ?: "",
            countryIso = iso,
            plmn = finalPlmn,
            rsp = size?.rsp.orEmpty(),
            profileSizeBytes = estimatedSize,
            apns = apns.filter { it.plmn == finalPlmn }.take(5),
            travelInfo = travel[iso]
        )
    }

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun inferPlmnFromIccid(iccid: String): String {
        val digits = iccid.filter { it.isDigit() }
        if (!digits.startsWith("89")) return ""
        // ICCID after 89 is country calling code, not reliable PLMN. Keep empty unless database name match works.
        return ""
    }

    private fun inferEumPrefix(eid: String): String? {
        val d = eid.filter { it.isDigit() }
        if (d.length < 8) return null
        val candidates = sizes.asSequence().flatMap { it.eumSizes.keys.asSequence() }.toSet()
        return candidates.firstOrNull { d.startsWith(it) }
    }
}

fun Int.bytesToKbText(): String = "${(this / 1024.0).let { String.format("%.1f", it) }} KB"
