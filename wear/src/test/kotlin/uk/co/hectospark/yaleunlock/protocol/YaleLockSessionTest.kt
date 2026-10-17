package uk.co.hectospark.yaleunlock.protocol

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Replays a transcript recorded from the unmodified yalexs-ble 3.3.1 code
 * (tools/gen_vectors.py) and asserts the Kotlin port puts identical bytes on
 * the air, in the identical order, and decodes the identical replies.
 */
class YaleLockSessionTest {

    private data class Event(val type: String, val channel: YaleChannel?, val data: ByteArray?)

    private val transcript: JSONObject = JSONObject(
        javaClass.classLoader!!.getResource("yalexs_ble_3_3_1_transcript.json")!!.readText(),
    )
    private val key = hex(transcript.getString("dummy_key"))
    private val slot = transcript.getInt("slot")
    private val mobileRandom = hex(transcript.getString("mobile_random"))
    private val events: List<Event> = transcript.getJSONArray("events").let { arr ->
        (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            Event(
                type = e.getString("type"),
                channel = YaleChannel.valueOf(e.getString("channel")),
                data = e.optString("data").takeIf { it.isNotEmpty() }?.let(::hex),
            )
        }
    }

    /** Asserts every call against the recorded events and feeds back the recorded notifications. */
    private class ReplayTransport(
        private val events: List<Event>,
        private val replaceReply: (writeIndex: Int, reply: ByteArray) -> ByteArray? = { _, r -> r },
    ) : FrameTransport {
        var cursor = 0
        private var writes = 0
        private val queues = YaleChannel.entries.associateWith { ArrayDeque<ByteArray>() }

        private fun expect(type: String, channel: YaleChannel): Event {
            val e = events.getOrNull(cursor) ?: fail("unexpected $type on $channel after end of transcript") as Nothing
            assertEquals("event #$cursor type", e.type, type)
            assertEquals("event #$cursor channel", e.channel, channel)
            cursor++
            return e
        }

        override suspend fun enableNotifications(channel: YaleChannel) { expect("enable", channel) }
        override suspend fun disableNotifications(channel: YaleChannel) { expect("disable", channel) }

        override suspend fun write(channel: YaleChannel, frame: ByteArray) {
            val e = expect("write", channel)
            assertEquals("write #$writes bytes", e.data!!.toHex(), frame.toHex())
            val index = writes++
            while (events.getOrNull(cursor)?.type == "notify") {
                val n = events[cursor++]
                replaceReply(index, n.data!!.copyOf())?.let { queues.getValue(n.channel!!).addLast(it) }
            }
        }

        override suspend fun receive(channel: YaleChannel, timeoutMs: Long): ByteArray? =
            queues.getValue(channel).removeFirstOrNull()

        override fun poll(channel: YaleChannel): ByteArray? = queues.getValue(channel).removeFirstOrNull()
    }

    private fun TestScope.session(transport: FrameTransport) = YaleLockSession(
        transport = transport,
        offlineKey = key.copyOf(),
        keyIndex = slot,
        randomBytes = { n -> mobileRandom.copyOf(n) },
        monotonicMillis = { testScheduler.currentTime },
    )

    @Test
    fun fullFlowMatchesPythonLibraryByteForByte() = runTest {
        val transport = ReplayTransport(events)
        val session = session(transport)

        session.handshake()
        assertEquals(LockStatus.valueOf(transcript.getString("status_before_unlock")), session.lockStatus())
        assertEquals(UnlockAck.Accepted(LockStatus.UNLOCKED), session.unlock())
        assertTrue(session.unlockMayHaveBeenSent)
        session.shutdown()

        assertEquals("all recorded events consumed", events.size, transport.cursor)
    }

