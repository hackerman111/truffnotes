package inkspace.text

import kotlin.test.*
import org.junit.jupiter.api.Test

class F10FontTest {
    @Test fun malformed_and_oversized_font_rejected_before_platform_parse() {
        assertFailsWith<IllegalArgumentException> { FontPreflight.validate(ByteArray(20)) }
        assertFailsWith<IllegalArgumentException> { FontPreflight.validate(ByteArray(FontPreflight.MAX_BYTES + 1)) }
    }
    @Test fun font_digest_stable_and_table_bounds_validated() {
        val bytes = ByteArray(28)
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        buffer.putInt(0x00010000); buffer.putShort(1)
        buffer.position(20); buffer.putInt(28); buffer.putInt(0)
        FontPreflight.validate(bytes)
        assertEquals(FontPreflight.digest(bytes), FontPreflight.digest(bytes.copyOf()))
        buffer.position(24); buffer.putInt(10)
        assertFailsWith<IllegalArgumentException> { FontPreflight.validate(bytes) }
    }
}
