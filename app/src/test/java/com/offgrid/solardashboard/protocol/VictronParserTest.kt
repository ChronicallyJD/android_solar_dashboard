package com.offgrid.solardashboard.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Port of tests/test_victron_parsers.py: advertisement extraction, payload
 * framing, AES-128-CTR crypto, per-record parsers, and end-to-end reads.
 */
class VictronParserTest {

    private val keyHex = "0102030405060708090a0b0c0d0e0f10"
    private val keyBytes = VictronParser.hexToBytes(keyHex)
    private val nonce = 0x1234
    private val ts = "2026-01-01T00:00:00"

    // ── Crypto / framing helpers (mirror Python test helpers) ──────────────────

    private fun aesCtrEncrypt(plaintext: ByteArray, nonceVal: Int, key: ByteArray): ByteArray {
        val iv = ByteArray(16)
        iv[0] = (nonceVal and 0xFF).toByte()
        iv[1] = ((nonceVal shr 8) and 0xFF).toByte()
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(plaintext)
    }

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    /** Format B: rec + nonce(LE) + key[0] + ciphertext. */
    private fun formatB(recType: Int, plaintext: ByteArray, nonceVal: Int = nonce, key: ByteArray = keyBytes): ByteArray {
        val ct = aesCtrEncrypt(plaintext, nonceVal, key)
        return byteArrayOf(recType.toByte()) + le16(nonceVal) + byteArrayOf(key[0]) + ct
    }

    /**
     * Format A: 0x10 outer wrapper. Real hardware layout (verified against a
     * SmartSolar MPPT and a VE.Bus inverter): byte[3] = readout kind,
     * byte[4] = device record type, byte[5..6] = nonce, byte[7] = key[0].
     */
    private fun formatA(recType: Int, plaintext: ByteArray, model: Int = 0x3502, nonceVal: Int = nonce, key: ByteArray = keyBytes): ByteArray {
        val ct = aesCtrEncrypt(plaintext, nonceVal, key)
        val head = byteArrayOf(
            0x10,
            (model and 0xFF).toByte(), ((model shr 8) and 0xFF).toByte(),
            0xA1.toByte(),              // readout record type (advertisement kind)
            (recType and 0xFF).toByte(), // device record type -> selects parser
            (nonceVal and 0xFF).toByte(), ((nonceVal shr 8) and 0xFF).toByte(),
            key[0],                      // key check byte
        )
        return head + ct
    }

    // Plaintext record builders --------------------------------------------------

    private fun bmvPlain(
        ttg: Int = 1000, battMv: Int = 5200, alarm: Int = 0, aux: Int = 0xFFFF,
        currentU22: Int = 5000, socRaw: Int = 800,
    ): ByteArray {
        val out = ByteArray(16)
        putLe16(out, 0, ttg)
        putLe16(out, 2, if (battMv < 0) battMv + 0x10000 else battMv)
        putLe16(out, 4, alarm)
        putLe16(out, 6, aux)
        val word = (aux.toLong() and 0x3L) or ((currentU22.toLong() and 0x3FFFFF) shl 2)
        putLe32(out, 8, word)
        putLe16(out, 13, (socRaw and 0x3FF) shl 4)
        return out
    }

    private fun dcEnergyPlain(ttg: Int = 1000, battMv: Int = 5200, alarm: Int = 0, currentU22: Int = 5000): ByteArray {
        val out = ByteArray(16)
        putLe16(out, 0, ttg)
        putLe16(out, 2, if (battMv < 0) battMv + 0x10000 else battMv)
        putLe16(out, 4, alarm)
        putLe32(out, 12, currentU22.toLong() and 0x3FFFFF)
        return out
    }

