package io.prumo.mcp.knowledge

import io.prumo.mcp.documentation.DocumentationSource
import io.prumo.mcp.ide.SourceStampReader
import io.prumo.mcp.ide.StampResult
import io.prumo.mcp.policy.WorkspacePolicies
import io.prumo.mcp.workspace.application.WorkspaceContext
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.domain.WorkspaceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * O carimbo é o que responde "a fonte mudou?". Ele é calculado aqui, nunca informado pelo cliente —
 * e quando não dá para calcular, o motivo precisa chegar distinto, porque é ele que diz ao cliente
 * o que corrigir.
 */
class SourceStampReaderTest {

    @TempDir
    lateinit var root: Path

    private lateinit var context: WorkspaceContext

    @BeforeEach
    fun montar() {
        val repositorio = root.resolve("repo").also { it.createDirectories() }
        repositorio.resolve("README.md").writeText("conteudo inicial\n")
        repositorio.resolve("segredos").createDirectories()
        repositorio.resolve("segredos/chaves.txt").writeText("nao deve ser carimbado\n")

        val documentos = root.resolve("docs").also { it.createDirectories() }
        documentos.resolve("S-1210.md").writeText("regras do evento\n")

        val binding = RepositoryBinding(
            id = "folha-esocial",
            name = "folha-esocial",
            localPath = repositorio.toString(),
            role = RepositoryRole.PRIMARY,
            accessMode = AccessMode.READ_WRITE,
            excludedPaths = listOf("segredos"),
        )
        val workspace = Workspace(
            id = "folha-esocial",
            name = "Folha",
            type = WorkspaceType.MODERNIZATION,
            repositories = listOf(binding),
            documentation = listOf(
                DocumentationSource(
                    id = "eventos",
                    name = "eventos",
                    kind = io.prumo.mcp.documentation.DocumentationKind.DIRECTORY,
                    location = documentos.toString(),
                ),
            ),
            policies = WorkspacePolicies.DENY_ALL,
            createdAt = "2026-09-07T00:00:00Z",
            updatedAt = "2026-09-07T00:00:00Z",
        )
        context = WorkspaceContext(workspace, binding)
    }

    @Test
    fun `arquivo do repositorio e carimbado com tamanho, data e resumo`() {
        val resultado = SourceStampReader.stamp(context, provenance(path = "README.md"))

        val stamp = (resultado as StampResult.Stamped).stamp
        assertEquals(Files.size(root.resolve("repo/README.md")), stamp.sizeBytes)
        assertTrue(stamp.sha256.startsWith("sha256:"), "o resumo não vem rotulado")
        assertTrue(stamp.modifiedAtEpochMillis > 0)
    }

    @Test
    fun `documentacao de pasta tambem e carimbada por arquivo`() {
        val resultado = SourceStampReader.stamp(
            context,
            provenance(kind = SourceKind.DOCUMENTATION, sourceId = "eventos", path = "S-1210.md"),
        )

        assertTrue(resultado is StampResult.Stamped)
    }

    /** O que a validação em campo pegou: três motivos diferentes davam a mesma mensagem. */
    @Test
    fun `os tres motivos de nao carimbar chegam distintos`() {
        assertSame(
            StampResult.UnknownSource,
            SourceStampReader.stamp(context, provenance(sourceId = "repositorio-que-nao-existe", path = "x.md")),
        )
        assertSame(
            StampResult.PathNotFound,
            SourceStampReader.stamp(context, provenance(path = "nao/existe.md")),
        )
        assertSame(
            StampResult.PathExcluded,
            SourceStampReader.stamp(context, provenance(path = "segredos/chaves.txt")),
        )
    }

    @Test
    fun `caminho excluido nao e carimbado nem com a caixa trocada`() {
        assertSame(
            StampResult.PathExcluded,
            SourceStampReader.stamp(context, provenance(path = "SEGREDOS/chaves.txt")),
        )
    }

