package io.prumo.mcp.knowledge

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/** L-006: o MVStore precisa estar no classpath de teste, não só no de compilação. */
class MvStoreClasspathProbeTest {

    @Test
    fun `o MVStore esta ao alcance da suite`() {
        assertNotNull(Class.forName("org.h2.mvstore.MVStore"))
        assertNotNull(Class.forName("org.h2.mvstore.tx.TransactionStore"))
    }
}
