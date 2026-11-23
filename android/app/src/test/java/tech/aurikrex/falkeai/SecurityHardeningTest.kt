package tech.aurikrex.falkeai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.aurikrex.falkeai.data.FalkeApiClient
import tech.aurikrex.falkeai.executors.PhoneMediaExecutor

class SecurityHardeningTest {
    @Test
    fun enforcesHttpsForRemoteHostsAndAllowsLoopbackDev() {
        assertEquals(
            "https://falkeai-prod.herokuapp.com",
            FalkeApiClient.normalizeHttpsUrl("http://falkeai-prod.herokuapp.com/")
        )
        assertEquals(
            "https://falkeai-prod.herokuapp.com",
            FalkeApiClient.normalizeHttpsUrl("falkeai-prod.herokuapp.com/")
        )
        assertEquals(
            "http://127.0.0.1:8080",
            FalkeApiClient.normalizeHttpsUrl("http://127.0.0.1:8080/")
        )
        assertEquals(
            "http://localhost:8080",
            FalkeApiClient.normalizeHttpsUrl("http://localhost:8080")
        )
    }

    @Test
    fun blocksShortcodeNumbersAndNormalizesNigerianNumbers() {
        assertFalse(PhoneMediaExecutor.isSafeDialOrSmsNumber("32100"))
        assertFalse(PhoneMediaExecutor.isSafeDialOrSmsNumber("911"))
        assertFalse(PhoneMediaExecutor.isSafeDialOrSmsNumber(""))
        assertTrue(PhoneMediaExecutor.isSafeDialOrSmsNumber("+2348011122233"))
        assertTrue(PhoneMediaExecutor.isSafeDialOrSmsNumber("08011122233"))
        assertEquals("+2348011122233", PhoneMediaExecutor.normalizeDialNumber("0801 112 2233"))
    }
}
