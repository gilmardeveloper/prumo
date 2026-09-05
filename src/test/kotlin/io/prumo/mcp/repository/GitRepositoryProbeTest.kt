package io.prumo.mcp.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class GitRepositoryProbeTest {

    @Test
    fun `encontra a raiz do repositorio a partir de um subdiretorio`(@TempDir root: Path) {
        val repository = root.resolve("app")
        Files.createDirectories(repository.resolve(".git"))
        val deep = repository.resolve("src/main/kotlin")
        Files.createDirectories(deep)

        assertEquals(repository.toAbsolutePath().normalize(), GitRepositoryProbe.findRepositoryRoot(deep))
    }

    @Test
    fun `diretorio sem Git nao tem raiz`(@TempDir root: Path) {
        val plain = root.resolve("sem-git")
        Files.createDirectories(plain)

        assertNull(GitRepositoryProbe.findRepositoryRoot(plain))
    }

    @Test
    fun `le o remote origin do arquivo de configuracao`(@TempDir root: Path) {
        val repository = root.resolve("app")
        Files.createDirectories(repository.resolve(".git"))
        Files.writeString(
            repository.resolve(".git/config"),
            """
            [core]
                bare = false
            [remote "upstream"]
                url = git@github.com:outra/coisa.git
            [remote "origin"]
                url = git@github.com:org/app.git
                fetch = +refs/heads/*:refs/remotes/origin/*
            """.trimIndent(),
        )

        assertEquals("git@github.com:org/app.git", GitRepositoryProbe.readOriginRemote(repository))
    }

    @Test
    fun `repositorio sem remote origin devolve nulo`(@TempDir root: Path) {
        val repository = root.resolve("app")
        Files.createDirectories(repository.resolve(".git"))
        Files.writeString(repository.resolve(".git/config"), "[core]\n    bare = false\n")

        assertNull(GitRepositoryProbe.readOriginRemote(repository))
    }

    @Test
    fun `worktree com ponteiro gitdir tambem e lido`(@TempDir root: Path) {
        val real = root.resolve("real/.git")
        Files.createDirectories(real)
        Files.writeString(real.resolve("config"), "[remote \"origin\"]\n    url = https://host/org/app.git\n")

        val worktree = root.resolve("worktree")
        Files.createDirectories(worktree)
        Files.writeString(worktree.resolve(".git"), "gitdir: ${real.toAbsolutePath()}")

        assertEquals("https://host/org/app.git", GitRepositoryProbe.readOriginRemote(worktree))
    }

    @Test
    fun `a secao errada nao contamina a leitura`() {
        val remote = GitRepositoryProbe.parseOriginUrl(
            listOf("[remote \"fork\"]", "url = git@host:fork/app.git", "[branch \"main\"]", "url = ignorado"),
        )

        assertNull(remote)
    }
}
