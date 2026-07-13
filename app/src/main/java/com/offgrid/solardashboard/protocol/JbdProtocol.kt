package com.offgrid.solardashboard.protocol

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * JBD / Vatrer BMS GATT protocol: packet framing and parsing.
 * Direct port of solar_monitor/jbd.py (pure functions only; BLE I/O lives
 * in the ble package so these parse routines are JVM-unit-testable).
 *
 * Response packet (4-byte header format):
 *   [0]       0xDD  start marker
 *   [1]       reg   register echo
 *   [2]       0x00  status (0x80 = error)
 *   [3]       N     payload length
 *   [4..N+3]  payload (big-endian fields)
 *   [N+4-5]   checksum  (0x10000 - sum(len, payload) & 0xFFFF, big-endian)
 *   [N+6]     0x77  end marker
 */
object JbdProtocol {

    // GATT UUID candidates - tried in order; first match wins.
    data class UuidCandidate(val service: String, val tx: String, val rx: String)

    val UUID_CANDIDATES = listOf(
        UuidCandidate(   // Standard JBD / Xiaoxiang
            "0000ff00-0000-1000-8000-00805f9b34fb",
            "0000ff01-0000-1000-8000-00805f9b34fb",
            "0000ff02-0000-1000-8000-00805f9b34fb",
        ),
        UuidCandidate(   // Vatrer / newer JBD firmware (shared TX/RX char)
            "0000ffe0-0000-1000-8000-00805f9b34fb",
            "0000ffe1-0000-1000-8000-00805f9b34fb",
            "0000ffe1-0000-1000-8000-00805f9b34fb",
        ),
        UuidCandidate(   // Nordic UART Service clone
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
            "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
        ),
    )

    // BLE device name substrings that trigger auto-discovery
    // Names that suggest a JBD-protocol pack, for the discovery "likely battery"
    // flag only. Daly is excluded: it advertises a BMS but speaks a different
    // protocol this app does not read.
    val NAME_KEYWORDS = listOf(
        "jbd", "bms", "xiaoxiang", "overkill", "vatrer", "sp04", "sp16",
        "jiabaida",
    )

    // Register 0x03 "basic info" request command
    val BASIC_INFO_CMD = byteArrayOf(
        0xDD.toByte(), 0xA5.toByte(), 0x03, 0x00, 0xFF.toByte(), 0xFD.toByte(), 0x77,
    )

    const val READ_TIMEOUT_MS = 12_000L
    const val PER_DEVICE_TIMEOUT_MS = 35_000L
    const val NOTIFY_SETTLE_DELAY_MS = 1_000L
    const val MAX_PAYLOAD_LEN = 128

    val PERMANENT_ERRORS = listOf(
        "rejected password", "no compatible jbd service", "bad password",
        "authentication",
    )

    private fun u8(b: Byte): Int = b.toInt() and 0xFF

    private fun beU16(data: ByteArray, off: Int): Int =
        (u8(data[off]) shl 8) or u8(data[off + 1])

