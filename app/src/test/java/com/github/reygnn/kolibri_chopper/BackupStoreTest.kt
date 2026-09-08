package com.github.reygnn.kolibri_chopper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * JVM unit tests for [BackupStore]'s restore-read bound.
 *
 * Only [BackupStore.readCapped] is exercised — the pure, load-bearing part of `readText`
 * (the guard against an OOM on a huge or streaming restore file). `readText`'s own body is
 * just `resolver.openInputStream(uri)?.use { readCapped(it) }`: trivial Android glue that
 * would need Robolectric to reach but carries no logic worth testing. Extracting the cap
 * into a function of an [InputStream] keeps the meaningful behaviour testable on the plain
 * JVM — a [ByteArrayInputStream] drives it, no Android runtime and no Robolectric.
 */
class BackupStoreTest {

    @Test fun `reads content under the cap`() {
        assertEquals(
            "hello",
            BackupStore.readCapped(ByteArrayInputStream("hello".toByteArray()), maxBytes = 16),
        )
    }

    @Test fun `content exactly at the cap is accepted (inclusive)`() {
        val exact = "x".repeat(8)
        assertEquals(exact, BackupStore.readCapped(ByteArrayInputStream(exact.toByteArray()), maxBytes = 8))
    }

    @Test fun `content one byte over the cap is refused, not truncated`() {
        assertNull(BackupStore.readCapped(ByteArrayInputStream("x".repeat(9).toByteArray()), maxBytes = 8))
    }

    @Test fun `an empty stream reads as empty, never null`() {
        assertEquals("", BackupStore.readCapped(ByteArrayInputStream(ByteArray(0)), maxBytes = 8))
    }

    @Test fun `a chunked over-cap stream is still refused`() {
        // A provider that hands out bytes a few at a time (as a real ContentProvider can)
        // must still be bounded: readNBytes keeps pulling up to cap + 1, so 9 bytes over an
        // 8-byte cap is caught even when delivered in 3-byte chunks.
        val chunky = object : InputStream() {
            private var remaining = 9
            override fun read(): Int = if (remaining-- > 0) 'x'.code else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = minOf(3, len, remaining)
                for (i in 0 until n) b[off + i] = 'x'.code.toByte()
                remaining -= n
                return n
            }
        }
        assertNull(BackupStore.readCapped(chunky, maxBytes = 8))
    }
}