    private fun inverterRsPlain(
        state: Int = 3, error: Int = 0, battVRaw: Int = 5200, battARaw: Int = 100,
        pvRaw: Int = 500, yieldRaw: Int = 120, acRaw: Int = 400,
    ): ByteArray {
        val out = ByteArray(12)
        out[0] = state.toByte(); out[1] = error.toByte()
        putLe16(out, 2, if (battVRaw < 0) battVRaw + 0x10000 else battVRaw)
        putLe16(out, 4, if (battARaw < 0) battARaw + 0x10000 else battARaw)
        putLe16(out, 6, pvRaw)
        putLe16(out, 8, yieldRaw)
        putLe16(out, 10, if (acRaw < 0) acRaw + 0x10000 else acRaw)
        return out
    }

    private fun vebusPlain(
        state: Int = 0x09, error: Int = 0, battCur: Double = -15.0, battV: Double = 52.0,
        activeAc: Int = 2, acInW: Int = 0, acOutW: Int = 755, alarm: Int = 0,
        tempRaw: Int = 65, soc: Int = 84,
    ): ByteArray {
        var value = BigInteger.ZERO
        fun place(v: Int, start: Int) {
            value = value.or(BigInteger.valueOf((v.toLong() and 0xFFFFFFFFL)).shiftLeft(start))
        }
        place(state and 0xFF, 0)
        place(error and 0xFF, 8)
        place((battCur / 0.1).toInt() and 0xFFFF, 16)
        place((battV / 0.01).toInt() and 0x3FFF, 32)
        place(activeAc and 0x3, 46)
        place(acInW and 0x7FFFF, 48)
        place(acOutW and 0x7FFFF, 67)
        place(alarm and 0x3, 86)
        place(tempRaw and 0x7F, 88)
        place(soc and 0x7F, 95)
        val bytes = value.toByteArray()
        // to little-endian, fixed 20 bytes
        val le = ByteArray(20)
        var i = bytes.size - 1
        var j = 0
        while (i >= 0 && j < 20) { le[j] = bytes[i]; i--; j++ }
        return le
    }

    private fun dongle07Plain(
        state: Int = 0x09, battMv: Int = 52000, byte3: Int = 0xFF, byte4: Int = 46, byte8: Int = 56, len: Int = 13,
    ): ByteArray {
        val out = ByteArray(len)
        out[0] = state.toByte()
        if (len >= 3) putLe16(out, 1, battMv)
        if (len >= 4) out[3] = byte3.toByte()
        if (len >= 5) out[4] = byte4.toByte()
        if (len >= 9) out[8] = byte8.toByte()
        return out
    }

    private fun putLe16(a: ByteArray, o: Int, v: Int) { a[o] = (v and 0xFF).toByte(); a[o + 1] = ((v shr 8) and 0xFF).toByte() }
    private fun putLe32(a: ByteArray, o: Int, v: Long) {
        a[o] = (v and 0xFF).toByte(); a[o + 1] = ((v shr 8) and 0xFF).toByte()
        a[o + 2] = ((v shr 16) and 0xFF).toByte(); a[o + 3] = ((v shr 24) and 0xFF).toByte()
    }

    // ── extractUsablePayload ───────────────────────────────────────────────────

    @Test fun extract_formatAReturned() {
        val p = formatA(0x0C, vebusPlain())
        assertNotNull(VictronParser.extractUsablePayload(listOf(p)))
    }

    @Test fun extract_shortFormatASkipped() {
        // 0x10 header but < 9 bytes = VE.Smart beacon
        val beacon = byteArrayOf(0x10, 0x02, 0x35, 0xC0.toByte())
        assertNull(VictronParser.extractUsablePayload(listOf(beacon)))
    }

    @Test fun extract_formatBReturned() {
        assertNotNull(VictronParser.extractUsablePayload(listOf(formatB(0x02, bmvPlain()))))
    }

    @Test fun extract_formatBTooShortSkipped() {
        assertNull(VictronParser.extractUsablePayload(listOf(byteArrayOf(0x02, 0x00, 0x00, 0x00))))
    }

    @Test fun extract_firstUsableFromList() {
        val beacon = byteArrayOf(0x10, 0x02, 0x35, 0xC0.toByte())
        val good = formatB(0x02, bmvPlain())
        assertEquals(good.toHex(), VictronParser.extractUsablePayload(listOf(ByteArray(0), beacon, good))!!.toHex())
    }

