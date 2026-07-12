package com.offgrid.solardashboard.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of tests/test_jbd_protocol.py: checksum, framing, and basic-info
 * parsing vectors. BLE-I/O tests from the Python suite are not ported (no
 * transport in the pure-parse layer); their coverage lives in ble/JbdGattClient.
 */
class JbdProtocolTest {

    // ── Frame construction helpers (mirror the Python test helpers) ────────────

    private fun u16be(v: Int) = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
    private fun s16be(v: Int): ByteArray {
        val u = if (v < 0) v + 0x10000 else v
        return u16be(u)
    }

    /** Build a register-0x03 basic-info payload (matches make_payload in Python). */
    private fun makePayload(
        voltage: Double = 53.2,
        current: Double = -12.34,
        remainAh: Double = 100.0,
        nominalAh: Double = 200.0,
        cycles: Int = 321,
        prodYear: Int = 2023,
        prodMonth: Int = 6,
        prodDay: Int = 15,
        balLo: Int = 0,
        balHi: Int = 0,
        protection: Int = 0,
        swVer: Int = 0x21,
        soc: Int = 87,
        fet: Int = 0x03,
        cells: Int = 4,
        temps: List<Double> = listOf(25.0, 26.5),
    ): ByteArray {
        val prodRaw = ((prodYear - 2000) shl 9) or (prodMonth shl 5) or prodDay
        var out = u16be((voltage * 100).toInt()) +
            s16be((current * 100).toInt()) +
            u16be((remainAh * 100).toInt()) +
            u16be((nominalAh * 100).toInt()) +
            u16be(cycles) +
            u16be(prodRaw) +
            u16be(balLo) +
            u16be(balHi) +
            u16be(protection) +
            byteArrayOf(swVer.toByte(), soc.toByte(), fet.toByte(), cells.toByte(), temps.size.toByte())
        for (t in temps) out += u16be((t * 10).toInt() + 2731)
        return out
    }

    /** Build a full JBD response frame around a payload. */
    private fun makeFrame(payload: ByteArray, reg: Int = 0x03, status: Int = 0x00): ByteArray {
        val head = byteArrayOf(0xDD.toByte(), reg.toByte(), status.toByte(), payload.size.toByte())
        val body = byteArrayOf(payload.size.toByte()) + payload
        val chk = JbdProtocol.checksum(body)
        return byteArrayOf(0xDD.toByte(), reg.toByte(), status.toByte()) + body + chk + byteArrayOf(0x77)
    }

