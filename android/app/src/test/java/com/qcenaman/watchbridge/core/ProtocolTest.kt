package com.qcenaman.watchbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private fun info(
        importance: Int = 3, ongoing: Boolean = false, dnd: Boolean = true,
        muted: Boolean = false, pkg: String = "com.whatsapp",
    ) = NotifInfo(pkg, ongoing, false, importance, dnd, muted)

    @Test fun soundOnPhoneBuzzesWatch() =
        assertEquals(Delivery.BUZZ, Rules.decide(info(importance = 4), "me"))

    @Test fun lowImportanceIsSilent() =
        assertEquals(Delivery.SILENT, Rules.decide(info(importance = 2), "me"))

    @Test fun doNotDisturbSilences() =
        assertEquals(Delivery.SILENT, Rules.decide(info(dnd = false), "me"))

    @Test fun ongoingMutedAndOwnAreSkipped() {
        assertEquals(Delivery.SKIP, Rules.decide(info(ongoing = true), "me"))
        assertEquals(Delivery.SKIP, Rules.decide(info(muted = true), "me"))
        assertEquals(Delivery.SKIP, Rules.decide(info(pkg = "me"), "me"))
        assertEquals(Delivery.SKIP, Rules.decide(info(importance = 1), "me"))
    }

    @Test fun framerSplitsAndMarksLast() {
        val data = ByteArray(45) { it.toByte() }
        val chunks = Framer.split(7, data, 20)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it[0] == 7.toByte() })
        assertEquals(listOf<Byte>(0, 0, 1), chunks.map { it[1] })
        val joined = chunks.flatMap { it.drop(2) }.toByteArray()
        assertTrue(data.contentEquals(joined))
    }

    @Test fun emptyMessageIsOneChunk() =
        assertEquals(1, Framer.split(1, ByteArray(0), 20).size)

    @Test fun jsonEscapes() {
        val json = Message.Posted("a\"b", "App", "Hi\nthere", "x\\y", 5, true).toJson()
        assertEquals(
            "{\"t\":\"n\",\"id\":\"a\\\"b\",\"app\":\"App\",\"title\":\"Hi\\nthere\"," +
                "\"text\":\"x\\\\y\",\"time\":5,\"buzz\":true}",
            json,
        )
    }

    @Test fun parsesCommands() {
        assertEquals(Command.Dismiss("0|com.x|1|null|10"), Command.parse("{\"c\":\"dismiss\",\"id\":\"0|com.x|1|null|10\"}"))
        assertEquals(Command.FindPhone, Command.parse("{\"c\":\"find\"}"))
        assertEquals(Command.Sync, Command.parse("{\"c\":\"sync\"}"))
        assertEquals(null, Command.parse("{\"c\":\"nope\"}"))
    }
}