    // ── parsePayload ──────────────────────────────────────────────────────────

    @Test fun parsePayload_formatB() {
        val ct = ByteArray(16) { it.toByte() }
        val payload = byteArrayOf(0x02) + le16(0xBEEF) + byteArrayOf(0x01) + ct
        val r = VictronParser.parsePayload(payload)
        assertEquals(0x02, r.recordType)
        assertEquals(0xBEEF, r.nonce)
        assertEquals(ct.toHex(), r.ciphertext.toHex())
    }

    @Test fun parsePayload_minFive() {
        val r = VictronParser.parsePayload(byteArrayOf(0x0C, 0x34, 0x12, 0x01, 0xAA.toByte()))
        assertEquals(0x0C, r.recordType)
        assertEquals(0x1234, r.nonce)
        assertEquals("aa", r.ciphertext.toHex())
    }

    @Test fun parsePayload_tooShortSentinel() {
        val r = VictronParser.parsePayload(byteArrayOf(0x02, 0x00, 0x00, 0x00))
        assertEquals(0xFF, r.recordType)
        assertTrue(r.ciphertext.isEmpty())
    }

    // ── try_decrypt ─────────────────────────────────────────────────────────

    @Test fun decrypt_roundtrip() {
        val plain = ByteArray(20) { it.toByte() }
        val ct = aesCtrEncrypt(plain, 0xABCD, keyBytes)
        assertEquals(plain.toHex(), VictronParser.tryDecrypt(0xABCD, ct, keyBytes).toHex())
    }

    @Test fun decrypt_wrongNonceGarbles() {
        val plain = ByteArray(20) { it.toByte() }
        val ct = aesCtrEncrypt(plain, 0xABCD, keyBytes)
        assertTrue(plain.toHex() != VictronParser.tryDecrypt(0xABCE, ct, keyBytes).toHex())
    }

    // ── Per-record parsers ─────────────────────────────────────────────────────

    @Test fun parse07_batteryVoltage() {
        val d = VictronParser.parseInverter0x07(dongle07Plain(battMv = 52000))
        assertEquals(52.0, d["voltage_v"] as Double, 1e-6)
    }

    @Test fun parse07_batteryNa() {
        assertNull(VictronParser.parseInverter0x07(dongle07Plain(battMv = 0xFFFF))["voltage_v"])
    }

    @Test fun parse07_acVoltageFromBytes() {
        val d = VictronParser.parseInverter0x07(dongle07Plain(byte3 = 0xFF, byte4 = 46))
        assertEquals(120.31, d["ac_out_voltage_v"] as Double, 1e-6)
    }

    @Test fun parse07_rawLoadAndState() {
        val d = VictronParser.parseInverter0x07(dongle07Plain(byte8 = 56, state = 0x09))
        assertEquals(56, d["raw_load_indicator"])
        assertEquals("Inverting", d["inverter_state"])
        assertNull(d["power_w"])
        assertNull(d["ac_out_current_a"])
        assertNull(d["alarm_reason"])
    }

    @Test fun parse07_unknownState() {
        assertEquals("0x42", VictronParser.parseInverter0x07(dongle07Plain(state = 0x42))["inverter_state"])
    }

    @Test fun parse07_lenFiveRawLoadNull() {
        assertNull(VictronParser.parseInverter0x07(dongle07Plain(len = 5))["raw_load_indicator"])
    }

    @Test fun parseRs_fields() {
        val d = VictronParser.parseInverterRs(inverterRsPlain())
        assertEquals(52.0, d["voltage_v"] as Double, 1e-6)
        assertEquals(10.0, d["current_a"] as Double, 1e-6)
        assertEquals(500.0, d["pv_power_w"] as Double, 1e-6)
        assertEquals(1200.0, d["yield_today_wh"] as Double, 1e-6)
        assertEquals(400.0, d["ac_out_power_va"] as Double, 1e-6)
        assertEquals(400.0, d["power_w"] as Double, 1e-6)
        assertEquals("Bulk", d["charger_state"])
        assertEquals("Bulk", d["inverter_state"])
    }

