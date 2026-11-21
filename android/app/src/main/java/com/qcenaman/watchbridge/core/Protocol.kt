package com.qcenaman.watchbridge.core

import java.util.UUID

object Protocol {
    val SERVICE: UUID = UUID.fromString("7a1e0001-5c2b-4d1a-9f3e-2b8a6c0d4e51")
    val EVENTS: UUID = UUID.fromString("7a1e0002-5c2b-4d1a-9f3e-2b8a6c0d4e51")
    val COMMAND: UUID = UUID.fromString("7a1e0003-5c2b-4d1a-9f3e-2b8a6c0d4e51")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/** What the watch should do with a phone notification. */
enum class Delivery { SKIP, SILENT, BUZZ }

/** Facts about a posted notification, gathered from the system. */
data class NotifInfo(
    val pkg: String,
    val ongoing: Boolean,
    val groupSummary: Boolean,
    /** NotificationManager importance of the channel (0..5). */
    val importance: Int,
    /** False when Do Not Disturb would hide or silence it on the phone. */
    val passesDnd: Boolean,
    val mutedByUser: Boolean,
)

object Rules {
    private const val IMPORTANCE_LOW = 2
    private const val IMPORTANCE_DEFAULT = 3

    /** Mirror the phone's own behaviour: what makes a sound on the phone buzzes the watch. */
    fun decide(n: NotifInfo, ownPkg: String): Delivery = when {
        n.pkg == ownPkg -> Delivery.SKIP
        n.ongoing || n.groupSummary -> Delivery.SKIP
        n.mutedByUser -> Delivery.SKIP
        n.importance < IMPORTANCE_LOW -> Delivery.SKIP
        !n.passesDnd -> Delivery.SILENT
        n.importance >= IMPORTANCE_DEFAULT -> Delivery.BUZZ
        else -> Delivery.SILENT
    }
}

sealed class Message {
    data class Posted(
        val id: String, val app: String, val title: String,
        val text: String, val time: Long, val buzz: Boolean,
    ) : Message()

    data class Removed(val id: String) : Message()
    data class Battery(val percent: Int, val charging: Boolean) : Message()

    fun toJson(): String = when (this) {
        is Posted -> "{\"t\":\"n\",\"id\":${q(id)},\"app\":${q(app)},\"title\":${q(title)}," +
            "\"text\":${q(text)},\"time\":$time,\"buzz\":$buzz}"
        is Removed -> "{\"t\":\"r\",\"id\":${q(id)}}"
        is Battery -> "{\"t\":\"b\",\"pct\":$percent,\"chg\":$charging}"
    }

    companion object {
        fun q(s: String): String {
            val b = StringBuilder("\"")
            for (c in s) when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\n' -> b.append("\\n")
                c == '\r' -> b.append("\\r")
                c == '\t' -> b.append("\\t")
                c < ' ' -> b.append(String.format("\\u%04x", c.code))
                else -> b.append(c)
            }
            return b.append('"').toString()
        }
    }
}

/** Commands written by the watch. */
sealed class Command {
    data class Dismiss(val id: String) : Command()
    object FindPhone : Command()
    object Sync : Command()

    companion object {
        private val cmd = Regex("\"c\"\\s*:\\s*\"(\\w+)\"")
        private val id = Regex("\"id\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        fun parse(json: String): Command? = when (cmd.find(json)?.groupValues?.get(1)) {
            "dismiss" -> id.find(json)?.groupValues?.get(1)
                ?.replace("\\\"", "\"")?.replace("\\\\", "\\")?.let { Dismiss(it) }
            "find" -> FindPhone
            "sync" -> Sync
            else -> null
        }
    }
}

/**
 * Splits a message into BLE-sized chunks. Each chunk is
 * [messageId, flags] + payload, where flags bit 0 marks the last chunk.
 */
object Framer {
    const val HEADER = 2

    fun split(messageId: Int, data: ByteArray, chunkSize: Int): List<ByteArray> {
        require(chunkSize > HEADER) { "chunkSize too small" }
        val body = chunkSize - HEADER
        val count = maxOf(1, (data.size + body - 1) / body)
        return (0 until count).map { i ->
            val from = i * body
            val to = minOf(data.size, from + body)
            val last = i == count - 1
            byteArrayOf((messageId and 0xFF).toByte(), if (last) 1 else 0) + data.copyOfRange(from, to)
        }
    }

    /** Keep text short enough for a small screen and a quick BLE transfer. */
    fun clip(s: String, max: Int = 400): String = if (s.length <= max) s else s.take(max - 1) + "…"
}
