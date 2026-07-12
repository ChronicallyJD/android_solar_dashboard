package com.offgrid.solardashboard.protocol

import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/**
 * Victron Instant Readout BLE advertisement parsing.
 * Direct port of solar_monitor/victron.py.
 *
 * All data arrives in the manufacturer-specific advertisement payload; no
 * GATT connection is required. Bleak/Android strip the 2-byte company ID
 * (0x02E1) so index 0 of the payload is the first application-level byte.
 */
object VictronParser {

    const val VICTRON_MFR_ID = 0x02E1

    // record_type -> (label, deviceType)
    val RECORD_TYPES: Map<Int, Pair<String, String>> = mapOf(
        0x01 to ("Solar Charger" to "mppt"),
        0x02 to ("Battery Monitor" to "monitor"),
        0x03 to ("Inverter" to "inverter"),
        0x04 to ("DC/DC Converter" to "dcdc"),
        0x05 to ("SmartLithium" to "lithium"),
        0x06 to ("Inverter RS" to "inverter"),
        0x07 to ("VE.Bus" to "inverter"),
        0x08 to ("SmartShunt IP65" to "monitor"),
        0x09 to ("DC-DC Charger" to "dcdc"),
        0x0B to ("Multi RS" to "inverter"),
        0x0C to ("VE.Bus" to "inverter"),
        0x0D to ("Orion XS" to "dcdc"),
    )

    val NAME_KEYWORDS = listOf(
        "victron", "smartsolar", "bluesolar", "bmv", "smartshunt",
        "multiplus", "phoenix", "orion", "multi rs", "quattro",
    )

    private val CHARGER_STATES = mapOf(
        0 to "Off", 2 to "Fault", 3 to "Bulk", 4 to "Absorption",
        5 to "Float", 7 to "Equalize", 252 to "ESS", 255 to "Unavailable",
    )

    private val INVERTER_STATES = mapOf(
        0 to "Off", 1 to "Low Power", 2 to "Fault", 3 to "Bulk",
        4 to "Absorption", 5 to "Float", 6 to "Storage", 7 to "Equalize",
        8 to "Passthrough", 9 to "Inverting", 10 to "Power Assist",
        11 to "Power Supply", 246 to "Sustain", 247 to "External Control",
        252 to "Hub-1", 253 to "Charge", 255 to "Unavailable",
    )

    private val RECORDS_WITH_STATE = setOf(0x01, 0x03, 0x06, 0x07, 0x0B)

