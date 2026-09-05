package io.prumo.mcp.repository

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Descobre a raiz de um repositório Git e o seu remote, lendo o próprio `.git`.
 *
 * A leitura é feita sobre o arquivo de configuração em vez de uma API da IDE por duas razões: vale
 * igual em Windows e Linux, e mantém a identificação de repositório testável fora do ambiente —
 * é justamente o que sustenta a fronteira do workspace.
 *
 * Nada é escrito. O Prumo apenas lê o que o Git já mantém.
 */
object GitRepositoryProbe {

    private const val GIT_DIRECTORY = ".git"
    private const val MAX_DEPTH = 64

    fun findRepositoryRoot(start: Path): Path? {
        var current: Path? = start.toAbsolutePath().normalize()
        var depth = 0
        while (current != null && depth++ < MAX_DEPTH) {
            val marker = current.resolve(GIT_DIRECTORY)
            if (Files.isDirectory(marker) || Files.isRegularFile(marker)) {
                return current
            }
            current = current.parent
        }
        return null
    }

    fun readOriginRemote(repositoryRoot: Path): String? {
        val config = resolveGitDirectory(repositoryRoot)?.resolve("config") ?: return null
        if (!Files.isRegularFile(config)) {
            return null
        }
        return try {
            parseOriginUrl(Files.readAllLines(config, StandardCharsets.UTF_8))
        } catch (_: IOException) {
            null
        }
    }

    /** Em worktree e submódulo, `.git` é um arquivo apontando para o diretório real. */
    private fun resolveGitDirectory(repositoryRoot: Path): Path? {
        val marker = repositoryRoot.resolve(GIT_DIRECTORY)
        if (Files.isDirectory(marker)) {
            return marker
        }
        if (!Files.isRegularFile(marker)) {
            return null
        }
        val pointer = try {
            Files.readString(marker, StandardCharsets.UTF_8).trim()
        } catch (_: IOException) {
            return null
        }
        val target = pointer.removePrefix("gitdir:").trim()
        if (target.isEmpty()) {
            return null
        }
        val resolved = runCatching { repositoryRoot.resolve(target).normalize() }.getOrNull()
        return resolved?.takeIf { Files.isDirectory(it) }
    }

    internal fun parseOriginUrl(lines: List<String>): String? {
        var insideOrigin = false
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.startsWith("[")) {
                insideOrigin = line.replace(" ", "").equals("[remote\"origin\"]", ignoreCase = true)
                continue
            }
            if (!insideOrigin || !line.startsWith("url", ignoreCase = true)) {
                continue
            }
            val value = line.substringAfter('=', "").trim()
            if (value.isNotEmpty()) {
                return value
            }
        }
        return null
    }
}
