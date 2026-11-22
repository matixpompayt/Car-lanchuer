package pl.mati.caros.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoTest {
    @Test fun stampAddsVersions() {
        val o = Proto.stamp(JSONObject().put("type", "route"), "2.0-beta.1")
        assertEquals(Proto.VERSION, o.getInt("pv"))
        assertEquals("2.0-beta.1", o.getString("app"))
    }

    @Test fun carWithCaps() {
        val st = JSONObject().put("type", "status").put("ver", "2.0-beta.4").put("pv", 2).put("caps", Proto.capsJson())
        val car = Proto.carFromStatus(st)
        assertEquals(2, car.pv)
        assertTrue(car.supports(Proto.Cmd.FUEL_TANK))
        assertTrue(car.supports(Proto.Feat.STOPS8))
        assertFalse(car.older)
    }

    @Test fun oldCarByVersion() {
        val old = Proto.carFromStatus(JSONObject().put("ver", "1.130"))
        assertEquals(1, old.pv)
        assertTrue(old.older)
        assertTrue(old.supports(Proto.Cmd.ROUTE))
        assertFalse(old.supports(Proto.Feat.STOPS8))
        assertFalse(old.supports(Proto.Cmd.FUEL_TANK))

        val mid = Proto.carFromStatus(JSONObject().put("ver", "1.139"))
        assertTrue(mid.supports(Proto.Feat.STOPS8))
        assertFalse(mid.supports(Proto.Cmd.FUEL_TANK))

        val last = Proto.carFromStatus(JSONObject().put("ver", "1.140"))
        assertTrue(last.supports(Proto.Cmd.FUEL_TANK))

        val unknown = Proto.carFromStatus(JSONObject())
        assertTrue(unknown.supports(Proto.Cmd.ROUTE))
        assertFalse(unknown.supports(Proto.Cmd.FUEL_TANK))
    }

    @Test fun capsAreKept() {
        val car = Proto.carFromStatus(JSONObject().put("pv", 3).put("caps", JSONArray().put("route").put("nowa_funkcja")))
        assertTrue(car.newer)
        assertTrue(car.supports("nowa_funkcja"))
        assertFalse(car.supports(Proto.Cmd.FUEL_ADD))
    }

    @Test fun phone() {
        assertEquals(1, Proto.phoneFrom(JSONObject().put("type", "route")).pv)
        val p = Proto.phoneFrom(Proto.stamp(JSONObject(), "2.0-beta.2"))
        assertEquals(2, p.pv)
        assertEquals("2.0-beta.2", p.app)
    }

    @Test fun nack() {
        val n = Proto.nack("teleport", "2.0-beta.1")
        assertEquals("nack", n.getString("type"))
        assertEquals("teleport", n.getString("of"))
        assertEquals(2, n.getInt("pv"))
    }

    @Test fun buildNumbers() {
        assertEquals(140, Proto.buildOf("1.140"))
        assertEquals(0, Proto.buildOf("2.0-beta.7"))
        assertEquals(0, Proto.buildOf("x"))
    }
}
