package com.batterywidget.app.data

import com.batterywidget.app.data.AutoConnectPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun hex(s: String): ByteArray =
    s.replace(">", "").replace("<", "").trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

/** Real AirPods broadcasts recorded by CAPod's test suite, with the lid state CAPod expects. */
class LidDecodingTest {
    private fun lidOf(s: String) = PodsLogic.proximity(hex(s))?.lid

    @Test fun `AirPods Pro, lid open (CAPod captures)`() {
        assertEquals(Lid.OPEN, lidOf("07 19 01 0E 20 55 AA B4 31 00 00 A1 D0 BD 82 D3 52 86 CA FC 11 62 DC 42 C6 92 8E"))
        assertEquals(Lid.OPEN, lidOf("07 19 01 0E 20 51 9A 93 31 00 00 95 D0 A5 D7 E3 F4 F1 38 38 99 61 3B 57 95 37 B7"))
        assertEquals(Lid.OPEN, lidOf("07 19 01 0E 20 71 A9 92 31 00 00 DB 48 7F 32 8C CE 80 6F D9 27 98 D6 76 45 9D 62"))
    }

    @Test fun `AirPods Pro, lid closed (CAPod captures)`() {
        assertEquals(Lid.CLOSED, lidOf("07 19 01 0E 20 55 AA B4 39 00 00 08 A6 DB 99 E0 5E 14 85 E5 C2 0B 68 D7 FF C3 A1"))
        assertEquals(Lid.CLOSED, lidOf("07 19 01 0E 20 55 AA B4 38 00 00 F3 F7 08 3B 98 09 C0 DD E4 BD BD 84 55 56 8B 81"))
        assertEquals(Lid.CLOSED, lidOf("07 19 01 0E 20 51 9A 93 38 00 00 3A D8 85 76 B0 91 48 31 DA FF 6C 4A 2B C2 67 F4"))
        assertEquals(Lid.CLOSED, lidOf("07 19 01 0E 20 71 A9 92 38 00 00 44 91 C4 8B 85 98 DD 55 4E 6A CA BC B5 CA 8D 37"))
    }

    @Test fun `AirPods 5 Wireless captures`() {
        assertEquals(Lid.CLOSED, lidOf("07 19 01 30 20 55 A9 B9 5A 00 04 7E 88 A7 35 54 C2 34 41 4C 46 EC 23 9E BD A6 14"))
        assertEquals(Lid.OPEN, lidOf("07 19 01 30 20 25 9A B9 53 00 00 2A 46 EA D2 5A A6 33 38 D8 23 69 BD 57 C9 65 F7"))
        // Sent by the pod outside the case: its lid byte is stale, so it must not count.
        assertEquals(Lid.UNKNOWN, lidOf("07 19 01 30 20 13 A9 A9 13 00 04 55 94 59 C6 1C C9 6D 13 4F EA D9 52 71 AE EB E4"))
        assertEquals(Lid.OUT_OF_CASE, lidOf("07 19 01 30 20 0B A9 8F 13 00 05 EA DB 3C 0E 2E 09 42 A7 22 FF B6 CD D7 FD E8 1A"))
    }

    @Test fun `AirPods Pro 3 in ears (CAPod capture)`() {
        val f = PodsLogic.proximity(hex("07 19 01 27 20 0B 99 8F 11 00 05 43 A8 85 17 07 FF 41 62 2C FE 5E 20 08 1A 52 40"))
        assertNotNull(f)
        assertEquals(0x2720, f!!.model)
        assertEquals(Lid.OUT_OF_CASE, f.lid)
    }

    @Test fun `status and lid byte table (CAPod DualApplePodsTest)`() {
        assertEquals(Lid.UNKNOWN, PodsLogic.lid(0x10, 0x51))
        assertEquals(Lid.CLOSED, PodsLogic.lid(0x04, 0x5A))
        assertEquals(Lid.OPEN, PodsLogic.lid(0x40, 0x51))
        assertEquals(Lid.OUT_OF_CASE, PodsLogic.lid(0x2B, 0x11))
        assertEquals(Lid.OUT_OF_CASE, PodsLogic.lid(0x20, 0x5A))
        assertEquals(Lid.CLOSED, PodsLogic.lid(0x73, 0x39))
        assertEquals(Lid.UNKNOWN, PodsLogic.lid(0x13, 0x11))
        assertEquals(Lid.CLOSED, PodsLogic.lid(0x53, 0x39))
        assertEquals(Lid.UNKNOWN, PodsLogic.lid(0x33, 0x02))
        assertEquals(Lid.OPEN, PodsLogic.lid(0x15, 0x31))
        assertEquals(Lid.OPEN, PodsLogic.lid(0x04, 0x31))
    }

