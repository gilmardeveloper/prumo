package io.prumo.mcp.toolsets

import io.prumo.mcp.documentation.DocumentAuthority
import io.prumo.mcp.documentation.DocumentationKind
import io.prumo.mcp.documentation.DocumentationSource
import io.prumo.mcp.policy.PolicyAction
import io.prumo.mcp.policy.WorkspacePolicies
import io.prumo.mcp.repository.RepositoryFingerprint
import io.prumo.mcp.workspace.application.WorkspaceContext
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.domain.WorkspaceType
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class WorkspaceReportsTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `o contexto descreve o workspace corrente sem caminho de disco`() {
        val response = WorkspaceReports.context(context())

        assertEquals("modernizacao-folha", response.workspaceId)
        assertEquals("MODERNIZATION", response.workspaceType)
        assertEquals("consumidor", response.currentRepository.repositoryId)
        assertTrue(response.currentRepository.current)
        assertEquals(2, response.repositoryCount)
        assertEquals(1, response.documentationSourceCount)
        assertNoLocalPath(json.encodeToString(WorkspaceContextResponse.serializer(), response))
    }

    @Test
    fun `a lista de repositorios entrega identificador, nunca caminho nem credencial`() {
        val context = context(currentRemote = "https://someone:s3cr3t-token@github.com/org/consumidor.git")

        val response = WorkspaceReports.repositories(context)
        val serialized = json.encodeToString(RepositoriesResponse.serializer(), response)

        assertEquals(listOf("consumidor", "legado"), response.repositories.map { it.repositoryId })
        assertEquals(listOf(true, false), response.repositories.map { it.current })
        assertEquals("github.com/org/consumidor", response.repositories.first().remoteIdentity)
        assertFalse(serialized.contains("s3cr3t-token"), "a URL crua do remote nao pode vazar")
        assertNoLocalPath(serialized)
    }

    @Test
    fun `a politica devolvida e a decisao do motor, nao uma copia das flags`() {
        val response = WorkspaceReports.policy(context())

        assertEquals(PolicyAction.entries.map { it.name }, response.decisions.map { it.action })
        val byAction = response.decisions.associateBy { it.action }
        assertTrue(byAction.getValue("READ_REPOSITORY").allowed)
        assertFalse(byAction.getValue("WRITE_GIT").allowed)
        assertTrue(byAction.getValue("WRITE_GIT").reason.orEmpty().contains("Git write"))
        assertTrue(response.decisions.filter { !it.allowed }.all { !it.reason.isNullOrBlank() })
    }

    @Test
    fun `as fontes de documentacao sao catalogadas sem revelar onde estao`(@TempDir root: Path) {
        val manual = root.resolve("docs/manual.md").also { it.parent.createDirectories() }
        manual.writeText("# manual")
        val contrato = root.resolve("docs/contrato.pdf")
        contrato.writeText("%PDF-1.4")

        val response = WorkspaceReports.documentationSources(
            context(
                documentation = listOf(
                    documentation("manual", manual),
                    documentation("contrato", contrato),
                    documentation("sumido", root.resolve("docs/ausente.md")),
                ),
            ),
        )
        val serialized = json.encodeToString(DocumentationSourcesResponse.serializer(), response)

        val bySource = response.sources.associateBy { it.documentationId }
        assertTrue(bySource.getValue("manual").available)
        assertTrue(bySource.getValue("manual").textExtractionSupported)
        assertTrue(bySource.getValue("contrato").available)
        assertFalse(bySource.getValue("contrato").textExtractionSupported, "PDF e catalogado, nao extraido")
        assertFalse(bySource.getValue("sumido").available)
        assertFalse(serialized.contains(root.toString()), "a resposta nao deve revelar onde o arquivo esta")
        assertNoLocalPath(serialized)
    }
}

class WorkspacePreparationTest {

    @Test
    fun `workspace integro fica pronto`(@TempDir root: Path) {
        val repository = gitRepository(root, "consumidor", "git@github.com:org/consumidor.git")
        val manual = manual(root)

        val response = WorkspacePreparation.evaluate(
            context(
                repositories = listOf(binding("consumidor", repository, "git@github.com:org/consumidor.git")),
                documentation = listOf(documentation("manual", manual)),
            ),
        )

        assertEquals(PreparationStatus.READY, response.status)
        assertEquals(
            listOf("policy", "repository:consumidor", "documentation:manual"),
            response.checks.map { it.check },
        )
        assertTrue(response.checks.all { it.status == CheckStatus.OK })
    }

    @Test
    fun `repositorio que sumiu do disco reprova a preparacao`(@TempDir root: Path) {
        val response = WorkspacePreparation.evaluate(
            context(
                repositories = listOf(binding("consumidor", root.resolve("nao-existe"), null)),
                documentation = listOf(documentation("manual", manual(root))),
            ),
        )

        assertEquals(PreparationStatus.ERROR, response.status)
        val check = response.checks.single { it.check == "repository:consumidor" }
        assertEquals(CheckStatus.ERROR, check.status)
        assertTrue(check.message.contains("no longer accessible"))
    }

