package io.prumo.mcp.storage

import io.prumo.mcp.platform.EnvironmentProbe
import io.prumo.mcp.platform.PrumoDirectories
import io.prumo.mcp.platform.SystemEnvironmentProbe
import java.nio.file.Files
import java.nio.file.Path

/** Localiza os diretórios do Prumo. Toda escrita do produto passa por aqui. */
interface LocalStorageProvider {

    fun workspacesRoot(): Path

    fun workspaceRoot(workspaceId: String): Path

    fun settingsRoot(): Path

    fun clientsRoot(): Path

    fun runtimeRoot(): Path

    fun cacheRoot(): Path

    fun prepare()
}

class FileSystemStorageProvider(
    private val directories: PrumoDirectories,
) : LocalStorageProvider {

    override fun workspacesRoot(): Path = directories.data.resolve(WORKSPACES)

    override fun workspaceRoot(workspaceId: String): Path {
        require(workspaceId.matches(SAFE_ID)) {
            "Invalid workspace id: only letters, digits, hyphen and underscore are allowed."
        }
        return workspacesRoot().resolve(workspaceId)
    }

    override fun settingsRoot(): Path = directories.config.resolve(SETTINGS)

    override fun clientsRoot(): Path = directories.config.resolve(CLIENTS)

    override fun runtimeRoot(): Path = directories.data.resolve(RUNTIME)

    override fun cacheRoot(): Path = directories.cache

    override fun prepare() {
        listOf(workspacesRoot(), settingsRoot(), clientsRoot(), runtimeRoot(), cacheRoot())
            .forEach { Files.createDirectories(it) }
    }

    companion object {
        private const val WORKSPACES = "workspaces"
        private const val SETTINGS = "settings"
        private const val CLIENTS = "clients"
        private const val RUNTIME = "runtime"

        /** O id vira nome de diretório, então não pode conter separador nem `..`. */
        private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun forCurrentSystem(environment: EnvironmentProbe = SystemEnvironmentProbe): FileSystemStorageProvider =
            FileSystemStorageProvider(PrumoDirectories.resolve(environment))
    }
}