    @Test fun `status message found after another message`() {
        val f = PodsLogic.proximity(hex("10 05 01 18 4C 2F 3A 07 19 01 0E 20 55 AA B4 31 00 00 A1 D0 BD 82 D3 52 86 CA FC 11 62 DC 42 C6 92 8E"))
        assertEquals(Lid.OPEN, f?.lid)
    }

    @Test fun `other messages are not AirPods status`() {
        // Find My beacon from your own debug line: not a status message.
        assertNull(PodsLogic.proximity(hex("12 19 00 A0 03 C2 F8 61 60 53 42 DF 9A AD FE 8D 64 94 DC 14")))
        // A 0x07 message in another format (sent while connecting; CAPod #603).
        assertNull(PodsLogic.proximity(hex("07 19 07 0E 20 55 AA B4 31 00 00 A1 D0 BD 82 D3 52 86 CA FC 11 62 DC 42 C6 92 8E")))
        // Cut short.
        assertNull(PodsLogic.proximity(hex("07 19 01 0E 20")))
        assertNull(PodsLogic.proximity(ByteArray(0)))
    }
}

/** Timelines played through the auto-connect decision, like the real phone would see them. */
class AutoConnectSimulationTest {
    private class Sim {
        val policy = AutoConnectPolicy()
        var t = 1_000_000L
        var sound = false
        var connecting = false
        val attempts = mutableListOf<Pair<Long, String>>()

        /** AirPods broadcast every 200 ms for [ms] with this lid state. */
        fun broadcast(ms: Long, lid: Lid, rssi: Int = -55, enabled: Boolean = true, step: Long = 200) {
            val end = t + ms
            while (t < end) {
                val d = policy.onFrame(t, lid, rssi, enabled, sound, connecting)
                if (d is Decision.Connect) attempts += t to d.reason
                t += step
            }
        }
        fun silence(ms: Long) { t += ms }
    }

    @Test fun `lid opens - connects at once, retries at 5 s and then every 20 s`() {
        val s = Sim()
        s.broadcast(3_000, Lid.CLOSED) // the few closed-lid messages after shutting it
        assertTrue(s.attempts.isEmpty())
        s.silence(60_000)
        val opened = s.t
        s.broadcast(40_000, Lid.OPEN)
        val at = s.attempts.map { it.first - opened }
        assertEquals("case opened", s.attempts.first().second)
        assertEquals(0L, at[0])
        assertTrue("quick retry about 5 s later, was ${at[1]}", at[1] in 5_000..5_400)
        assertTrue("then 20 s later, was ${at[2] - at[1]}", at[2] - at[1] in 20_000..20_400)
        assertEquals(3, at.size)
    }

    @Test fun `stops as soon as Android is connecting or connected`() {
        val s = Sim()
        s.broadcast(1_000, Lid.OPEN)
        assertEquals(1, s.attempts.size)
        s.connecting = true
        s.broadcast(10_000, Lid.OPEN)
        assertEquals("never interrupts a connection on its way", 1, s.attempts.size)
        s.connecting = false; s.sound = true
        s.broadcast(60_000, Lid.OPEN)
        assertEquals(1, s.attempts.size)
    }

    @Test fun `closing and reopening the lid connects again straight away`() {
        val s = Sim()
        s.broadcast(1_000, Lid.OPEN)
        s.broadcast(2_000, Lid.CLOSED)
        val reopened = s.t
        s.broadcast(1_000, Lid.OPEN)
        assertEquals(2, s.attempts.size)
        assertEquals("case opened", s.attempts[1].second)
        assertTrue(s.attempts[1].first - reopened < 400)
    }

    @Test fun `never connects while the lid is shut`() {
        val s = Sim()
        s.broadcast(120_000, Lid.CLOSED)
        assertTrue(s.attempts.isEmpty())
    }

    @Test fun `out of the case and not connected - connects, then every 20 s`() {
        val s = Sim()
        s.broadcast(45_000, Lid.OUT_OF_CASE)
        assertEquals(listOf("AirPods out of the case", "retry 1", "retry 2"), s.attempts.map { it.second })
    }

    @Test fun `stale lid byte from the pod outside the case doesn't count as opening`() {
        val s = Sim()
        s.broadcast(1_000, Lid.OPEN)
        s.broadcast(1_000, Lid.UNKNOWN)
        assertEquals(1, s.attempts.size)
    }

    @Test fun `switched off - nothing happens`() {
        val s = Sim()
        s.broadcast(60_000, Lid.OPEN, enabled = false)
        assertTrue(s.attempts.isEmpty())
    }