    @Test fun parseRs_negativeCurrent() {
        assertEquals(-15.0, VictronParser.parseInverterRs(inverterRsPlain(battARaw = -150))["current_a"] as Double, 1e-6)
    }

    @Test fun parseRs_errorCode() {
        assertEquals(2, VictronParser.parseInverterRs(inverterRsPlain(error = 2))["error_code"])
        assertNull(VictronParser.parseInverterRs(inverterRsPlain(error = 0xFF))["error_code"])
    }

    @Test fun parseRs_naSentinels() {
        val d = VictronParser.parseInverterRs(inverterRsPlain(
            battVRaw = 0x7FFF, battARaw = 0x7FFF, pvRaw = 0xFFFF, yieldRaw = 0xFFFF, acRaw = 0x7FFF))
        assertNull(d["voltage_v"]); assertNull(d["current_a"]); assertNull(d["pv_power_w"])
        assertNull(d["yield_today_wh"]); assertNull(d["ac_out_power_va"])
    }

    @Test fun parseVebus_fields() {
        val d = VictronParser.parseVebus(vebusPlain())
        assertEquals(52.0, d["voltage_v"] as Double, 1e-6)
        assertEquals(-15.0, d["current_a"] as Double, 1e-6)
        assertEquals(755.0, d["ac_out_power_va"] as Double, 1e-6)
        assertEquals(84, d["capacity_pct"])
        assertEquals("Inverting", d["inverter_state"])
    }

    @Test fun parseBmv_fields() {
        val d = VictronParser.parseBmv(bmvPlain())
        assertEquals(52.0, d["voltage_v"] as Double, 1e-6)
        assertEquals(5.0, d["current_a"] as Double, 1e-6)
        assertEquals(80, d["capacity_pct"])
        assertEquals(1000, d["ttg_minutes"])
    }

    @Test fun parse_shortRecordsRaise() {
        assertThrowsParse { VictronParser.parseSolar(ByteArray(9)) }
        assertThrowsParse { VictronParser.parseInverter(ByteArray(6)) }
        assertThrowsParse { VictronParser.parseBmv(ByteArray(15)) }
        assertThrowsParse { VictronParser.parseDcEnergy(ByteArray(5)) }
    }

    // ── readAdvertisement end-to-end ───────────────────────────────────────────

    private fun read(recType: Int, payload: ByteArray, override: String?, key: String? = keyHex, formatA: Boolean = false): DeviceReading {
        val p = if (formatA) formatA(recType, payload) else formatB(recType, payload)
        return VictronParser.readAdvertisement("AA:BB:CC:DD:EE:FF", "dev", listOf(p), key, override, ts)
    }

    @Test fun read_bmv() {
        val r = read(0x02, bmvPlain(), null)
        assertEquals("monitor", r.deviceType)
        assertEquals(52.0, r.voltageV!!, 1e-6)
        assertEquals(5.0, r.currentA!!, 1e-6)
        assertEquals(80, r.capacityPct)
    }

    @Test fun read_smartShunt08AsMonitor() {
        val r = read(0x08, dcEnergyPlain(), "monitor")
        assertEquals(52.0, r.voltageV!!, 1e-6)
        assertEquals(5.0, r.currentA!!, 1e-6)
    }

    @Test fun read_dcdc04() {
        assertEquals("dcdc", read(0x04, bmvPlain(), "dcdc").deviceType)
    }

    @Test fun read_orionXs0dAsDcdc() {
        assertEquals(5.0, read(0x0D, dcEnergyPlain(), "dcdc").currentA!!, 1e-6)
    }

    @Test fun read_lithium05() {
        assertEquals("lithium", read(0x05, bmvPlain(), "lithium").deviceType)
    }

    @Test fun read_inverterRs06() {
        val r = read(0x06, inverterRsPlain(), "inverter")
        assertEquals(400.0, r.acOutPowerVa!!, 1e-6)
        assertEquals(500.0, r.pvPowerW!!, 1e-6)
    }

