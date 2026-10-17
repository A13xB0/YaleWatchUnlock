package uk.co.hectospark.yaleunlock.credential

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import uk.co.hectospark.yaleunlock.ble.LockMatcher

class ProvisioningParseTest {

    // Same field names and shapes as Home Assistant's yalexs_ble entry data; dummy values.
    private val dummy = """{"address":"aa:bb:cc:dd:ee:ff","key":"00112233445566778899AABBCCDDEEFF","local_name":"ZZ12345","slot":3}"""

    @Test
    fun parsesHomeAssistantEntryData() {
        val c = ProvisioningProvider.parse(dummy.toByteArray())
        assertArrayEquals(LockCredential.parseKeyHex("00112233445566778899aabbccddeeff"), c.keyCopy())
        assertEquals(3, c.slot)
        assertEquals("ZZ12345", c.localName)
        assertEquals("AA:BB:CC:DD:EE:FF", c.address)
    }

    @Test
    fun stripsAdvertisedNulTerminator() {
        // Home Assistant stores the advertised name verbatim, NUL included.
        val withNul = dummy.replace("\"ZZ12345\"", "\"Z1234Z\\u0000\"")
        assertEquals("Z1234Z", ProvisioningProvider.parse(withNul.toByteArray()).localName)
        assert(LockMatcher.matches("Z1234Z", null, "Z1234Z\u0000", "AA:BB:CC:DD:EE:FF"))
        assert(LockMatcher.matches("Z1234Z\u0000", null, "Z1234Z", "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun toStringNeverContainsKey() {
        val text = ProvisioningProvider.parse(dummy.toByteArray()).toString()
        assertFalse(text.contains("0011", ignoreCase = true))
        assertFalse(text.contains("aabb", ignoreCase = true))
    }

    @Test
    fun rejectsMalformedPayloads() {
        for (bad in listOf(
            "not json",
            """{"key":"0011","slot":1}""",
            """{"key":"00112233445566778899aabbccddeeff"}""",
            """{"key":"00112233445566778899aabbccddeeff","slot":300}""",
            """{"key":"zz112233445566778899aabbccddeeff","slot":1}""",
        )) {
            try {
                ProvisioningProvider.parse(bad.toByteArray())
                fail("accepted: $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun matcherPrefersUniqueLocalName() {
        assert(LockMatcher.matches("ZZ12345", "AA:BB:CC:DD:EE:FF", "ZZ12345", "11:22:33:44:55:66"))
        assert(LockMatcher.matches("ZZ12345", "AA:BB:CC:DD:EE:FF", null, "aa:bb:cc:dd:ee:ff"))
        assertFalse(LockMatcher.matches("ZZ12345", null, "ZZ99999", "AA:BB:CC:DD:EE:FF"))
        assertFalse(LockMatcher.matches(null, null, "ZZ12345", "AA:BB:CC:DD:EE:FF"))
    }
}