    private val VALID_STATES = setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11,
        246, 247, 252, 253, 255)

    private val TYPE_TO_RECORDS = mapOf(
        "mppt" to setOf(0x01),
        "monitor" to setOf(0x02, 0x08),
        "inverter" to setOf(0x03, 0x06, 0x07, 0x0B, 0x0C),
        "dcdc" to setOf(0x04, 0x09, 0x0D),
        "lithium" to setOf(0x05),
        "meter" to setOf(0x07),
    )

    private val RECORD_PRIORITY = mapOf(
        0x01 to 0, 0x02 to 0, 0x0C to 1, 0x03 to 2, 0x06 to 2,
        0x0B to 2, 0x07 to 9, 0x0D to 0,
    )

    private fun u8(b: Byte): Int = b.toInt() and 0xFF
    private fun leU16(d: ByteArray, o: Int): Int = u8(d[o]) or (u8(d[o + 1]) shl 8)
    private fun leI16(d: ByteArray, o: Int): Int {
        val v = leU16(d, o)
        return if (v >= 0x8000) v - 0x10000 else v
    }
    private fun leU32(d: ByteArray, o: Int): Long =
        (u8(d[o]).toLong()) or (u8(d[o + 1]).toLong() shl 8) or
            (u8(d[o + 2]).toLong() shl 16) or (u8(d[o + 3]).toLong() shl 24)

    private fun round(v: Double, digits: Int): Double {
        var m = 1.0
        repeat(digits) { m *= 10 }
        return (v * m).roundToInt() / m
    }

    class VictronParseException(message: String) : Exception(message)

    /**
     * Return the best usable Victron payload from a list of manufacturer-data
     * payloads (each is the value bytes for company ID 0x02E1, ID already
     * stripped). Format A (0x10 outer) needs >=9 bytes; Format B needs >=5.
     */
    fun extractUsablePayload(payloads: List<ByteArray>): ByteArray? {
        for (p in payloads) {
            if (p.isEmpty()) continue
            if (u8(p[0]) == 0x10) {
                if (p.size >= 9) return p
            } else if (p.size >= 5) {
                return p
            }
        }
        return null
    }

    /**
     * @param keyCheck the byte Victron places immediately before the ciphertext,
     *   which equals the first byte of the advertisement key. -1 when the frame
     *   was too short to carry one (sentinel result).
     */
    data class ParsedPayload(val recordType: Int, val nonce: Int, val ciphertext: ByteArray, val keyCheck: Int = -1)

    /**
     * Extract (record_type, nonce, ciphertext, key_check) from a raw Victron
     * payload.
     *
     * Format A ("Instant Readout", 0x10 prefix) layout, verified against real
     * SmartSolar MPPT and VE.Bus hardware:
     *   [0]     0x10 marker
     *   [1..2]  model id (LE)
     *   [3]     readout record type (advertisement kind, NOT the data record)
     *   [4]     device record type   <-- selects the parser
     *   [5..6]  nonce / counter (LE)
     *   [7]     first byte of the encryption key (key check)
     *   [8..]   AES-CTR ciphertext
     *
     * Format B (legacy, no 0x10 prefix):
     *   [0]     device record type
     *   [1..2]  nonce (LE)
     *   [3]     first byte of the encryption key (key check)
     *   [4..]   AES-CTR ciphertext
     */
    fun parsePayload(raw: ByteArray): ParsedPayload {
        if (raw.isNotEmpty() && u8(raw[0]) == 0x10 && raw.size >= 9) {
            val recordType = u8(raw[4])
            val nonce = leU16(raw, 5)
            val keyCheck = u8(raw[7])
            val ciphertext = raw.copyOfRange(8, raw.size)
            return ParsedPayload(recordType, nonce, ciphertext, keyCheck)
        } else if (raw.size >= 5) {
            val recordType = u8(raw[0])
            val nonce = leU16(raw, 1)
            val keyCheck = u8(raw[3])
            val ciphertext = raw.copyOfRange(4, raw.size)
            return ParsedPayload(recordType, nonce, ciphertext, keyCheck)
        }
        return ParsedPayload(0xFF, 0, ByteArray(0))
    }

    /**
     * AES-128-CTR decrypt. Nonce = 2-byte LE counter zero-padded to 16 bytes.
     */
    fun tryDecrypt(nonce: Int, ciphertext: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(16)
        iv[0] = (nonce and 0xFF).toByte()
        iv[1] = ((nonce shr 8) and 0xFF).toByte()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    // ── Record parsers ──────────────────────────────────────────────────────

    /** Record 0x01 - Solar Charger (SmartSolar / BlueSolar MPPT). */
    fun parseSolar(dec: ByteArray): MutableMap<String, Any?> {
        if (dec.size < 10) throw VictronParseException(
            "Solar Charger record too short (${dec.size}B, need 10)")
        val state = u8(dec[0])
        val error = u8(dec[1])
        val battMv = leI16(dec, 2)
        val battMa = leI16(dec, 4)
        val yieldRaw = leU16(dec, 6)
        val pvRaw = leU16(dec, 8)

        var loadMa: Double? = null
        if (dec.size >= 12) {
            val word10 = leU16(dec, 10)
            val lcRaw = word10 and 0x1FF
            loadMa = if (lcRaw == 0x1FF) null else round(lcRaw * 0.1, 1)
        }

        val battV = if (battMv == 0x7FFF) null else round(battMv * 0.01, 3)
        val battA = if (battMa == 0x7FFF) null else round(battMa * 0.1, 3)
        val pvW = if (pvRaw == 0xFFFF) null else pvRaw.toDouble()
        val yieldWh = if (yieldRaw == 0xFFFF) null else round(yieldRaw * 10.0, 1)
        val battW = if (battV != null && battA != null) round(battV * battA, 2) else null

        return mutableMapOf(
            "voltage_v" to battV,
            "current_a" to battA,
            "power_w" to battW,
            "pv_power_w" to pvW,
            "yield_today_wh" to yieldWh,
            "load_current_a" to loadMa,
            "charger_state" to (CHARGER_STATES[state] ?: "0x%02X".format(state)),
            "error_code" to if (error != 0xFF) error else null,
        )
    }

    /** Record 0x03 - Inverter (Phoenix, Quattro, MultiPlus classic). */
    fun parseInverter(dec: ByteArray): MutableMap<String, Any?> {
        if (dec.size < 7) throw VictronParseException(
            "Inverter record too short (${dec.size}B, need 7)")
        val state = u8(dec[0])
        val alarm = leU16(dec, 1)
        val battCv = leI16(dec, 3)
        val acVaRaw = leU16(dec, 5)

        val word = if (dec.size >= 11) leU32(dec, 7) else 0L
        val acVRaw = (word and 0x7FFF).toInt()
        val acIRaw = ((word shr 15) and 0x7FF).toInt()

        val battV = if (battCv == 0x7FFF) null else round(battCv * 0.01, 2)
        val acVa = if (acVaRaw == 0xFFFF) null else acVaRaw.toDouble()
        val acV = if (acVRaw == 0x7FFF) null else round(acVRaw * 0.01, 2)
        val acI = if (acIRaw == 0x7FF) null else round(acIRaw * 0.1, 2)

        return mutableMapOf(
            "voltage_v" to battV,
            "power_w" to acVa,
            "ac_out_power_va" to acVa,
            "ac_out_voltage_v" to acV,
            "ac_out_current_a" to acI,
            "inverter_state" to (INVERTER_STATES[state] ?: "0x%02X".format(state)),
            "alarm_reason" to if (alarm != 0) alarm else null,
        )
    }

    /** Record 0x07 - VE.Bus Smart Dongle (custom dongle layout). */
    fun parseInverter0x07(dec: ByteArray): MutableMap<String, Any?> {
        if (dec.size < 5) throw VictronParseException(
            "Inverter 0x07 record too short (${dec.size}B, need 5)")
        val state = u8(dec[0])
        val battMv = leU16(dec, 1)
        val acVRaw = leU16(dec, 3)

        val battV = if (battMv == 0xFFFF || battMv == 0x7FFF) null
            else round(battMv * 0.001, 3)
        val acV = if (acVRaw == 0xFFFF || acVRaw == 0x7FFF) null
            else round(acVRaw * 0.01, 2)

        val rawByte4 = if (dec.size >= 5) u8(dec[4]) else null
        val rawLoad = if (dec.size >= 9) u8(dec[8]) else null

        // Calibration constants - null until paired against a VictronConnect reading.
        val scaleWatts: Double? = null
        val scaleAmps: Double? = null
        val acW = if (rawLoad != null && scaleWatts != null) rawLoad * scaleWatts else null
        val acA = if (rawLoad != null && scaleAmps != null) rawLoad * scaleAmps else null

        return mutableMapOf(
            "voltage_v" to battV,
            "power_w" to acW,
            "ac_out_power_va" to acW,
            "ac_out_voltage_v" to acV,
            "ac_out_current_a" to acA,
            "raw_load_indicator" to rawLoad,
            "inverter_state" to (INVERTER_STATES[state] ?: "0x%02X".format(state)),
            "alarm_reason" to null,
        )
    }

    /** Record 0x06 - Inverter RS (and 0x0B Multi RS). */
    fun parseInverterRs(dec: ByteArray): MutableMap<String, Any?> {
        if (dec.size < 12) throw VictronParseException(
            "Inverter RS record too short (${dec.size}B, need 12)")
        val state = u8(dec[0])
        val error = u8(dec[1])
        val battMv = leI16(dec, 2)
        val battMa = leI16(dec, 4)
        val pvRaw = leU16(dec, 6)
        val yieldRaw = leU16(dec, 8)
        val acPwr = leI16(dec, 10)

        val battV = if (battMv == 0x7FFF) null else round(battMv * 0.01, 3)
        val battA = if (battMa == 0x7FFF) null else round(battMa * 0.1, 3)
        val pvW = if (pvRaw == 0xFFFF) null else pvRaw.toDouble()
        val yieldWh = if (yieldRaw == 0xFFFF) null else round(yieldRaw * 10.0, 1)
        val acVa = if (acPwr == 0x7FFF) null else acPwr.toDouble()
        val battW = if (battV != null && battA != null) round(battV * battA, 2) else null

        return mutableMapOf(
            "voltage_v" to battV,
            "current_a" to battA,
            "power_w" to acVa,
            "pv_power_w" to pvW,
            "yield_today_wh" to yieldWh,
            "ac_out_power_va" to acVa,
            "charger_state" to (CHARGER_STATES[state] ?: "0x%02X".format(state)),
            "inverter_state" to (INVERTER_STATES[state] ?: "0x%02X".format(state)),
            "error_code" to if (error != 0xFF) error else null,
        )
    }

    /** Record 0x02 - Battery Monitor (SmartShunt, BMV-712/702). */
    fun parseBmv(dec: ByteArray): MutableMap<String, Any?> {
        if (dec.size < 16) throw VictronParseException(
            "BMV record too short (${dec.size}B, need 16)")
        val ttgRaw = leU16(dec, 0)
        val battMv = leI16(dec, 2)
        val alarm = leU16(dec, 4)

        val wordI = leU32(dec, 8)
        val iRawU22 = ((wordI shr 2) and 0x3FFFFF).toInt()

        val iSigned: Int? = if (iRawU22 == 0x3FFFFF) null
            else if ((iRawU22 and 0x200000) != 0) iRawU22 - 0x400000 else iRawU22

        var socRaw: Int? = null
        if (dec.size >= 15) {
            val ws = leU16(dec, 13)
            val s = (ws shr 4) and 0x3FF
            socRaw = if (s != 0x3FF) s else null
        }

        val battV = if (battMv == 0x7FFF) null else round(battMv * 0.01, 3)
        val ttgMin = if (ttgRaw == 0xFFFF) null else ttgRaw
        val battA = if (iSigned == null) null else round(iSigned * 0.001, 3)
        val socPct = if (socRaw == null) null else round(socRaw * 0.1, 1)
        val battW = if (battV != null && battA != null) round(battV * battA, 2) else null

        return mutableMapOf(
            "voltage_v" to battV,
            "current_a" to battA,
            "power_w" to battW,
            "capacity_pct" to socPct?.toInt(),
            "ttg_minutes" to ttgMin,
            "alarm_reason" to if (alarm != 0) alarm else null,
        )
    }

    /** Record 0x08 - SmartShunt IP65 / DC Energy Meter. */
    fun parseDcEnergy(dec: ByteArray): MutableMap<String, Any?> {
        if (dec.size < 6) throw VictronParseException(
            "DC Energy Meter record too short (${dec.size}B, need 6)")
        val ttgRaw = leU16(dec, 0)
        val battMv = leI16(dec, 2)
        val alarm = leU16(dec, 4)

        val battV = if (battMv == 0x7FFF) null else round(battMv * 0.01, 3)
        val ttgMin = if (ttgRaw == 0xFFFF) null else ttgRaw

        var battA: Double? = null
        if (dec.size >= 16) {
            val wordI = leU32(dec, 12)
            val iRawU22 = (wordI and 0x3FFFFF).toInt()
            if (iRawU22 != 0x3FFFFF) {
                val iSigned = if ((iRawU22 and 0x200000) != 0) iRawU22 - 0x400000 else iRawU22
                battA = round(iSigned * 0.001, 3)
            }
        }

        val battW = if (battV != null && battA != null) round(battV * battA, 2) else null
        return mutableMapOf(
            "voltage_v" to battV,
            "current_a" to battA,
            "power_w" to battW,
            "ttg_minutes" to ttgMin,
            "alarm_reason" to if (alarm != 0) alarm else null,
        )
    }

    /** Record 0x0C / 0x07-full - VE.Bus (VE.Bus Smart Dongle). */
    fun parseVebus(dec: ByteArray): MutableMap<String, Any?> {
        if (dec.size < 13) throw VictronParseException(
            "VE.Bus 0x0C record too short (${dec.size}B, need 13)")

        // Whole payload as a little-endian big integer for bit slicing.
        val le = ByteArray(dec.size) { dec[dec.size - 1 - it] }
        val value = BigInteger(1, le)

        fun u(start: Int, n: Int): Int =
            value.shiftRight(start).and(BigInteger.ONE.shiftLeft(n).subtract(BigInteger.ONE)).toInt()

        val state = u(0, 8)
        val vebusErr = u(8, 8)
        val battCurR = u(16, 16)
        val battVR = u(32, 14)
        val activeAc = u(46, 2)
        val acInR = u(48, 19)
        val acOutR = u(67, 19)
        val alarmRaw = u(86, 2)
        val tempR = u(88, 7)
        val socR = u(95, 7)

        fun sign16(v: Int) = if ((v and 0x8000) != 0) v - 0x10000 else v
        fun sign19(v: Int) = if ((v and 0x40000) != 0) v - 0x80000 else v

        val battA = if (battCurR == 0x7FFF) null else round(sign16(battCurR) * 0.1, 2)
        val battV = if (battVR == 0x3FFF) null else round(battVR * 0.01, 2)
        val acInW = if (acInR == 0x3FFFF) null else sign19(acInR)
        val acOutW = if (acOutR == 0x3FFFF) null else sign19(acOutR)
        val battTc = if (tempR == 0x7F) null else tempR - 40
        val soc = if (socR == 0x7F) null else socR
        val battW = if (battV != null && battA != null) round(battV * battA, 1) else null

        val acInLabel = mapOf(0 to "AC1", 1 to "AC2", 2 to "Not connected", 3 to null)[activeAc]
        val alarmStr = mapOf(0 to null, 1 to "Warning", 2 to "Alarm")[alarmRaw]

        return mutableMapOf(
            "voltage_v" to battV,
            "current_a" to battA,
            "power_w" to battW,
            "capacity_pct" to soc,
            "temperature_c" to battTc?.toDouble(),
            "ac_in_power_w" to acInW?.toDouble(),
            "ac_in_source" to acInLabel,
            "ac_out_power_va" to acOutW?.toDouble(),
            "ac_out_current_a" to null,
            "ac_out_voltage_v" to null,
            "inverter_state" to (INVERTER_STATES[state] ?: "0x%02X".format(state)),
            "vebus_error" to if (vebusErr != 0xFF) vebusErr else null,
            "alarm_reason" to alarmStr,
        )
    }

    // Dispatch table: record_type -> parser
    val PARSERS: Map<Int, (ByteArray) -> MutableMap<String, Any?>> = mapOf(
        0x01 to ::parseSolar,
        0x02 to ::parseBmv,
        0x03 to ::parseInverter,
        0x04 to ::parseBmv,
        0x05 to ::parseBmv,
        0x06 to ::parseInverterRs,
        0x07 to ::parseVebus,
        0x08 to ::parseDcEnergy,
        0x09 to ::parseBmv,
        0x0B to ::parseInverterRs,
        0x0C to ::parseVebus,
        0x0D to ::parseDcEnergy,
        0x0E to ::parseBmv,
    )

    /**
     * Decode a Victron BLE advertisement and return a DeviceReading.
     *
     * allPayloads should contain every distinct payload accumulated for this
     * MAC during the scan window so the device-specific record type can be
     * found even if the generic 0x01 Solar Charger beacon arrived last.
     */
    fun readAdvertisement(
        address: String,
        deviceName: String?,
        allPayloads: List<ByteArray>,
        encKey: String?,
        deviceTypeOverride: String?,
        timestamp: String,
    ): DeviceReading {
        val name = deviceName ?: address
        val candidates = allPayloads.toMutableList()

        if (candidates.isEmpty()) {
            return DeviceReading(
                address = address, name = name,
                deviceType = "victron", timestamp = timestamp,
                error = "No usable Victron Instant Readout data found in advertisement",
            )
        }

        // No key: identify device type only.
        if (encKey.isNullOrEmpty()) {
            val rt = parsePayload(candidates[0]).recordType
            val (label, dtype) = RECORD_TYPES[rt] ?: ("Victron" to "victron")
            return DeviceReading(
                address = address, name = name,
                deviceType = dtype, timestamp = timestamp,
                chargerState = "No key ($label)",
                error = "Encryption key required for full data",
            )
        }

        // Tolerate keys pasted with separators or a "MAC:key" prefix: keep hex
        // digits and use the trailing 32 (the advertisement key length).
        val keyHex = encKey.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            .let { if (it.length >= 32) it.takeLast(32) else it }
        val keyBytes = try {
            hexToBytes(keyHex)
        } catch (e: Exception) {
            return DeviceReading(
                address = address, name = name,
                deviceType = "victron", timestamp = timestamp,
                error = "Invalid encryption key: must be exactly 32 hex characters",
            )
        }
        if (keyBytes.size != 16) {
            return DeviceReading(
                address = address, name = name,
                deviceType = "victron", timestamp = timestamp,
                error = "Invalid encryption key: must be exactly 32 hex characters",
            )
        }

        val allowedRecords: Set<Int>? =
            if (deviceTypeOverride != null) TYPE_TO_RECORDS[deviceTypeOverride] else null

        // Sort so richer/more-specific parsers are tried before fallbacks.
        val sorted = candidates.sortedBy { RECORD_PRIORITY[parsePayload(it).recordType] ?: 5 }

        val keyByte0 = keyBytes[0].toInt() and 0xFF
        var lastError = "no candidate payload decrypted successfully"

        for (payload in sorted) {
            val (recordType, nonce, ciphertext, keyCheck) = parsePayload(payload)
            if (recordType == 0xFF || ciphertext.isEmpty()) continue
            // Victron stamps the first byte of the advertisement key just before
            // the ciphertext. A mismatch means either the wrong key or a stray
            // 0x02E1 beacon that isn't Instant Readout data (some inverters emit
            // both). Skip it rather than decrypt garbage that may pass the
            // plausibility gate and mislabel the device.
            if (keyCheck != keyByte0) {
                lastError = "key check byte 0x%02X does not match configured key (0x%02X)"
                    .format(keyCheck, keyByte0)
                continue
            }
            if (allowedRecords != null && recordType !in allowedRecords) continue
            val parser = PARSERS[recordType] ?: continue

            val decrypted = try {
                tryDecrypt(nonce, ciphertext, keyBytes)
            } catch (e: Exception) {
                lastError = "decryption error: ${e.message}"
                continue
            }

            if (recordType in RECORDS_WITH_STATE && decrypted.isNotEmpty()) {
                if (u8(decrypted[0]) !in VALID_STATES) {
                    lastError = "state byte 0x%02X is not a known charger/inverter state for record type 0x%02X"
                        .format(u8(decrypted[0]), recordType)
                    continue
                }
            }

            val parsed = try {
                parser(decrypted)
            } catch (e: Exception) {
                lastError = "parse error for rec=0x%02X: ${e.message}".format(recordType)
                continue
            }

            val vCheck = parsed["voltage_v"] as? Double
            val aCheck = parsed["current_a"] as? Double

            val vCeiling = when (deviceTypeOverride) {
                "monitor" -> 80.0
                "mppt" -> 150.0
                "inverter" -> 150.0
                else -> 150.0
            }
            val vMin = if (deviceTypeOverride == "inverter") 9.0 else 0.0

            if (vCheck != null && !(vCheck in vMin..vCeiling)) {
                lastError = "decoded voltage %.2fV is outside the physically plausible %s-%sV range"
                    .format(vCheck, vMin, vCeiling)
                continue
            }
            if (aCheck != null && Math.abs(aCheck) > 2000.0) {
                lastError = "decoded current %.1fA is outside the physically plausible +-2000A range"
                    .format(aCheck)
                continue
            }

            // Success.
            val (_, inferredType) = RECORD_TYPES[recordType] ?: ("Victron" to "victron")
            val dtype = deviceTypeOverride ?: inferredType
            val r = DeviceReading(
                address = address, name = name,
                deviceType = dtype, timestamp = timestamp,
            )
            applyParsed(r, parsed)
            return r
        }

        val rt0 = parsePayload(sorted[0]).recordType
        val (_, dtype) = RECORD_TYPES[rt0] ?: ("Victron" to "victron")
        return DeviceReading(
            address = address, name = name,
            deviceType = dtype, timestamp = timestamp,
            error = "Decryption failed: $lastError. Make sure you are using the " +
                "Advertisement key from VictronConnect (gear icon > Product info > " +
                "Instant Readout via Bluetooth > Show > Advertisement key).",
        )
    }

    private fun applyParsed(r: DeviceReading, m: Map<String, Any?>) {
        (m["voltage_v"] as? Double)?.let { r.voltageV = it }
        (m["current_a"] as? Double)?.let { r.currentA = it }
        (m["power_w"] as? Double)?.let { r.powerW = it }
        (m["capacity_pct"] as? Int)?.let { r.capacityPct = it }
        (m["pv_power_w"] as? Double)?.let { r.pvPowerW = it }
        (m["yield_today_wh"] as? Double)?.let { r.yieldTodayWh = it }
        (m["load_current_a"] as? Double)?.let { r.loadCurrentA = it }
        (m["charger_state"] as? String)?.let { r.chargerState = it }
        (m["ac_out_power_va"] as? Double)?.let { r.acOutPowerVa = it }
        (m["ac_out_voltage_v"] as? Double)?.let { r.acOutVoltageV = it }
        (m["ac_out_current_a"] as? Double)?.let { r.acOutCurrentA = it }
        (m["inverter_state"] as? String)?.let { r.inverterState = it }
        (m["ac_in_power_w"] as? Double)?.let { r.acInPowerW = it }
        (m["ac_in_source"] as? String)?.let { r.acInSource = it }
        (m["vebus_error"] as? Int)?.let { r.vebusError = it }
        (m["temperature_c"] as? Double)?.let { r.temperatureC = it }
        (m["ttg_minutes"] as? Int)?.let { r.ttgMinutes = it }
        (m["raw_load_indicator"] as? Int)?.let { r.rawLoadIndicator = it }
        (m["error_code"] as? Int)?.let { r.errorCode = it }
        when (val ar = m["alarm_reason"]) {
            is String -> r.alarmReason = ar
            is Int -> r.alarmBits = ar
        }
    }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have even length" }
        return ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }
}