    @Test fun `someone else's AirPods far away are ignored`() {
        val s = Sim()
        s.broadcast(60_000, Lid.OPEN, rssi = -90)
        assertTrue(s.attempts.isEmpty())
    }

    @Test fun `lid opens while the phone only hears now and then (slow scan)`() {
        val s = Sim()
        s.broadcast(2_000, Lid.OPEN, step = 1_500)
        assertEquals(1, s.attempts.size)
    }
}

/** When the widget shows AirPods, and when it goes back to the phone alone. */
class WidgetRevertSimulationTest {
    private val v = PodsVisibility

    @Test fun `your screenshot - AirPods put away after a manual connect`() {
        val t0 = 0L
        // Connected with the button; the AirPods' own channel reported 80% and case 5%.
        assertEquals(Long.MAX_VALUE, v.shownUntil(t0 + 10_000, t0, PodsSource.DIRECT, connected = true, directLive = true))
        // Lid closed: channel drops, then the link. From that moment it must be the phone only.
        assertNotNull(v.shownUntil(t0 + 20_000, t0, PodsSource.DIRECT, connected = true, directLive = false))
        assertNull(v.shownUntil(t0 + 21_000, t0, PodsSource.DIRECT, connected = false, directLive = false))
        // Three minutes later (your second screenshot): still nothing.
        assertNull(v.shownUntil(t0 + 200_000, t0, PodsSource.DIRECT, connected = false, directLive = false))
    }

    @Test fun `case open next to the phone, not connected - shows for a minute after the last message`() {
        val heard = 0L
        val until = v.shownUntil(heard + 1_000, heard, PodsSource.BROADCAST, connected = false, directLive = false)
        assertEquals(PodsVisibility.NEARBY_MS, until)
        assertNull(v.shownUntil(heard + PodsVisibility.NEARBY_MS, heard, PodsSource.BROADCAST, connected = false, directLive = false))
    }

    @Test fun `connected without the direct channel - broadcast stays up to 5 minutes`() {
        assertNotNull(v.shownUntil(4 * 60_000L, 0, PodsSource.BROADCAST, connected = true, directLive = false))
        assertNull(v.shownUntil(5 * 60_000L, 0, PodsSource.BROADCAST, connected = true, directLive = false))
    }

    @Test fun `old direct reading never comes back after reconnecting much later`() {
        assertNull(v.shownUntil(60 * 60_000L, 0, PodsSource.DIRECT, connected = true, directLive = false))
    }
}

/** Android ignores more than 5 scan starts in 30 s: a burst of events must stay under it. */
class ScanBudgetSimulationTest {
    @Test fun `burst of events never goes over Android's limit`() {
        val budget = ScanBudget()
        val starts = mutableListOf<Long>()
        // Screen on, app opened (restart + background scan), AirPods link, widget tap, link drops,
        // the 20 s follow-up refresh, screen on again: all within 25 seconds, as in your test.
        val wanted = listOf(0L, 500, 600, 4_000, 6_000, 9_000, 20_000, 24_000, 25_000)
        for (t in wanted) if (budget.waitMs(t, starts) == 0L) starts += t
        for (t in starts) {
            val inWindow = starts.count { it in t until t + 30_000 }
            assertTrue("$inWindow starts within 30 s of $t", inWindow <= 4)
        }
        // A start that was held back is allowed again once the oldest one is 30 s old.
        val wait = budget.waitMs(25_000, starts)
        assertEquals(starts[starts.size - 4] + 30_000 - 25_000, wait)
        assertEquals(0L, budget.waitMs(25_000 + wait, starts))
    }

    @Test fun `normal use is never held back`() {
        val budget = ScanBudget()
        val starts = mutableListOf<Long>()
        var t = 0L
        repeat(50) {
            assertEquals(0L, budget.waitMs(t, starts))
            starts += t
            t += 60_000 // the service's one-minute check
        }
    }
}

/** Your 00:31 log: the AirPods link up by themselves for 3 s when the lid opens, with no broadcasts. */
class LinkedByThemselvesSimulationTest {
    @Test fun `AirPods link up on their own - connect at once, once more 1,5 s later`() {
        val p = AutoConnectPolicy()
        val first = p.onLinked(0, enabled = true, soundConnected = false, connecting = false, weAskedRecently = false)
        assertEquals("AirPods linked by themselves", (first as Decision.Connect).reason)
        val again = p.onLinked(1_900, enabled = true, soundConnected = false, connecting = false, weAskedRecently = true, retry = true)
        assertTrue(again is Decision.Connect)
    }

    @Test fun `link we caused with the button is not doubled`() {
        val p = AutoConnectPolicy()
        assertTrue(p.onLinked(0, true, false, false, weAskedRecently = true) is Decision.Skip)
    }

