package io.prumo.mcp.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class FakeEnvironmentProbe(
    private val variables: Map<String, String> = emptyMap(),
    private val properties: Map<String, String> = emptyMap(),
) : EnvironmentProbe {
    override fun variable(name: String): String? = variables[name]?.takeIf { it.isNotBlank() }
    override fun systemProperty(name: String): String? = properties[name]?.takeIf { it.isNotBlank() }
}

class PrumoDirectoriesTest {

    @Test
    fun `no Windows usa LOCALAPPDATA`() {
        val directories = PrumoDirectories.resolve(
            FakeEnvironmentProbe(
                variables = mapOf("LOCALAPPDATA" to "C:\\Users\\dev\\AppData\\Local"),
                properties = mapOf("os.name" to "Windows 11", "user.home" to "C:\\Users\\dev"),
            ),
        )

        assertEquals(Path.of("C:\\Users\\dev\\AppData\\Local", "PrumoMCP"), directories.config)
        assertEquals(directories.config, directories.data)
        assertEquals(directories.config.resolve("cache"), directories.cache)
    }

    @Test
    fun `no Windows sem LOCALAPPDATA cai no perfil do usuario`() {
        val directories = PrumoDirectories.resolve(
            FakeEnvironmentProbe(
                properties = mapOf("os.name" to "Windows 10", "user.home" to "C:\\Users\\dev"),
            ),
        )

        assertEquals(
            Path.of("C:\\Users\\dev", "AppData", "Local", "PrumoMCP"),
            directories.config,
        )
    }

    @Test
    fun `no Linux respeita as variaveis XDG`() {
        val directories = PrumoDirectories.resolve(
            FakeEnvironmentProbe(
                variables = mapOf(
                    "XDG_CONFIG_HOME" to "/home/dev/cfg",
                    "XDG_DATA_HOME" to "/home/dev/data",
                    "XDG_CACHE_HOME" to "/home/dev/tmp",
                ),
                properties = mapOf("os.name" to "Linux", "user.home" to "/home/dev"),
            ),
        )

        assertEquals(Path.of("/home/dev/cfg", "prumo-mcp"), directories.config)
        assertEquals(Path.of("/home/dev/data", "prumo-mcp"), directories.data)
        assertEquals(Path.of("/home/dev/tmp", "prumo-mcp"), directories.cache)
    }

    @Test
    fun `no Linux sem XDG usa os diretorios convencionais`() {
        val directories = PrumoDirectories.resolve(
            FakeEnvironmentProbe(
                properties = mapOf("os.name" to "Linux", "user.home" to "/home/dev"),
            ),
        )

        assertEquals(Path.of("/home/dev/.config", "prumo-mcp"), directories.config)
        assertEquals(Path.of("/home/dev/.local/share", "prumo-mcp"), directories.data)
        assertEquals(Path.of("/home/dev/.cache", "prumo-mcp"), directories.cache)
    }

    @Test
    fun `XDG relativo e ignorado para nao cair dentro do projeto aberto`() {
        val directories = PrumoDirectories.resolve(
            FakeEnvironmentProbe(
                variables = mapOf("XDG_CONFIG_HOME" to ".prumo"),
                properties = mapOf("os.name" to "Linux", "user.home" to "/home/dev"),
            ),
        )

        assertEquals(Path.of("/home/dev/.config", "prumo-mcp"), directories.config)
    }

    @Test
    fun `reconhece as familias de sistema operacional`() {
        assertEquals(OperatingSystemFamily.WINDOWS, OperatingSystemFamily.fromName("Windows Server 2022"))
        assertEquals(OperatingSystemFamily.LINUX, OperatingSystemFamily.fromName("Linux"))
        assertEquals(OperatingSystemFamily.MACOS, OperatingSystemFamily.fromName("Mac OS X"))
        assertEquals(OperatingSystemFamily.OTHER, OperatingSystemFamily.fromName(null))
    }

    @Test
    fun `nenhum diretorio do Prumo cai dentro de um repositorio do usuario`() {
        val repository = Path.of("/home/dev/repos/folha").toAbsolutePath().normalize()
        val directories = PrumoDirectories.resolve(
            FakeEnvironmentProbe(
                properties = mapOf("os.name" to "Linux", "user.home" to "/home/dev"),
            ),
        )

        listOf(directories.config, directories.data, directories.cache).forEach { directory ->
            assertTrue(
                !directory.toAbsolutePath().normalize().startsWith(repository),
                "$directory nao pode ficar dentro do repositorio $repository",
            )
        }
    }
}