    /**
     * A revisão pegou isto: a regra de exclusão tinha sido reescrita aqui em vez de delegada, e
     * divergiu da canônica em dois pontos. Em campo, `remember` aceitou `.git/config` como fonte
     * enquanto `read_file` recusava o mesmo caminho.
     */
    @Test
    fun `o diretorio git nunca e carimbado, mesmo sem estar na lista de exclusao`() {
        root.resolve("repo/.git").createDirectories()
        root.resolve("repo/.git/config").writeText("[core]")

        assertSame(StampResult.PathExcluded, SourceStampReader.stamp(context, provenance(path = ".git/config")))
        assertSame(StampResult.PathExcluded, SourceStampReader.stamp(context, provenance(path = ".GIT/config")))
    }

    /** A canônica casa a exclusão por segmento; a regra reescrita só olhava o prefixo. */
    @Test
    fun `exclusao vale no meio do caminho, nao so no comeco`() {
        root.resolve("repo/modulo/segredos").createDirectories()
        root.resolve("repo/modulo/segredos/x.txt").writeText("nada")

        assertSame(
            StampResult.PathExcluded,
            SourceStampReader.stamp(context, provenance(path = "modulo/segredos/x.txt")),
        )
    }

    /** E o prefixo sozinho não pode excluir um irmão de nome parecido. */
    @Test
    fun `nome que apenas comeca igual ao excluido nao e barrado`() {
        root.resolve("repo/segredosdopassado.md").writeText("texto publico")

        assertTrue(
            SourceStampReader.stamp(context, provenance(path = "segredosdopassado.md")) is StampResult.Stamped,
        )
    }

    /**
     * O fail-closed que a 0.3.0 introduziu no `RepositoryReports.locate`, e que esta família não
     * herdava: um vínculo mais abrangente não pode carimbar o que o vínculo mais próximo excluiu.
     * Sem ele, basta nomear o repositório de cima para alcançar o que o de baixo recusa.
     */
    @Test
    fun `vinculo mais abrangente nao carimba o que o mais proximo excluiu`() {
        val guardaChuva = RepositoryBinding(
            id = "guarda-chuva",
            name = "guarda-chuva",
            localPath = root.toString(),
            role = RepositoryRole.REFERENCE,
            accessMode = AccessMode.READ_ONLY,
        )
        val comDois = context.workspace.copy(
            repositories = context.workspace.repositories + guardaChuva,
        )
        // O vínculo corrente é o de cima, e quem exclui é o de baixo: sem o fail-closed entre
        // vínculos, consultar só o corrente deixaria o caminho passar.
        val aninhado = WorkspaceContext(comDois, guardaChuva)

        assertSame(
            StampResult.PathExcluded,
            SourceStampReader.stamp(
                aninhado,
                provenance(sourceId = "guarda-chuva", path = "repo/segredos/chaves.txt"),
            ),
            "o vínculo de cima carimbou o que o de baixo excluiu",
        )
        assertTrue(
            SourceStampReader.stamp(aninhado, provenance(sourceId = "guarda-chuva", path = "repo/README.md"))
                is StampResult.Stamped,
            "o vínculo de cima deixou de alcançar o que ninguém excluiu",
        )
    }

    @Test
    fun `mudar o conteudo muda o carimbo`() {
        val antes = SourceStampReader.stamp(context, provenance(path = "README.md")).stampOrNull!!

        root.resolve("repo/README.md").writeText("conteudo alterado, e bem maior do que era antes\n")
        val depois = SourceStampReader.stamp(context, provenance(path = "README.md")).stampOrNull!!

        assertNotEquals(antes, depois)
        assertEquals(Freshness.STALE, freshnessOf(antes, depois))
    }

    @Test
    fun `fonte que sumiu do disco responde ausente, e o registro fica orfao`() {
        val antes = SourceStampReader.stamp(context, provenance(path = "README.md")).stampOrNull!!

        Files.delete(root.resolve("repo/README.md"))
        val depois = SourceStampReader.stamp(context, provenance(path = "README.md"))

        assertNull(depois.stampOrNull)
        assertEquals(Freshness.ORPHAN, freshnessOf(antes, depois.stampOrNull))
    }

    @Test
    fun `dois arquivos de mesmo tamanho nao compartilham carimbo`() {
        root.resolve("repo/outro.md").writeText("conteudo iniciaX\n")

        val um = SourceStampReader.stamp(context, provenance(path = "README.md")).stampOrNull!!
        val outro = SourceStampReader.stamp(context, provenance(path = "outro.md")).stampOrNull!!

        assertEquals(um.sizeBytes, outro.sizeBytes, "o teste perdeu o alvo: os tamanhos deviam coincidir")
        assertNotEquals(um.sha256, outro.sha256, "o resumo não distinguiu conteúdos de mesmo tamanho")
    }

