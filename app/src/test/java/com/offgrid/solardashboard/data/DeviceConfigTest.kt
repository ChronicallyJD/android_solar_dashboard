package com.offgrid.solardashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-Kotlin tests for device config normalisation and JSON serialisation. */
class DeviceConfigTest {

    private val realKey = "613f9fd95d3633385cf49d32a9d551e3"

    // ── normaliseMac ────────────────────────────────────────────────────────

    @Test fun mac_lowercaseColonToUpper() {
        assertEquals("AA:BB:CC:DD:EE:FF", DeviceConfig.normaliseMac("aa:bb:cc:dd:ee:ff"))
    }

    @Test fun mac_bareHexGetsColons() {
        assertEquals("A4:C1:37:05:E9:C6", DeviceConfig.normaliseMac("a4c13705e9c6"))
    }

    @Test fun mac_dashSeparatorsNormalised() {
        assertEquals("A4:C1:37:05:E9:C6", DeviceConfig.normaliseMac("A4-C1-37-05-E9-C6"))
    }

    @Test fun mac_whitespaceTrimmed() {
        assertEquals("A4:C1:37:05:E9:C6", DeviceConfig.normaliseMac("  a4:c1:37:05:e9:c6  "))
    }

    @Test fun mac_invalidPassesThroughUppercased() {
        // Not 12 hex chars: left as trimmed/uppercased rather than mangled.
        assertEquals("NOT-A-MAC", DeviceConfig.normaliseMac(" not-a-mac "))
    }

    // ── normaliseKey ────────────────────────────────────────────────────────

    @Test fun key_cleanPassesThrough() {
        assertEquals(realKey, DeviceConfig.normaliseKey(realKey))
    }

    @Test fun key_uppercaseLowered() {
        assertEquals(realKey, DeviceConfig.normaliseKey(realKey.uppercase()))
    }

    @Test fun key_macPrefixDropped() {
        // Whole "MAC:key" pasted into the key field keeps the trailing 32 hex.
        assertEquals(realKey, DeviceConfig.normaliseKey("DD:1B:7E:A7:91:83:$realKey"))
    }

    @Test fun key_spacesAndDashesStripped() {
        assertEquals(realKey, DeviceConfig.normaliseKey("613f 9fd9 5d36 3338 5cf4 9d32 a9d5 51e3"))
    }

    @Test fun key_blankIsNull() {
        assertNull(DeviceConfig.normaliseKey(""))
        assertNull(DeviceConfig.normaliseKey("   "))
        assertNull(DeviceConfig.normaliseKey(null))
    }

    @Test fun key_nonHexOnlyIsNull() {
        assertNull(DeviceConfig.normaliseKey("zzzz"))
    }

    // ── JSON round-trip ───────────────────────────────────────────────────────

    @Test fun json_victronRoundTrip() {
        val d = DeviceConfig(
            kind = "victron", name = "MPPT 1", mac = "DD:1B:7E:A7:91:83",
            encKey = realKey, deviceType = "mppt",
        )
        assertEquals(d, DeviceConfig.fromJson(d.toJson()))
    }

    @Test fun json_bmsRoundTrip() {
        val d = DeviceConfig(
            kind = "bms", name = "Batt0", mac = "A4:C1:37:05:E9:C6",
            bleName = "BATT0", password = "0000",
        )
        assertEquals(d, DeviceConfig.fromJson(d.toJson()))
    }

    @Test fun json_nullOptionalsOmitted() {
        val d = DeviceConfig(kind = "bms", name = "x", mac = "AA:BB:CC:DD:EE:FF")
        val back = DeviceConfig.fromJson(d.toJson())
        assertNull(back.encKey)
        assertNull(back.password)
        assertNull(back.deviceType)
        assertNull(back.bleName)
    }

    @Test fun json_listRoundTrip() {
        val list = listOf(
            DeviceConfig("bms", "Batt0", "A4:C1:37:05:E9:C6", password = "0000"),
            DeviceConfig("victron", "MPPT", "DD:1B:7E:A7:91:83", encKey = realKey, deviceType = "mppt"),
        )
        assertEquals(list, DeviceConfig.listFromJson(DeviceConfig.listToJson(list)))
    }

    @Test fun json_emptyStringIsEmptyList() {
        assertEquals(emptyList<DeviceConfig>(), DeviceConfig.listFromJson(""))
    }
}