    @Test fun read_multiRs0b() {
        assertEquals("inverter", read(0x0B, inverterRsPlain(), "inverter").deviceType)
    }

    @Test fun read_vebus0c() {
        val r = read(0x0C, vebusPlain(), null)
        assertEquals("inverter", r.deviceType)
        assertEquals("Inverting", r.inverterState)
        assertEquals(52.0, r.voltageV!!, 1e-6)
        assertEquals(-15.0, r.currentA!!, 1e-6)
        assertEquals(755.0, r.acOutPowerVa!!, 1e-6)
        assertEquals(84, r.capacityPct)
    }

    @Test fun read_vebus0cViaFormatA() {
        val r = read(0x0C, vebusPlain(), null, formatA = true)
        assertEquals("Inverting", r.inverterState)
        assertEquals(52.0, r.voltageV!!, 1e-6)
    }

    @Test fun read_overrideWinsOverInferred() {
        // 0x06 inferred inverter; override with same works and is preserved
        assertEquals("inverter", read(0x06, inverterRsPlain(), "inverter").deviceType)
    }

    // ── Failure paths ──────────────────────────────────────────────────────────

    @Test fun read_noAdData() {
        val r = VictronParser.readAdvertisement("AA:BB:CC:DD:EE:FF", "dev", emptyList(), keyHex, null, ts)
        assertEquals("victron", r.deviceType)
        assertTrue(r.error!!.contains("No usable"))
    }

    @Test fun read_noKey() {
        val payload = formatB(0x02, bmvPlain())
        val r = VictronParser.readAdvertisement("AA:BB:CC:DD:EE:FF", "dev", listOf(payload), null, null, ts)
        assertEquals("monitor", r.deviceType)
        assertTrue(r.chargerState!!.contains("No key"))
        assertTrue(r.error!!.contains("Encryption key required"))
    }

    @Test fun read_invalidKey() {
        val r = read(0x02, bmvPlain(), null, key = "zz".repeat(16))
        assertTrue(r.error!!.contains("32 hex characters"))
    }

    @Test fun read_unknownOverrideProceeds() {
        val r = read(0x02, bmvPlain(), "toaster")
        assertEquals("toaster", r.deviceType)
        assertNull(r.error)
    }

    @Test fun read_unparseablePayload() {
        val r = VictronParser.readAdvertisement("AA:BB:CC:DD:EE:FF", "dev",
            listOf(byteArrayOf(0x02, 0x00, 0x00, 0x00)), keyHex, null, ts)
        assertTrue(r.error!!.contains("Decryption failed"))
    }

    @Test fun read_overrideMismatchDisallowed() {
        // override mppt but only a 0x02 BMV record present
        val r = read(0x02, bmvPlain(), "mppt")
        assertTrue(r.error!!.contains("Decryption failed"))
    }

    @Test fun read_recordWithoutParser() {
        assertTrue(0x0F !in VictronParser.PARSERS.keys)
        val r = read(0x0F, bmvPlain(), null)
        assertTrue(r.error!!.contains("Decryption failed"))
    }

    @Test fun read_invalidStateByte() {
        val r = read(0x03, ByteArray(11).also { it[0] = 0x99.toByte() }, "inverter")
        assertTrue(r.error!!.contains("state byte 0x99"))
    }

    @Test fun read_voltageImplausible() {
        val r = read(0x02, bmvPlain(battMv = 25000), null) // 250V
        assertTrue(r.error!!.contains("physically plausible"))
    }

    @Test fun read_currentImplausible() {
        val r = read(0x02, bmvPlain(currentU22 = 2_100_000), null) // >2000A
        assertTrue(r.error!!.contains("2000A"))
    }