    private fun hex(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    // ── Checksum ───────────────────────────────────────────────────────────────

    @Test fun checksum_knownVectorBasicInfo() {
        assertEquals("fffd", JbdProtocol.checksum(hex(0x03, 0x00)).toHex())
    }

    @Test fun checksum_emptyPayload() {
        assertEquals("0000", JbdProtocol.checksum(ByteArray(0)).toHex())
    }

    @Test fun checksum_wrapsModulo() {
        assertEquals("ff00", JbdProtocol.checksum(hex(0xFF, 0x01)).toHex())
    }

    @Test fun checksum_bigEndian() {
        assertEquals("ffff", JbdProtocol.checksum(hex(0x01)).toHex())
    }

    @Test fun checksum_basicInfoCmdSelfConsistent() {
        val cmd = JbdProtocol.BASIC_INFO_CMD
        assertEquals(0xDD, cmd[0].toInt() and 0xFF)
        assertEquals(0x77, cmd[cmd.size - 1].toInt() and 0xFF)
        assertEquals(
            cmd.copyOfRange(4, 6).toHex(),
            JbdProtocol.checksum(cmd.copyOfRange(2, 4)).toHex(),
        )
    }

    @Test fun checksum_authCommandVector() {
        // body = 06 04 "0000"
        val body = hex(0x06, 0x04) + "0000".toByteArray()
        assertEquals("ff36", JbdProtocol.checksum(body).toHex())
    }

    // ── verifyChecksum ───────────────────────────────────────────────────────

    @Test fun verifyChecksum_valid() {
        assertTrue(JbdProtocol.verifyChecksum(makeFrame(makePayload())))
    }

    @Test fun verifyChecksum_corrupt() {
        val f = makeFrame(makePayload())
        f[f.size - 3] = (f[f.size - 3].toInt() xor 0xFF).toByte()
        assertFalse(JbdProtocol.verifyChecksum(f))
    }

    @Test fun verifyChecksum_tooShort() {
        assertFalse(JbdProtocol.verifyChecksum(hex(0xDD, 0x03, 0x00)))
        assertFalse(JbdProtocol.verifyChecksum(ByteArray(0)))
    }

    @Test fun verifyChecksum_registerByteExcluded() {
        val f = makeFrame(makePayload())
        f[1] = 0x04 // change register byte; checksum excludes it
        assertTrue(JbdProtocol.verifyChecksum(f))
    }

    @Test fun verifyChecksum_payloadBitFlipFails() {
        val f = makeFrame(makePayload())
        f[5] = (f[5].toInt() xor 0x01).toByte()
        assertFalse(JbdProtocol.verifyChecksum(f))
    }

    // ── packetComplete ─────────────────────────────────────────────────────────

    @Test fun packetComplete_shortEmpty() {
        assertFalse(JbdProtocol.packetComplete(ByteArray(0)))
        assertFalse(JbdProtocol.packetComplete(hex(0xDD, 0x03)))
    }

    @Test fun packetComplete_badStart() {
        val f = makeFrame(makePayload()); f[0] = 0xAA.toByte()
        assertFalse(JbdProtocol.packetComplete(f))
    }

    @Test fun packetComplete_corruptLength() {
        assertFalse(JbdProtocol.packetComplete(hex(0xDD, 0x03, 0x00, JbdProtocol.MAX_PAYLOAD_LEN + 1)))
    }

    @Test fun packetComplete_full() {
        assertTrue(JbdProtocol.packetComplete(makeFrame(makePayload())))
    }

    @Test fun packetComplete_incomplete() {
        val f = makeFrame(makePayload())
        assertFalse(JbdProtocol.packetComplete(f.copyOfRange(0, f.size - 1)))
    }

    @Test fun packetComplete_trailingBytesOk() {
        assertTrue(JbdProtocol.packetComplete(makeFrame(makePayload()) + hex(0x00, 0x00)))
    }

    // ── parseBasicInfo happy path ──────────────────────────────────────────────

    @Test fun parse_coreValues() {
        val d = JbdProtocol.parseBasicInfo(makeFrame(makePayload()))
        assertEquals(53.2, d.voltageV, 1e-6)
        assertEquals(-12.34, d.currentA, 1e-6)
        assertEquals(87, d.capacityPct)
        assertEquals(4, d.cellCount)
        assertEquals(round2(53.2 * -12.34), d.powerW, 1e-6)
    }

    @Test fun parse_capacityAndWh() {
        val d = JbdProtocol.parseBasicInfo(makeFrame(makePayload(remainAh = 100.0, nominalAh = 200.0)))
        assertEquals(100.0, d.remainAh, 1e-6)
        assertEquals(200.0, d.nominalAh, 1e-6)
        assertEquals(5320.0, d.remainWh, 1e-3)
        assertEquals(10640.0, d.nominalWh, 1e-3)
    }

    @Test fun parse_signedCurrent() {
        assertEquals(-50.0, JbdProtocol.parseBasicInfo(makeFrame(makePayload(current = -50.0))).currentA, 1e-6)
        assertEquals(25.5, JbdProtocol.parseBasicInfo(makeFrame(makePayload(current = 25.5))).currentA, 1e-6)
    }

    @Test fun parse_timeToEmptyDischarging() {
        val d = JbdProtocol.parseBasicInfo(makeFrame(makePayload(current = -12.34, remainAh = 100.0)))
        assertEquals(round2(100.0 / 12.34), d.timeToEmptyH!!, 1e-6)
        assertNull(d.timeToFullH)
    }

    @Test fun parse_timeToFullCharging() {
        val d = JbdProtocol.parseBasicInfo(makeFrame(makePayload(current = 20.0, remainAh = 100.0, nominalAh = 200.0)))
        assertEquals(5.0, d.timeToFullH!!, 1e-6)
        assertNull(d.timeToEmptyH)
    }

    @Test fun parse_idleBothNull() {
        val d = JbdProtocol.parseBasicInfo(makeFrame(makePayload(current = 0.0)))
        assertNull(d.timeToEmptyH)
        assertNull(d.timeToFullH)
    }

    @Test fun parse_cycleCount() {
        assertEquals(321, JbdProtocol.parseBasicInfo(makeFrame(makePayload(cycles = 321))).cycleCount)
    }

    @Test fun parse_swVersionNibbleOrder() {
        assertEquals("2.1", JbdProtocol.parseBasicInfo(makeFrame(makePayload(swVer = 0x21))).swVersion)
        assertEquals("1.3", JbdProtocol.parseBasicInfo(makeFrame(makePayload(swVer = 0x13))).swVersion)
    }

    @Test fun parse_productionDate() {
        assertEquals("2023-06-15", JbdProtocol.parseBasicInfo(makeFrame(makePayload(prodYear = 2023, prodMonth = 6, prodDay = 15))).productionDate)
        assertEquals("2000-01-01", JbdProtocol.parseBasicInfo(makeFrame(makePayload(prodYear = 2000, prodMonth = 1, prodDay = 1))).productionDate)
        assertEquals("2099-12-31", JbdProtocol.parseBasicInfo(makeFrame(makePayload(prodYear = 2099, prodMonth = 12, prodDay = 31))).productionDate)
    }

    @Test fun parse_balanceCells() {
        val d = JbdProtocol.parseBasicInfo(makeFrame(makePayload(balLo = 0b0101, cells = 4)))
        assertEquals(listOf(1, 0, 1, 0), d.balanceCells)

        val d2 = JbdProtocol.parseBasicInfo(makeFrame(makePayload(balLo = 1, balHi = 1, cells = 17)))
        assertEquals(17, d2.balanceCells.size)
        assertEquals(1, d2.balanceCells[0])
        assertEquals(1, d2.balanceCells[16])
        assertEquals(2, d2.balanceCells.sum())
    }

    @Test fun parse_fetBits() {
        assertFetPair(0x00, chg = false, dsg = false)
        assertFetPair(0x01, chg = true, dsg = false)
        assertFetPair(0x02, chg = false, dsg = true)
        assertFetPair(0x03, chg = true, dsg = true)
    }

    private fun assertFetPair(fet: Int, chg: Boolean, dsg: Boolean) {
        val d = JbdProtocol.parseBasicInfo(makeFrame(makePayload(fet = fet)))
        assertEquals(chg, d.chargeFet)
        assertEquals(dsg, d.dischargeFet)
    }

    @Test fun parse_trailingGarbageIgnored() {
        val frame = makeFrame(makePayload(voltage = 48.0)) + hex(0xAB, 0xCD)
        assertEquals(48.0, JbdProtocol.parseBasicInfo(frame).voltageV, 1e-6)
    }

    // ── Protection flags ─────────────────────────────────────────────────────

    @Test fun parse_faultsEmpty() {
        val d = JbdProtocol.parseBasicInfo(makeFrame(makePayload(protection = 0)))
        assertEquals(emptyList<String>(), d.faults)
        assertEquals(0, d.protectionBits)
    }

    @Test fun parse_faultsTwoBits() {
        val prot = (1 shl 0) or (1 shl 9)
        val d = JbdProtocol.parseBasicInfo(makeFrame(makePayload(protection = prot)))
        assertEquals(listOf("Cell overvoltage", "Discharge overcurrent"), d.faults)
        assertEquals(0x0201, d.protectionBits)
    }

    @Test fun parse_faultsUndefinedBits() {
        val d = JbdProtocol.parseBasicInfo(makeFrame(makePayload(protection = 0xE000)))
        assertEquals(emptyList<String>(), d.faults)
        assertEquals(0xE000, d.protectionBits)
    }

    // ── NTC temps ────────────────────────────────────────────────────────────

    @Test fun parse_temps() {
        assertEquals(listOf(25.0, 26.5), JbdProtocol.parseBasicInfo(makeFrame(makePayload(temps = listOf(25.0, 26.5)))).tempC)
        assertEquals(listOf(-10.0), JbdProtocol.parseBasicInfo(makeFrame(makePayload(temps = listOf(-10.0)))).tempC)
        assertEquals(listOf(0.0), JbdProtocol.parseBasicInfo(makeFrame(makePayload(temps = listOf(0.0)))).tempC)
        assertEquals(emptyList<Double>(), JbdProtocol.parseBasicInfo(makeFrame(makePayload(temps = emptyList()))).tempC)
        assertEquals(listOf(20.0, 21.5, 23.0, -5.5), JbdProtocol.parseBasicInfo(makeFrame(makePayload(temps = listOf(20.0, 21.5, 23.0, -5.5)))).tempC)
    }

    @Test fun parse_ntcCountClampedWhenLying() {
        // Claim 8 sensors but only supply 2 temps.
        var payload = makePayload(temps = listOf(25.0, 26.0))
        // byte 22 is the NTC count field within the payload.
        payload[22] = 8
        val frame = makeFrame(payload)
        assertEquals(listOf(25.0, 26.0), JbdProtocol.parseBasicInfo(frame).tempC)
    }

    // ── Error paths ────────────────────────────────────────────────────────────

    @Test fun parse_errorsRaise() {
        assertThrows("too short") { JbdProtocol.parseBasicInfo(hex(0xDD, 0x03, 0x00)) }
        assertThrows("start byte") {
            val f = makeFrame(makePayload()); f[0] = 0x77; JbdProtocol.parseBasicInfo(f)
        }
        assertThrows("BMS error") {
            val f = makeFrame(makePayload()); f[2] = 0x80.toByte(); JbdProtocol.parseBasicInfo(f)
        }
        assertThrows("truncated") {
            val f = makeFrame(makePayload()); JbdProtocol.parseBasicInfo(f.copyOfRange(0, f.size - 3))
        }
        assertThrows("end marker") {
            val f = makeFrame(makePayload()); f[f.size - 1] = 0x00; JbdProtocol.parseBasicInfo(f)
        }
        assertThrows("Payload too short") {
            JbdProtocol.parseBasicInfo(makeFrame(ByteArray(10)))
        }
    }

    @Test fun parse_corruptChecksumStillParses() {
        // Checksum quirk tolerance: bad checksum warns, does not raise.
        val f = makeFrame(makePayload(voltage = 51.1))
        f[f.size - 3] = (f[f.size - 3].toInt() xor 0xFF).toByte()
        val d = JbdProtocol.parseBasicInfo(f)
        assertEquals(51.1, d.voltageV, 1e-6)
        assertFalse(d.checksumOk)
    }

    // ── Auth command ─────────────────────────────────────────────────────────

    @Test fun auth_commandVectorDefaultPassword() {
        val expected = hex(0xDD, 0x5A, 0x06, 0x04, 0x30, 0x30, 0x30, 0x30, 0xFF, 0x36, 0x77)
        assertEquals(expected.toHex(), JbdProtocol.buildAuthCommand("0000").toHex())
    }

    @Test fun auth_commandSecretPassword() {
        val cmd = JbdProtocol.buildAuthCommand("secret")
        assertEquals(6, cmd[3].toInt() and 0xFF) // length byte
        assertEquals("secret", String(cmd.copyOfRange(4, 10), Charsets.US_ASCII))
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    private fun assertThrows(fragment: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected exception containing '$fragment'")
        } catch (e: JbdProtocol.JbdParseException) {
            assertTrue("message '${e.message}' should contain '$fragment'",
                e.message!!.contains(fragment, ignoreCase = true))
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
