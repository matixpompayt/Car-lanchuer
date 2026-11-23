package pl.mati.caros.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AckLookTest {
    @Test fun midIsRandomHex() {
        val a = Proto.newMid()
        val b = Proto.newMid()
        assertEquals(10, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertNotEquals(a, b)
    }

    @Test fun ackRoundTrip() {
        val o = JSONObject(Proto.ack("route", "abc123", true, "Trasa ustawiona", "2.0-beta.2").toString())
        val a = Proto.ackFrom(o)
        assertNotNull(a)
        assertEquals("abc123", a!!.mid)
        assertEquals("route", a.of)
        assertTrue(a.ok && a.known)
        assertEquals("Trasa ustawiona", a.text)
    }

    @Test fun nackWithMidIsUnknown() {
        val a = Proto.ackFrom(JSONObject(Proto.nack("future_cmd", "2.0", "m1").toString()))
        assertNotNull(a)
        assertFalse(a!!.known)
        assertFalse(a.ok)
        // nack bez mid (stare zachowanie) – nie dotyczy żadnej śledzonej komendy
        assertNull(Proto.ackFrom(Proto.nack("x", "2.0")))
    }

    @Test fun staleLiveCommands() {
        val now = 10_000_000L
        val old = JSONObject().put("type", "alert").put(Proto.SENT, now - 10 * 60_000)
        val fresh = JSONObject().put("type", "alert").put(Proto.SENT, now - 20_000)
        val route = JSONObject().put("type", "route").put(Proto.SENT, now - 5 * 3600_000)
        val noTime = JSONObject().put("type", "camsam")
        assertTrue(Proto.isStaleLive(old, now))
        assertFalse(Proto.isStaleLive(fresh, now))
        assertFalse(Proto.isStaleLive(route, now)) // trasa wysłana wcześniej ma zadziałać po włączeniu radia
        assertFalse(Proto.isStaleLive(noTime, now))
    }

    @Test fun stampKeepsSentTime() {
        val o = JSONObject().put("type", "route").put(Proto.SENT, 123L)
        Proto.stamp(o, "x")
        assertEquals(123L, o.getLong(Proto.SENT))
        assertTrue(Proto.stamp(JSONObject(), "x").getLong(Proto.SENT) > 0)
    }

    @Test fun carCapsHaveNewFeatures() {
        assertTrue(Proto.Feat.ACK in Proto.CAR_CAPS)
        assertTrue(Proto.Cmd.LOOK in Proto.CAR_CAPS)
        // stare CAR OS: bez potwierdzeń i wyglądu
        val old = Proto.carFromStatus(JSONObject().put("ver", "1.141"))
        assertFalse(old.supports(Proto.Feat.ACK))
        assertFalse(old.supports(Proto.Cmd.LOOK))
    }

    @Test fun lookJson() {
        val l = Look(true, "dark", 0xFF8B5CF6.toInt(), anim = false, t = 42L)
        val back = Look.fromJson(JSONObject(l.toJson().toString()))
        assertEquals(l, back)
        assertTrue(l.toJson().getLong("accent") > 0) // bez znaku w JSON
        assertEquals(l.content, back!!.copy(t = 99L).content)
        assertNull(Look.fromJson(JSONObject().put("tone", "pink")))
        assertNull(Look(false, "auto").toJson().let { Look.fromJson(it) }!!.accent)
    }

    @Test fun fuelTypes() {
        assertEquals(FuelType.PB98, FuelType.of("pb98"))
        assertEquals(FuelType.ON, FuelType.of("nic"))
        assertEquals("diesel", StationPrices.osmTag(FuelType.ON))
        assertNull(StationPrices.tkType(FuelType.LPG))
        assertEquals("pb95", EuFuel.key(FuelType.PB98))
    }
}