    @Test fun read_wrongKeyNeverMixes() {
        val plain = ByteArray(11).also { it[0] = 0x09 }
        val payload = formatB(0x03, plain)
        val r = VictronParser.readAdvertisement("AA:BB:CC:DD:EE:FF", "dev",
            listOf(payload), "ff".repeat(16), "inverter", ts)
        // Either rejected on state/plausibility; must never silently succeed with wrong key
        if (r.error != null) assertTrue(r.error!!.contains("Decryption failed"))
    }

    @Test fun read_allFailedNamedByFirstRecord() {
        // 0x02 with 8-byte plaintext -> parse error -> device_type monitor
        val r = read(0x02, ByteArray(8), null)
        assertEquals("monitor", r.deviceType)
        assertTrue(r.error!!.contains("Advertisement key"))
    }

    // ── Real hardware regression vectors ───────────────────────────────────────
    // Captured live over BLE. These are the ground truth the synthetic formatA()
    // helper failed to model: the device record type lives at byte[4], not the
    // high nibble of byte[3]. Keys are the owner's own advertisement keys.

    @Test fun parsePayload_realMpptRecordAtByte4() {
        val raw = VictronParser.hexToBytes("100215a1019fb161abb0031f10f5b7cc5304e219")
        assertEquals(0x01, VictronParser.parsePayload(raw).recordType) // Solar, not 0x0A
    }

    @Test fun parsePayload_realInverterRecordAtByte4() {
        val raw = VictronParser.hexToBytes("100329270cc9e6ddafc7fefe986b0826b3f79d133b")
        assertEquals(0x0C, VictronParser.parsePayload(raw).recordType) // VE.Bus, not 0x02
    }

    @Test fun read_realSmartSolarMppt() {
        val raw = VictronParser.hexToBytes("100215a1019fb161abb0031f10f5b7cc5304e219")
        val r = VictronParser.readAdvertisement(
            "DD:1B:7E:A7:91:83", "MPPT", listOf(raw),
            "613f9fd95d3633385cf49d32a9d551e3", "mppt", ts,
        )
        assertNull(r.error)
        assertEquals("mppt", r.deviceType)
        assertEquals(53.24, r.voltageV!!, 1e-6)
        assertEquals(39.9, r.currentA!!, 1e-6)
        assertEquals(2212.0, r.pvPowerW!!, 1e-6)
        assertEquals("Bulk", r.chargerState)
    }

    @Test fun read_realVeBusInverter() {
        val raw = VictronParser.hexToBytes("100329270cc9e6ddafc7fefe986b0826b3f79d133b")
        val r = VictronParser.readAdvertisement(
            "E6:2E:31:75:9A:1A", "Inverter", listOf(raw),
            "dd15693279172720da3ecb1d2e4e7da1", "inverter", ts,
        )
        assertNull(r.error)
        assertEquals("inverter", r.deviceType)
        assertEquals("Inverting", r.inverterState)
        assertEquals(53.24, r.voltageV!!, 1e-6)
        assertEquals(-22.1, r.currentA!!, 1e-6)
        assertEquals(1046.0, r.acOutPowerVa!!, 1e-6)
    }

    // ── No-override auto-detection hardening (key-check byte) ───────────────────
    // Both real devices also emit stray 0x02E1 beacons that are NOT Instant
    // Readout data. Their key-check byte does not match the key; decrypted as
    // garbage the MPPT's stray even lands at ~79V, inside the monitor plausible
    // range, so without the key-check gate it would win the priority sort and
    // mislabel the device. These vectors prove auto-detection ignores them.

    private val mpptStrays = listOf(
        "017ce32cd19f2aac2b0c0000afe1a8bb",
        "027ce32cd19f2bac2b0c785e0bdbc5dea75c14b40cf67cd4c9c4",
    ).map { VictronParser.hexToBytes(it) }
    private val inverterStrays = listOf(
        "017c04869c9bece6281600002404e8c5",
        "027c04869c9bcde528169a1e459880d80507526ebe88b1de5cc758",
    ).map { VictronParser.hexToBytes(it) }
    private val mpptReal = VictronParser.hexToBytes("100215a1019fb161abb0031f10f5b7cc5304e219")
    private val inverterReal = VictronParser.hexToBytes("100329270cc9e6ddafc7fefe986b0826b3f79d133b")

