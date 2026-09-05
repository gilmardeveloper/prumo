package io.prumo.mcp.platform

import java.nio.file.Path

/**
 * Diretórios em que o Prumo guarda tudo o que produz.
 *
 * Nenhum deles fica dentro de repositório do usuário: a configuração do Prumo vive em área do
 * sistema operacional, e o repositório da aplicação permanece limpo.
 */
data class PrumoDirectories(
    val config: Path,
    val data: Path,
    val cache: Path,
) {

    companion object {
        private const val WINDOWS_DIRECTORY = "PrumoMCP"
        private const val UNIX_DIRECTORY = "prumo-mcp"

        fun resolve(environment: EnvironmentProbe): PrumoDirectories =
            when (environment.operatingSystem) {
                OperatingSystemFamily.WINDOWS -> windows(environment)
                OperatingSystemFamily.MACOS -> macos(environment)
                else -> xdg(environment)
            }

        private fun windows(environment: EnvironmentProbe): PrumoDirectories {
            val root = environment.variable("LOCALAPPDATA")
                ?.let { Path.of(it) }
                ?: home(environment).resolve("AppData").resolve("Local")
            val base = root.resolve(WINDOWS_DIRECTORY)
            return PrumoDirectories(
                config = base,
                data = base,
                cache = base.resolve("cache"),
            )
        }

        private fun macos(environment: EnvironmentProbe): PrumoDirectories {
            val home = home(environment)
            val base = home.resolve("Library").resolve("Application Support").resolve(WINDOWS_DIRECTORY)
            return PrumoDirectories(
                config = base,
                data = base,
                cache = home.resolve("Library").resolve("Caches").resolve(WINDOWS_DIRECTORY),
            )
        }

        private fun xdg(environment: EnvironmentProbe): PrumoDirectories {
            val home = home(environment)
            return PrumoDirectories(
                config = xdgDirectory(environment, "XDG_CONFIG_HOME", home.resolve(".config")),
                data = xdgDirectory(environment, "XDG_DATA_HOME", home.resolve(".local").resolve("share")),
                cache = xdgDirectory(environment, "XDG_CACHE_HOME", home.resolve(".cache")),
            )
        }

        private fun xdgDirectory(
            environment: EnvironmentProbe,
            variable: String,
            fallback: Path,
        ): Path {
            // A especificação XDG manda ignorar valor relativo, e não interpretá-lo contra o
            // diretório corrente: um caminho relativo aqui poderia cair dentro do projeto aberto.
            val configured = environment.variable(variable)
                ?.takeIf { looksAbsolute(it) }
                ?.let { runCatching { Path.of(it) }.getOrNull() }
            return (configured ?: fallback).resolve(UNIX_DIRECTORY)
        }

        /**
         * `Path.isAbsolute` responde segundo o sistema operacional em que o processo roda, então um
         * caminho Unix seria classificado como relativo em Windows e vice-versa. A decisão precisa
         * ser a mesma em qualquer máquina, inclusive na que executa os testes.
         */
        private fun looksAbsolute(value: String): Boolean =
            value.startsWith("/") ||
                value.startsWith("\\\\") ||
                WINDOWS_ABSOLUTE.matches(value)

        private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[\\/].*")

        private fun home(environment: EnvironmentProbe): Path {
            val home = environment.systemProperty("user.home")
                ?: environment.variable("HOME")
                ?: environment.variable("USERPROFILE")
                ?: error("Prumo MCP could not determine the user home directory.")
            return Path.of(home)
        }
    }
}