    private fun beI16(data: ByteArray, off: Int): Int {
        val v = beU16(data, off)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    /**
     * Compute the 2-byte JBD checksum, stored big-endian.
     * Checksum = (0x10000 - sum(payload)) & 0xFFFF.
     * For outgoing WRITE commands payload is reg + len + data.
     */
    fun checksum(payload: ByteArray): ByteArray {
        var sum = 0
        for (b in payload) sum += u8(b)
        val chk = (0x10000 - sum) and 0xFFFF
        return byteArrayOf((chk shr 8).toByte(), (chk and 0xFF).toByte())
    }

    /**
     * Verify the checksum of a complete JBD response packet.
     * The checksum covers len + payload only (bytes [3..3+len]).
     */
    fun verifyChecksum(data: ByteArray): Boolean {
        if (data.size < 7) return false
        val n = u8(data[3])
        val payloadEnd = 4 + n
        if (data.size < payloadEnd + 3) return false
        val body = data.copyOfRange(3, payloadEnd)
        val expected = checksum(body)
        return data[payloadEnd] == expected[0] && data[payloadEnd + 1] == expected[1]
    }

    /**
     * True when buf holds a complete JBD response packet.
     * Returns false if the length byte exceeds MAX_PAYLOAD_LEN (corrupt frame).
     */
    fun packetComplete(buf: ByteArray): Boolean {
        if (buf.size < 4) return false
        if (u8(buf[0]) != 0xDD) return false
        val n = u8(buf[3])
        if (n > MAX_PAYLOAD_LEN) return false
        return buf.size >= n + 7
    }

    /** Build the password unlock command (register 0x06). */
    fun buildAuthCommand(password: String): ByteArray {
        val pw = password.toByteArray(Charsets.US_ASCII)
        val body = byteArrayOf(0x06, pw.size.toByte()) + pw
        return byteArrayOf(0xDD.toByte(), 0x5A) + body + checksum(body) + byteArrayOf(0x77)
    }

    class JbdParseException(message: String) : Exception(message)

    data class BasicInfo(
        val voltageV: Double,
        val currentA: Double,
        val powerW: Double,
        val capacityPct: Int,
        val remainAh: Double,
        val nominalAh: Double,
        val remainWh: Double,
        val nominalWh: Double,
        val timeToEmptyH: Double?,
        val timeToFullH: Double?,
        val cycleCount: Int,
        val cellCount: Int,
        val swVersion: String,
        val productionDate: String,
        val balanceCells: List<Int>,
        val protectionBits: Int,
        val faults: List<String>,
        val chargeFet: Boolean,
        val dischargeFet: Boolean,
        val tempC: List<Double>,
        val checksumOk: Boolean,
    )

    private val FAULT_NAMES = linkedMapOf(
        0 to "Cell overvoltage",
        1 to "Cell undervoltage",
        2 to "Pack overvoltage",
        3 to "Pack undervoltage",
        4 to "Charge overtemp",
        5 to "Charge undertemp",
        6 to "Discharge overtemp",
        7 to "Discharge undertemp",
        8 to "Charge overcurrent",
        9 to "Discharge overcurrent",
        10 to "Short circuit",
        11 to "IC error",
        12 to "MOS lock",
    )

    private fun round(v: Double, digits: Int): Double {
        var m = 1.0
        repeat(digits) { m *= 10 }
        return (v * m).roundToInt() / m
    }

    /**
     * Parse a JBD/Vatrer BMS basic-info response (register 0x03).
     *
     * Payload fields (big-endian, offset from payload[0]):
     *   0-1   pack voltage      10 mV/LSB
     *   2-3   pack current      10 mA/LSB signed (positive = charging)
     *   4-5   residual capacity 10 mAh/LSB
     *   6-7   nominal capacity  10 mAh/LSB
     *   8-9   cycle count
     *   10-11 production date bitfield
     *   12-15 balance flags (lo, hi)
     *   16-17 protection bitmask
     *   18    software version (BCD nibbles)
     *   19    state of charge %
     *   20    FET status bits
     *   21    cell count
     *   22    NTC sensor count
     *   23+   NTC temperatures 0.1 K/LSB (subtract 2731 for °C)
     */
    fun parseBasicInfo(data: ByteArray): BasicInfo {
        if (data.size < 8)
            throw JbdParseException("Response too short (${data.size}B, need >=8)")
        if (u8(data[0]) != 0xDD)
            throw JbdParseException(
                "Bad start byte 0x%02X (expected 0xDD)".format(u8(data[0])))
        if (u8(data[2]) == 0x80)
            throw JbdParseException(
                "BMS error status: error code 0x%02X".format(u8(data[3])))

        val n = u8(data[3])
        val expectedLen = n + 7
        if (data.size < expectedLen)
            throw JbdParseException(
                "Packet truncated: got ${data.size}B, expected ${expectedLen}B")
        if (u8(data[n + 6]) != 0x77)
            throw JbdParseException(
                "Bad end marker at byte ${n + 6}: 0x%02X (expected 0x77)"
                    .format(u8(data[n + 6])))
        // Checksum mismatch is tolerated (some firmware has quirks) but recorded.
        val checksumOk = verifyChecksum(data)

        val payload = data.copyOfRange(4, 4 + n)
        if (payload.size < 23)
            throw JbdParseException("Payload too short (${payload.size}B, need >=23)")

        val voltageV = beU16(payload, 0) * 10 / 1000.0
        val currentA = beI16(payload, 2) * 10 / 1000.0
        val remainAh = beU16(payload, 4) * 10 / 1000.0
        val nominalAh = beU16(payload, 6) * 10 / 1000.0
        val cycles = beU16(payload, 8)
        val prodRaw = beU16(payload, 10)
        val balLo = beU16(payload, 12)
        val balHi = beU16(payload, 14)
        val protection = beU16(payload, 16)
        val swVer = u8(payload[18])
        val soc = u8(payload[19])
        val fetBits = u8(payload[20])
        val cellCount = u8(payload[21])
        val ntcCount = minOf(u8(payload[22]), (payload.size - 23) / 2)

        // Production date: bits [15:9] = year-2000, [8:5] = month, [4:0] = day
        val prodYear = 2000 + ((prodRaw shr 9) and 0x7F)
        val prodMonth = (prodRaw shr 5) and 0x0F
        val prodDay = prodRaw and 0x1F
        val prodDate = "%d-%02d-%02d".format(prodYear, prodMonth, prodDay)

        // Per-cell balance flags: 32-bit field (balHi << 16 | balLo), LSB = cell 1
        val balBits = (balHi.toLong() shl 16) or balLo.toLong()
        val balance = (0 until cellCount).map { ((balBits shr it) and 1L).toInt() }

        val faults = FAULT_NAMES.filterKeys { (protection and (1 shl it)) != 0 }
            .values.toList()

        val tempsC = (0 until ntcCount).map {
            round((beU16(payload, 23 + it * 2) - 2731) / 10.0, 1)
        }

        val powerW = round(voltageV * currentA, 2)
        val remainWh = round(remainAh * voltageV, 1)
        val nominalWh = round(nominalAh * voltageV, 1)

        val tteH = if (currentA < -0.1) round(remainAh / abs(currentA), 2) else null
        val ttfH = if (currentA > 0.1) round((nominalAh - remainAh) / currentA, 2) else null

        return BasicInfo(
            voltageV = round(voltageV, 3),
            currentA = round(currentA, 3),
            powerW = powerW,
            capacityPct = soc,
            remainAh = round(remainAh, 2),
            nominalAh = round(nominalAh, 2),
            remainWh = remainWh,
            nominalWh = nominalWh,
            timeToEmptyH = tteH,
            timeToFullH = ttfH,
            cycleCount = cycles,
            cellCount = cellCount,
            swVersion = "${swVer shr 4}.${swVer and 0xF}",
            productionDate = prodDate,
            balanceCells = balance,
            protectionBits = protection,
            faults = faults,
            chargeFet = (fetBits and 0x01) != 0,
            dischargeFet = (fetBits and 0x02) != 0,
            tempC = tempsC,
            checksumOk = checksumOk,
        )
    }

    /** Copy parsed BasicInfo fields onto a DeviceReading. */
    fun applyTo(r: DeviceReading, info: BasicInfo) {
        r.voltageV = info.voltageV
        r.currentA = info.currentA
        r.powerW = info.powerW
        r.capacityPct = info.capacityPct
        r.remainAh = info.remainAh
        r.nominalAh = info.nominalAh
        r.remainWh = info.remainWh
        r.nominalWh = info.nominalWh
        r.timeToEmptyH = info.timeToEmptyH
        r.timeToFullH = info.timeToFullH
        r.cycleCount = info.cycleCount
        r.cellCount = info.cellCount
        r.swVersion = info.swVersion
        r.productionDate = info.productionDate
        r.balanceCells = info.balanceCells
        r.protectionBits = info.protectionBits
        r.faults = info.faults
        r.chargeFet = info.chargeFet
        r.dischargeFet = info.dischargeFet
        r.tempC = info.tempC
    }
}