    @Test
    fun missingUnlockReplyIsAmbiguousNotRetried() = runTest {
        val unlockWrite = 3 // key exchange, init, status, unlock
        val transport = ReplayTransport(events) { i, r -> if (i == unlockWrite) null else r }
        val session = session(transport)
        session.handshake()
        session.lockStatus()
        try {
            session.unlock()
            fail("expected timeout")
        } catch (_: ResponseTimeoutException) {
        }
        assertTrue("caller must treat this as possibly unlocked", session.unlockMayHaveBeenSent)
        try {
            session.unlock()
            fail("a second unlock in one session must be refused")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun refusedWriteIsKnownNotSent() = runTest {
        val replay = ReplayTransport(events)
        val transport = object : FrameTransport by replay {
            var writes = 0
            override suspend fun write(channel: YaleChannel, frame: ByteArray) {
                if (writes++ == 3) throw WriteNotSentException("queue full")
                replay.write(channel, frame)
            }
        }
        val session = session(transport)
        session.handshake()
        session.lockStatus()
        try {
            session.unlock()
            fail("expected WriteNotSentException")
        } catch (_: WriteNotSentException) {
        }
        assertFalse(session.unlockMayHaveBeenSent)
    }

    @Test
    fun wrongKeyExchangeReplyIsAuthError() = runTest {
        // A reply that decrypts and checksums fine but has the wrong opcode.
        val forged = Frames.secureCommand(0x7F, slot).also(Frames::writeSecureChecksum)
        ChannelCipher.Secure(key).encrypt(forged)
        val transport = ReplayTransport(events) { i, r -> if (i == 0) forged else r }
        try {
            session(transport).handshake()
            fail("expected AuthException")
        } catch (_: AuthException) {
        }
    }

    @Test
    fun corruptedReplyIsRejected() = runTest {
        val transport = ReplayTransport(events) { i, r -> if (i == 0) r.also { it[5] = (it[5] + 1).toByte() } else r }
        try {
            session(transport).handshake()
            fail("expected ResponseException")
        } catch (_: ResponseException) {
        }
    }

    @Test
    fun jammedReplyIsReported() = runTest {
        // Build the lock's third session notification by hand: CBC chains from
        // the previous ciphertext block, which the transcript gives us.
        val sessionNotifies = events.filter { it.type == "notify" && it.channel == YaleChannel.SESSION }
        val sessionKey = mobileRandom.copyOfRange(0, 8) + hex("1011121314151617")
        val plain = ByteArray(FRAME_LEN).also {
            it[0] = 0xBB.toByte()
            it[1] = Opcode.UNLOCK.toByte()
            it[3] = LockStatus.JAMMED.code.toByte()
            it[5] = Frames.simpleChecksum(it).toByte()
        }
        val iv = sessionNotifies[1].data!!.copyOfRange(0, 16)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), IvParameterSpec(iv))
        val jammed = cipher.doFinal(plain, 0, 16) + plain.copyOfRange(16, 18)

        val transport = ReplayTransport(events) { i, r -> if (i == 3) jammed else r }
        val session = session(transport)
        session.handshake()
        session.lockStatus()
        assertEquals(UnlockAck.Jammed, session.unlock())
    }

    @Test
    fun checksumsMatchPythonDefinitions() {
        // util._simple_checksum over 18 bytes, util._security_checksum with the 10-byte third term.
        val buf = ByteArray(FRAME_LEN) { (it * 37 + 11).toByte() }
        val simple = (-(buf.take(18).sumOf { it.toInt() and 0xFF })) and 0xFF
        assertEquals(simple, Frames.simpleChecksum(buf))

        val v3 = buf.copyOfRange(8, 18).foldIndexed(java.math.BigInteger.ZERO) { i, acc, b ->
            acc.add(java.math.BigInteger.valueOf((b.toLong() and 0xFF)).shiftLeft(8 * i))
        }
        val total = java.math.BigInteger.valueOf(Frames.u32(buf, 0) + Frames.u32(buf, 4)).add(v3)
        val expected = total.negate().mod(java.math.BigInteger.ONE.shiftLeft(32)).toLong()
        assertEquals(expected, Frames.securityChecksum(buf))
    }

    @Test
    fun framesHaveLibraryLayout() {
        val secure = Frames.secureCommand(Opcode.SEC_KEY_EXCHANGE, 7)
        assertArrayEquals(byteArrayOf(0x0F, 7), secure.copyOfRange(16, 18))
        val unlock = Frames.sessionCommand(Opcode.UNLOCK)
        assertEquals(0xEE.toByte(), unlock[0])
        assertEquals(0x0A.toByte(), unlock[1])
        assertArrayEquals(byteArrayOf(0x02, 0x00), unlock.copyOfRange(16, 18))
    }

    private companion object {
        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