    @Test
    fun `caminho excluido esta fora de alcance`() {
        root.resolve("repo/.git").createDirectories()
        root.resolve("repo/.git/config").writeText("[core]")
        root.resolve("repo/modulo/segredos").createDirectories()
        root.resolve("repo/modulo/segredos/x.txt").writeText("nada")

        listOf("segredos/chaves.txt", "SEGREDOS/chaves.txt", ".git/config", "modulo/segredos/x.txt").forEach {
            assertTrue(SourceStampReader.outOfReach(context, provenance(path = it)), "'$it' devia estar fora de alcance")
        }
    }

    @Test
    fun `caminho que ninguem excluiu esta ao alcance`() {
        root.resolve("repo/segredosdopassado.md").writeText("texto publico")

        listOf("README.md", "segredosdopassado.md").forEach {
            assertFalse(SourceStampReader.outOfReach(context, provenance(path = it)), "'$it' devia estar ao alcance")
        }
    }

    @Test
    fun `fonte de documentacao nunca esta fora de alcance`() {
        val documento = provenance(kind = SourceKind.DOCUMENTATION, sourceId = "eventos", path = "S-1210.md")

        assertFalse(SourceStampReader.outOfReach(context, documento))
    }

    /** Fonte que o workspace não conhece é outro caso, e quem o distingue é o carimbo. */
    @Test
    fun `fonte desconhecida e caminho ausente nao sao fora de alcance`() {
        assertFalse(SourceStampReader.outOfReach(context, provenance(sourceId = "nao-existe", path = "x.md")))
        assertFalse(SourceStampReader.outOfReach(context, provenance(path = null)))
    }

    /**
     * A decisão de alcance tem um dono só: se o carimbo deixar de consultá-la, as duas respostas se
     * separam e a exclusão volta a valer em um lugar e não no outro.
     */
    @Test
    fun `o alcance e o carimbo concordam sempre`() {
        root.resolve("repo/.git").createDirectories()
        root.resolve("repo/.git/config").writeText("[core]")
        root.resolve("repo/segredosdopassado.md").writeText("texto publico")

        listOf(
            "README.md",
            "segredos/chaves.txt",
            "SEGREDOS/chaves.txt",
            ".git/config",
            "segredosdopassado.md",
            "nao/existe.md",
        ).forEach { caminho ->
            val fora = SourceStampReader.outOfReach(context, provenance(path = caminho))
            val carimbo = SourceStampReader.stamp(context, provenance(path = caminho))

            assertEquals(
                fora,
                carimbo === StampResult.PathExcluded,
                "'$caminho': alcance disse $fora e o carimbo disse $carimbo",
            )
        }
    }

    /** O mesmo fail-closed entre vínculos aninhados, pela porta nova. */
    @Test
    fun `o alcance do vinculo mais abrangente respeita a exclusao do mais proximo`() {
        val guardaChuva = RepositoryBinding(
            id = "guarda-chuva",
            name = "guarda-chuva",
            localPath = root.toString(),
            role = RepositoryRole.REFERENCE,
            accessMode = AccessMode.READ_ONLY,
        )
        val aninhado = WorkspaceContext(
            context.workspace.copy(repositories = context.workspace.repositories + guardaChuva),
            guardaChuva,
        )

        assertTrue(
            SourceStampReader.outOfReach(
                aninhado,
                provenance(sourceId = "guarda-chuva", path = "repo/segredos/chaves.txt"),
            ),
        )
        assertFalse(
            SourceStampReader.outOfReach(aninhado, provenance(sourceId = "guarda-chuva", path = "repo/README.md")),
        )
    }

    private fun provenance(
        kind: SourceKind = SourceKind.REPOSITORY,
        sourceId: String = "folha-esocial",
        path: String? = null,
    ) = Provenance(
        sourceKind = kind,
        sourceId = sourceId,
        path = path,
        stamp = SourceStamp(0, 0, ""),
    )
}