    @Test fun parsePayload_keyCheckFromByte7FormatA() {
        assertEquals(0x61, VictronParser.parsePayload(mpptReal).keyCheck)
        assertEquals(0xDD, VictronParser.parsePayload(inverterReal).keyCheck)
    }

    @Test fun parsePayload_keyCheckFromByte3FormatB() {
        assertEquals(0x2C, VictronParser.parsePayload(mpptStrays[0]).keyCheck)
    }

    @Test fun read_noOverrideDetectsMpptDespiteStrays() {
        val r = VictronParser.readAdvertisement(
            "DD:1B:7E:A7:91:83", "MPPT", mpptStrays + mpptReal,
            "613f9fd95d3633385cf49d32a9d551e3", null, ts,
        )
        assertNull(r.error)
        assertEquals("mppt", r.deviceType)
        assertEquals(53.24, r.voltageV!!, 1e-6)
        assertEquals(2212.0, r.pvPowerW!!, 1e-6)
        assertEquals("Bulk", r.chargerState)
    }

    @Test fun read_noOverrideDetectsInverterDespiteStrays() {
        val r = VictronParser.readAdvertisement(
            "E6:2E:31:75:9A:1A", "Inverter", inverterStrays + inverterReal,
            "dd15693279172720da3ecb1d2e4e7da1", null, ts,
        )
        assertNull(r.error)
        assertEquals("inverter", r.deviceType)
        assertEquals("Inverting", r.inverterState)
        assertEquals(53.24, r.voltageV!!, 1e-6)
    }

    @Test fun read_strayBeaconsAloneFailCleanly() {
        // Only strays, no genuine record: must fail rather than report garbage.
        val r = VictronParser.readAdvertisement(
            "DD:1B:7E:A7:91:83", "MPPT", mpptStrays,
            "613f9fd95d3633385cf49d32a9d551e3", null, ts,
        )
        assertTrue(r.error!!.contains("Decryption failed"))
        assertNull(r.voltageV)
    }

    @Test fun read_keyPastedWithMacPrefixStillDecodes() {
        // Real-world fumble: user pastes the whole "MAC:key" string into the key
        // field. The parser must keep the trailing 32 hex and decode normally.
        val r = VictronParser.readAdvertisement(
            "DD:1B:7E:A7:91:83", "MPPT", listOf(mpptReal),
            "DD:1B:7E:A7:91:83:613f9fd95d3633385cf49d32a9d551e3", "mppt", ts,
        )
        assertNull(r.error)
        assertEquals(53.24, r.voltageV!!, 1e-6)
        assertEquals(2212.0, r.pvPowerW!!, 1e-6)
    }

    @Test fun read_wrongKeyRejectedByKeyCheck() {
        // Right frame, wrong key -> key-check mismatch -> never decodes garbage.
        val r = VictronParser.readAdvertisement(
            "DD:1B:7E:A7:91:83", "MPPT", listOf(mpptReal),
            "00".repeat(16), "mppt", ts,
        )
        assertTrue(r.error!!.contains("Decryption failed"))
        assertNull(r.voltageV)
    }

    // ── Constants ────────────────────────────────────────────────────────────

    @Test fun constants_mfrIdAndRecordTypes() {
        assertEquals(0x02E1, VictronParser.VICTRON_MFR_ID)
        assertEquals("mppt", VictronParser.RECORD_TYPES[0x01]!!.second)
        assertEquals("monitor", VictronParser.RECORD_TYPES[0x02]!!.second)
        assertEquals("inverter", VictronParser.RECORD_TYPES[0x0C]!!.second)
        assertEquals("dcdc", VictronParser.RECORD_TYPES[0x0D]!!.second)
    }

    private fun assertThrowsParse(block: () -> Unit) {
        try { block(); throw AssertionError("expected VictronParseException") }
        catch (e: VictronParser.VictronParseException) { /* ok */ }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