    @Test fun `already connected or switched off - nothing`() {
        assertTrue(AutoConnectPolicy().onLinked(0, true, soundConnected = true, connecting = false, weAskedRecently = false) is Decision.Skip)
        assertTrue(AutoConnectPolicy().onLinked(0, enabled = false, soundConnected = false, connecting = false, weAskedRecently = false) is Decision.Skip)
    }

    @Test fun `link flapping twice within 3 s only tries once`() {
        val p = AutoConnectPolicy()
        assertTrue(p.onLinked(0, true, false, false, false) is Decision.Connect)
        assertTrue(p.onLinked(2_500, true, false, false, false) is Decision.Skip)
        assertTrue(p.onLinked(2 * 60_000L, true, false, false, false) is Decision.Connect) // next lid open
    }
}

class RetryWindowTest {
    @Test fun `tries right away, then every 3 s, and stops once connected`() {
        val w = RetryWindow()
        assertTrue(w.start(0, "case opened"))
        assertEquals(RetryWindow.Step.Connect, w.tick(0, true, false, false))
        assertEquals(RetryWindow.Step.Wait, w.tick(1_000, true, false, false))
        assertEquals(RetryWindow.Step.Connect, w.tick(3_000, true, false, false))
        assertEquals(RetryWindow.Step.Stop("connected"), w.tick(6_000, true, true, false))
        assertTrue(!w.active)
    }

    @Test fun `never asks again while Android is connecting`() {
        val w = RetryWindow()
        w.start(0, "x")
        assertEquals(RetryWindow.Step.Connect, w.tick(0, true, false, false))
        assertEquals(RetryWindow.Step.Wait, w.tick(3_000, true, false, true))
        assertEquals(RetryWindow.Step.Wait, w.tick(6_000, true, false, true))
    }

    @Test fun `never tries when already connected`() {
        val w = RetryWindow()
        w.start(0, "x")
        assertEquals(RetryWindow.Step.Stop("connected"), w.tick(0, true, true, false))
    }

    @Test fun `gives up after 30 s`() {
        val w = RetryWindow()
        w.start(0, "x")
        var t = 0L
        while (t < 30_000) { w.tick(t, true, false, false); t += 3_000 }
        assertEquals(RetryWindow.Step.Stop("gave up after 30 s"), w.tick(30_000, true, false, false))
    }
}

class DisconnectPauseTest {
    @Test fun `stays paused while the AirPods keep talking, wakes when the case closes`() {
        val p = DisconnectPause()
        p.onDisconnected()
        p.onFrame(1_000, Lid.OUT_OF_CASE); p.onFrame(2_000, Lid.OUT_OF_CASE)
        assertTrue(p.paused)
        p.onFrame(3_000, Lid.CLOSED)
        assertTrue(!p.paused)
    }

    @Test fun `a quiet gap means the case was closed and reopened`() {
        val p = DisconnectPause()
        p.onFrame(0, Lid.UNKNOWN)
        p.onDisconnected()
        p.onFrame(1_000, Lid.UNKNOWN)
        assertTrue(p.paused)
        p.onFrame(12_000, Lid.OPEN)
        assertTrue(!p.paused)
    }
}

class ScreenOnWatchTest {
    private fun ScreenOnWatch.at(now: Long, screenOn: Boolean = true, connected: Boolean = false, connecting: Boolean = false,
                                 otherAudio: Boolean = false, seen: Long? = now) =
        check(now, screenOn, true, connected, connecting, otherAudio, seen)

    @Test fun `tries every 3 s while the screen is on and the AirPods are around`() {
        val w = ScreenOnWatch()
        assertNull(w.at(0))
        assertEquals("waiting", w.at(1_000))
        assertNull(w.at(3_000))
        assertNull(w.at(6_000))
    }

    @Test fun `never tries when connected, connecting, screen off or another headset is on`() {
        val w = ScreenOnWatch()
        assertEquals("already connected", w.at(0, connected = true))
        assertEquals("Android is connecting", w.at(0, connecting = true))
        assertEquals("screen off", w.at(0, screenOn = false))
        assertEquals("another headset or car is connected", w.at(0, otherAudio = true))
    }

    @Test fun `waits a minute after a disconnect, and needs the AirPods nearby`() {
        val w = ScreenOnWatch()
        w.onDisconnected(0)
        assertEquals("just disconnected", w.at(30_000))
        assertNull(w.at(61_000))
        assertEquals("AirPods not close to the phone", ScreenOnWatch().at(200_000, seen = 100_000))
        assertEquals("AirPods not close to the phone", ScreenOnWatch().at(10_000, seen = 4_000))
    }
}