    @Test
    fun `identidade divergente e documentacao ausente viram alerta, nao erro`(@TempDir root: Path) {
        val repository = gitRepository(root, "consumidor", "git@github.com:org/outro.git")

        val response = WorkspacePreparation.evaluate(
            context(
                repositories = listOf(
                    binding("consumidor", repository, "git@github.com:org/consumidor.git").copy(
                        fingerprint = RepositoryFingerprint
                            .fromGitRemote("git@github.com:org/consumidor.git")
                            ?.value,
                    ),
                ),
                documentation = listOf(documentation("sumido", root.resolve("docs/ausente.md"))),
            ),
        )

        assertEquals(PreparationStatus.WARNING, response.status)
        assertEquals(
            CheckStatus.WARNING,
            response.checks.single { it.check == "repository:consumidor" }.status,
        )
        assertEquals(
            CheckStatus.WARNING,
            response.checks.single { it.check == "documentation:sumido" }.status,
        )
    }

    @Test
    fun `preparar nao altera estado algum`(@TempDir root: Path) {
        val repository = gitRepository(root, "consumidor", "git@github.com:org/consumidor.git")
        val manual = manual(root)
        val before = snapshot(root)

        WorkspacePreparation.evaluate(
            context(
                repositories = listOf(binding("consumidor", repository, "git@github.com:org/consumidor.git")),
                documentation = listOf(documentation("manual", manual)),
            ),
        )

        assertEquals(before, snapshot(root))
    }

    @Test
    fun `nenhuma mensagem de preparacao expoe caminho de disco`(@TempDir root: Path) {
        val repository = gitRepository(root, "consumidor", "git@github.com:org/consumidor.git")

        val response = WorkspacePreparation.evaluate(
            context(
                repositories = listOf(binding("consumidor", repository, "git@github.com:org/consumidor.git")),
                documentation = listOf(documentation("sumido", root.resolve("docs/ausente.md"))),
            ),
        )

        val rendered = response.checks.joinToString("\n") { it.message }
        assertFalse(rendered.contains(root.toString()), "a mensagem nao deve conter caminho local")
        assertFalse(rendered.contains("/"), "a mensagem nao deve conter fragmento de caminho")
    }

    private fun gitRepository(root: Path, name: String, remote: String): Path {
        val repository = root.resolve(name).also { it.createDirectories() }
        val config = repository.resolve(".git/config").also { it.parent.createDirectories() }
        config.writeText(
            """
            [core]
                bare = false
            [remote "origin"]
                url = $remote
            """.trimIndent(),
        )
        return repository
    }

    private fun manual(root: Path): Path {
        val manual = root.resolve("docs/manual.md").also { it.parent.createDirectories() }
        manual.writeText("# manual")
        return manual
    }

    private fun snapshot(directory: Path): Map<String, Long> =
        Files.walk(directory).use { paths ->
            paths.filter(Files::isRegularFile)
                .toList()
                .associate { directory.relativize(it).toString() to Files.size(it) }
        }
}

private fun binding(id: String, localPath: Path, remote: String?) = RepositoryBinding(
    id = id,
    name = id,
    localPath = localPath.toString(),
    gitRemote = remote,
    role = RepositoryRole.TARGET,
    accessMode = AccessMode.READ_WRITE,
)

private fun documentation(id: String, location: Path) = DocumentationSource(
    id = id,
    name = id,
    kind = DocumentationKind.FILE,
    location = location.toString(),
    authority = DocumentAuthority.OFFICIAL,
)

private fun context(
    repositories: List<RepositoryBinding>? = null,
    documentation: List<DocumentationSource>? = null,
    currentRemote: String = "git@github.com:org/consumidor.git",
): WorkspaceContext {
    val bindings = repositories ?: listOf(
        RepositoryBinding(
            id = "consumidor",
            name = "folha-calculadora-consumidor",
            localPath = "C:/repos/consumidor",
            gitRemote = currentRemote,
            role = RepositoryRole.TARGET,
            accessMode = AccessMode.READ_WRITE,
        ),
        RepositoryBinding(
            id = "legado",
            name = "folha",
            localPath = "C:/repos/folha",
            role = RepositoryRole.LEGACY_REFERENCE,
            accessMode = AccessMode.READ_ONLY,
        ),
    )
    val workspace = Workspace(
        id = "modernizacao-folha",
        name = "Modernização Folha",
        type = WorkspaceType.MODERNIZATION,
        repositories = bindings,
        documentation = documentation ?: listOf(
            DocumentationSource(
                id = "manual",
                name = "manual",
                kind = DocumentationKind.FILE,
                location = "C:/repos/consumidor/docs/manual.md",
            ),
        ),
        policies = WorkspacePolicies.DENY_ALL,
        createdAt = "2026-09-04T00:00:00Z",
        updatedAt = "2026-09-04T00:00:00Z",
    )
    return WorkspaceContext(workspace, bindings.first())
}

private fun assertNoLocalPath(serialized: String) {
    listOf("C:/repos", "C:\\repos", "localPath", "location").forEach {
        assertFalse(serialized.contains(it), "a resposta MCP nao deve conter '$it'")
    }
}
