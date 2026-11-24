package pl.mati.caros.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PartsTest {
    private fun bigRoute(n: Int): String {
        val r = Random(7)
        val via = JSONArray()
        repeat(n) { i ->
            via.put(JSONObject().put("n", "Stacja ${r.nextLong()} $i").put("d", "Ulica ${r.nextInt()} ${r.nextLong()}").put("lat", 45 + r.nextDouble()).put("lon", 5 + r.nextDouble()))
        }
        return JSONObject().put("type", "route").put("mid", "abc").put("n", "Cel").put("lat", 52.0).put("lon", 14.0).put("via", via).toString()
    }

    @Test
    fun splitAndJoinInAnyOrder() {
        val plain = bigRoute(120)
        val parts = Parts.split(plain, chunk = 500)
        assertTrue(parts.size > 2)
        val j = Parts.Joiner()
        val shuffled = parts.shuffled(Random(3))
        shuffled.dropLast(1).forEach { assertNull(j.add(it)) }
        assertEquals(plain, j.add(shuffled.last()))
    }

    @Test
    fun partsFitRelayLimitAfterEncryption() {
        val key = ByteArray(32) { it.toByte() }
        Parts.split(bigRoute(300)).forEach { p ->
            val body = RelayCrypto.encrypt(key, p.put("pv", 3).put("app", "2.0").toString())
            assertTrue("część ma ${body.length} znaków", body.length < 4000)
        }
    }

    @Test
    fun badPartIgnored() {
        val j = Parts.Joiner()
        assertNull(j.add(JSONObject().put("type", "pt").put("id", "x").put("i", 5).put("n", 2).put("d", "")))
    }
}
