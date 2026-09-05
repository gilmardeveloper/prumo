package io.prumo.mcp.platform

/**
 * Famílias de sistema operacional que o Prumo distingue.
 *
 * As diferenças de plataforma ficam concentradas aqui e em [PrumoDirectories].
 */
enum class OperatingSystemFamily {
    WINDOWS,
    LINUX,
    MACOS,
    OTHER,
    ;

    companion object {
        fun fromName(osName: String?): OperatingSystemFamily {
            val normalized = osName?.lowercase().orEmpty()
            return when {
                normalized.startsWith("windows") -> WINDOWS
                normalized.startsWith("mac") || normalized.startsWith("darwin") -> MACOS
                normalized.startsWith("linux") ||
                    normalized.contains("nix") ||
                    normalized.contains("nux") ||
                    normalized.contains("aix") -> LINUX
                else -> OTHER
            }
        }
    }
}

/**
 * Leitura do ambiente do processo. Existe como interface para que a resolução de diretórios seja
 * testável nas duas plataformas a partir de qualquer máquina.
 */
interface EnvironmentProbe {

    fun variable(name: String): String?

    fun systemProperty(name: String): String?

    val operatingSystem: OperatingSystemFamily
        get() = OperatingSystemFamily.fromName(systemProperty("os.name"))
}

object SystemEnvironmentProbe : EnvironmentProbe {

    override fun variable(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    override fun systemProperty(name: String): String? =
        System.getProperty(name)?.takeIf { it.isNotBlank() }
}
