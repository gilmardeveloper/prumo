package io.prumo.mcp.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitStateParserTest {

    private val status = listOf(
        "# branch.oid 8f1c2d3e4a5b6c7d8e9f0a1b2c3d4e5f60718293",
        "# branch.head feature/prumo",
        "# branch.upstream origin/feature/prumo",
        "# branch.ab +2 -1",
        "1 M. N... 100644 100644 100644 aaa bbb src/main/kotlin/Prumo.kt",
        "1 .M N... 100644 100644 100644 ccc ddd docs/manual.md",
        "2 R. N... 100644 100644 100644 eee fff R100 src/novo.kt\tsrc/antigo.kt",
        "u UU N... 100644 100644 100644 100644 aaa bbb ccc src/conflito.kt",
        "? build/relatorio.txt",
        "! .idea/workspace.xml",
    )

    @Test
    fun `le a branch, o upstream e a distancia para o remoto`() {
        val snapshot = GitStateParser.parseStatus(status)

        assertEquals("feature/prumo", snapshot.branch.branch)
        assertEquals("origin/feature/prumo", snapshot.branch.upstream)
        assertEquals(2, snapshot.branch.ahead)
        assertEquals(1, snapshot.branch.behind)
        assertFalse(snapshot.branch.detached)
    }

    @Test
    fun `classifica cada tipo de mudanca pelo que o Git reporta`() {
        val changes = GitStateParser.parseStatus(status).changes.associateBy { it.path }

        assertEquals(GitChangeKind.STAGED, changes.getValue("src/main/kotlin/Prumo.kt").kind)
        assertEquals(GitChangeKind.UNSTAGED, changes.getValue("docs/manual.md").kind)
        assertEquals(GitChangeKind.CONFLICTED, changes.getValue("src/conflito.kt").kind)
        assertEquals(GitChangeKind.UNTRACKED, changes.getValue("build/relatorio.txt").kind)
        assertEquals(GitChangeKind.IGNORED, changes.getValue(".idea/workspace.xml").kind)
    }

    @Test
    fun `arquivo renomeado e enderecado pelo caminho novo`() {
        val changes = GitStateParser.parseStatus(status).changes.map { it.path }

        assertTrue(changes.contains("src/novo.kt"), "o caminho novo enderesa o arquivo")
        assertFalse(changes.contains("src/antigo.kt"), "o caminho antigo nao deve virar mudanca separada")
    }

    @Test
    fun `HEAD destacado nao inventa nome de branch`() {
        val snapshot = GitStateParser.parseStatus(
            listOf("# branch.oid 8f1c2d3", "# branch.head (detached)"),
        )

        assertNull(snapshot.branch.branch)
        assertTrue(snapshot.branch.detached)
        assertEquals("8f1c2d3", snapshot.branch.commit)
    }

    @Test
    fun `repositorio sem commit algum nao reporta revisao`() {
        val snapshot = GitStateParser.parseStatus(listOf("# branch.oid (initial)", "# branch.head main"))

        assertNull(snapshot.branch.commit)
        assertEquals("main", snapshot.branch.branch)
    }

    @Test
    fun `numstat separa contagem de linha de arquivo binario`() {
        val deltas = GitStateParser.parseNumstat(
            listOf("12\t3\tsrc/Prumo.kt", "-\t-\tdocs/diagrama.png", "linha invalida"),
        ).associateBy { it.path }

        assertEquals(12, deltas.getValue("src/Prumo.kt").addedLines)
        assertEquals(3, deltas.getValue("src/Prumo.kt").deletedLines)
        assertFalse(deltas.getValue("src/Prumo.kt").binary)
        assertTrue(deltas.getValue("docs/diagrama.png").binary)
        assertNull(deltas.getValue("docs/diagrama.png").addedLines)
        assertEquals(2, deltas.size, "linha fora do formato e descartada, nao vira arquivo")
    }

    @Test
    fun `a lista de branches ignora linha vazia e repeticao`() {
        val branches = GitStateParser.parseBranchList(listOf("main", "  feature/prumo  ", "", "main"))

        assertEquals(listOf("main", "feature/prumo"), branches)
    }
}
