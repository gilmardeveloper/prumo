package io.prumo.mcp.repository

import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale

/**
 * Identidade estável de um repositório, independente de onde ele esteja no disco.
 *
 * A identidade preferencial vem do remote Git normalizado, igual em qualquer máquina. Sem Git,
 * resta o nome do diretório, o que reconhece a pasta movida mas não a pasta renomeada.
 */
data class RepositoryFingerprint(
    val value: String,
    val source: Source,
) {
    enum class Source { GIT_REMOTE, LOCAL_DIRECTORY }

    companion object {

        fun fromGitRemote(remote: String): RepositoryFingerprint? {
            val normalized = normalizeRemote(remote) ?: return null
            return RepositoryFingerprint("git:$normalized", Source.GIT_REMOTE)
        }

        fun fromLocalDirectory(path: Path): RepositoryFingerprint {
            val name = path.toAbsolutePath().normalize().fileName?.toString().orEmpty()
            return RepositoryFingerprint("local:${sha256(name.lowercase(Locale.ROOT))}", Source.LOCAL_DIRECTORY)
        }

        fun of(gitRemote: String?, localPath: Path): RepositoryFingerprint =
            gitRemote?.let(::fromGitRemote) ?: fromLocalDirectory(localPath)

        /**
         * Identidade da árvore Git que contém [directory], ou do próprio diretório sem Git.
         *
         * Vincular uma subpasta de um repositório é vincular aquele repositório, e quem responde
         * pela identidade é a raiz. Resolver de um jeito ao gravar o vínculo e de outro ao
         * conferi-lo faz a conferência acusar mudança onde nada mudou.
         */
        fun forDirectory(directory: Path): RepositoryFingerprint {
            val root = GitRepositoryProbe.findRepositoryRoot(directory) ?: return fromLocalDirectory(directory)
            return of(GitRepositoryProbe.readOriginRemote(root), root)
        }

        /**
         * Reduz as formas de endereçar o mesmo repositório a uma só: `host/organizacao/nome`.
         *
         * `git@github.com:org/app.git`, `https://github.com/org/app.git` e
         * `ssh://git@github.com:22/org/app/` descrevem o mesmo repositório e precisam produzir o
         * mesmo fingerprint.
         */
        internal fun normalizeRemote(remote: String): String? {
            var value = remote.trim()
            if (value.isEmpty()) return null

            value = value.substringBefore('?').substringBefore('#')
            value = SCHEME.replace(value, "")

            // Forma SCP do Git: usuario@host:caminho
            value = SCP_FORM.replace(value) { match -> "${match.groupValues[1]}/" }

            value = value.substringAfter('@', value)
            value = value.removeSuffix("/")
            if (value.endsWith(".git")) {
                value = value.dropLast(4)
            }
            value = value.replace(Regex("/+"), "/")

            val parts = value.split('/').filter { it.isNotBlank() }
            if (parts.size < 2) return null

            val host = parts.first().substringBefore(':').lowercase(Locale.ROOT)
            val path = parts.drop(1).joinToString("/").lowercase(Locale.ROOT)
            return "$host/$path"
        }

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(32)

        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
        private val SCP_FORM = Regex("^([^/:]+):(?!\\d)")
    }
}
