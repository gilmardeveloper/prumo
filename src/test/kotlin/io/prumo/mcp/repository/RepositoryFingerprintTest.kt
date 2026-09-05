package io.prumo.mcp.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

class RepositoryFingerprintTest {

    @Test
    fun `as varias formas do mesmo remote produzem o mesmo fingerprint`() {
        val expected = RepositoryFingerprint.fromGitRemote("git@github.com:org/folha.git")

        listOf(
            "https://github.com/org/folha.git",
            "https://github.com/org/folha",
            "ssh://git@github.com/org/folha.git",
            "git@github.com:org/folha",
            "https://GitHub.com/Org/Folha.git/",
            "https://user@github.com/org/folha.git",
        ).forEach { remote ->
            assertEquals(expected, RepositoryFingerprint.fromGitRemote(remote), remote)
        }
    }

    @Test
    fun `repositorios diferentes nao colidem`() {
        assertNotEquals(
            RepositoryFingerprint.fromGitRemote("git@github.com:org/folha.git"),
            RepositoryFingerprint.fromGitRemote("git@github.com:org/folha-legado.git"),
        )
        assertNotEquals(
            RepositoryFingerprint.fromGitRemote("git@github.com:org/folha.git"),
            RepositoryFingerprint.fromGitRemote("git@gitlab.com:org/folha.git"),
        )
    }

    @Test
    fun `remote sem organizacao e recusado`() {
        assertNull(RepositoryFingerprint.fromGitRemote(""))
        assertNull(RepositoryFingerprint.fromGitRemote("github.com"))
    }

    @Test
    fun `o repositorio movido de lugar continua sendo o mesmo`() {
        val windowsBefore = RepositoryFingerprint.of("git@github.com:org/folha.git", Path.of("C:/repos/folha"))
        val windowsAfter = RepositoryFingerprint.of("git@github.com:org/folha.git", Path.of("D:/workspace/folha"))
        val linux = RepositoryFingerprint.of("git@github.com:org/folha.git", Path.of("/workspaces/folha"))

        assertEquals(windowsBefore, windowsAfter)
        assertEquals(windowsBefore, linux)
    }

    @Test
    fun `sem Git o fingerprint sobrevive a mudanca de caminho`() {
        val before = RepositoryFingerprint.of(null, Path.of("/home/dev/repos/sistema-legado"))
        val after = RepositoryFingerprint.of(null, Path.of("/workspaces/sistema-legado"))

        assertEquals(before, after)
        assertEquals(RepositoryFingerprint.Source.LOCAL_DIRECTORY, after.source)
    }

    @Test
    fun `o fingerprint local nao expoe o caminho da maquina`() {
        val fingerprint = RepositoryFingerprint.of(null, Path.of("/home/gilmar/repos/folha-secreta"))

        assertEquals(false, fingerprint.value.contains("gilmar"))
        assertEquals(false, fingerprint.value.contains("folha-secreta"))
    }
}
