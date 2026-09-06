package io.prumo.mcp.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@Tag("security")
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

    /**
     * Vincular uma subpasta é vincular o repositório que a contém. Gravar o vínculo por um caminho e
     * conferi-lo por outro fazia a conferência acusar mudança de identidade num vínculo intacto.
     */
    @Test
    fun `subpasta de repositorio Git tem a identidade da raiz`(@TempDir raiz: Path) {
        val git = Files.createDirectory(raiz.resolve(".git"))
        Files.writeString(
            git.resolve("config"),
            "[remote \"origin\"]" + System.lineSeparator() +
                "	url = https://git.exemplo.gov.br/time/folha.git" + System.lineSeparator(),
        )
        val subpasta = Files.createDirectories(raiz.resolve("modulo/FONTES"))

        val daSubpasta = RepositoryFingerprint.forDirectory(subpasta)

        assertEquals(RepositoryFingerprint.forDirectory(raiz), daSubpasta)
        assertEquals(RepositoryFingerprint.Source.GIT_REMOTE, daSubpasta.source)
    }

    @Test
    fun `diretorio sem Git continua identificado pelo proprio nome`(@TempDir raiz: Path) {
        val solto = Files.createDirectory(raiz.resolve("sistema-legado"))

        assertEquals(RepositoryFingerprint.of(null, solto), RepositoryFingerprint.forDirectory(solto))
    }

    @Test
    fun `o fingerprint local nao expoe o caminho da maquina`() {
        val fingerprint = RepositoryFingerprint.of(null, Path.of("/home/gilmar/repos/folha-secreta"))

        assertEquals(false, fingerprint.value.contains("gilmar"))
        assertEquals(false, fingerprint.value.contains("folha-secreta"))
    }
}
