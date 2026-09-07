package io.prumo.mcp.toolsets

import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * A descrição de cada tool é a única superfície pela qual o Prumo se apresenta ao cliente de IA.
 *
 * O servidor MCP da IDE não transmite texto de apresentação, e as tools do Prumo chegam ao cliente
 * no fim de uma lista longa, disputando a mesma necessidade com as tools nativas. Uma descrição que
 * apenas declara o que a tool faz perde para uma que diz quando usá-la.
 */
@Tag("security")
class ToolDescriptionTest {

    private data class Tool(val name: String, val description: String)

    private val tools: List<Tool> = listOf(
        WorkspaceToolset::class.java,
        RepositoryToolset::class.java,
        IdeToolset::class.java,
        DatabaseToolset::class.java,
        PackToolset::class.java,
        PackAuthoringToolset::class.java,
        QualityToolset::class.java,
        KnowledgeToolset::class.java,
    ).flatMap { toolset ->
        toolset.declaredMethods.mapNotNull { method ->
            val name = method.getAnnotation(McpTool::class.java)?.name ?: return@mapNotNull null
            Tool(name, method.getAnnotation(McpDescription::class.java)?.description.orEmpty())
        }
    }

    @Test
    fun `nenhuma descricao entrega o cliente a outra familia de ferramentas`() {
        val cedendo = tools.filter { tool ->
            CESSAO.any { tool.description.contains(it, ignoreCase = true) }
        }

        assertTrue(cedendo.isEmpty(), "descrição cedendo a preferência: ${cedendo.map { it.name }}")
    }

    @Test
    fun `as tools de leitura dizem quando devem ser usadas`() {
        val semInstrucao = tools
            .filter { it.name in INSTRUCTIVE }
            .filterNot { tool -> INSTRUCAO.any { tool.description.contains(it) } }

        assertTrue(semInstrucao.isEmpty(), "descrição sem instrução de uso: ${semInstrucao.map { it.name }}")
    }

    /**
     * O servidor da IDE não transmite texto de apresentação: entre a conexão e a primeira chamada,
     * o cliente recebe do Prumo apenas nomes e descrições. Se nenhuma delas apontar o ponto de
     * partida, ele não existe para quem chega sem contexto.
     */
    @Test
    fun `as tools de entrada apontam o ponto de partida`() {
        val entrada = tools.filter { it.name in ENTRY_POINTS }
        val semRota = entrada.filterNot { it.description.contains(PREPARE) }

        assertTrue(entrada.isNotEmpty(), "nenhuma tool de entrada encontrada")
        assertTrue(semRota.isEmpty(), "tool de entrada sem rota para $PREPARE: ${semRota.map { it.name }}")
    }

    @Test
    fun `o ponto de partida se apresenta como tal`() {
        val prepare = tools.single { it.name == PREPARE }

        assertTrue(prepare.description.contains("Call this first"), "prepare não se anuncia como primeiro passo")
        assertTrue(prepare.description.contains("boundary"), "prepare não explica o que é um workspace")
    }

    /**
     * Descrição é contrato lido por IA: uma garantia escrita ali vale tanto quanto uma no código.
     *
     * A tool de diff chegou a prometer que honrava os caminhos excluídos sem que o código os
     * consultasse. Este teste liga as duas coisas: quem promete a exclusão precisa consultá-la.
     */
    @Test
    fun `tool que promete honrar a exclusao consulta a exclusao no codigo`() {
        val fonte = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/io/prumo/mcp/toolsets/RepositoryToolset.kt"),
        )
        val prometem = tools.filter { tool ->
            PROMESSA_DE_EXCLUSAO.any { tool.description.contains(it, ignoreCase = true) }
        }

        assertTrue(prometem.isNotEmpty(), "nenhuma descrição promete a exclusão; o teste perdeu o alvo")
        val consultas = Regex("excludedPaths").findAll(fonte).count()
        assertTrue(
            consultas >= prometem.size,
            "descrições que prometem a exclusão: ${prometem.map { it.name }}, " +
                "mas o toolset consulta excludedPaths apenas $consultas vezes",
        )
    }

    /**
     * O catálogo diz o que a IDE sabe procurar; a análise de um arquivo é da `get_file_problems`,
     * do próprio servidor da IDE. Uma descrição que não separasse as duas faria o cliente pedir
     * análise a quem só lista regras.
     */
    @Test
    fun `a tool de catalogo nega executar inspecao e aponta a via da analise`() {
        val catalogo = tools.single { it.name == QUALITY_CATALOG }

        assertTrue(
            NEGACAO_DE_ANALISE.any { catalogo.description.contains(it, ignoreCase = true) },
            "a descrição não nega executar inspeção",
        )
        assertTrue(
            catalogo.description.contains(ANALISE_NATIVA),
            "a descrição não aponta $ANALISE_NATIVA como a via da análise por arquivo",
        )
        assertTrue(
            catalogo.description.contains("not the same as applicable", ignoreCase = true),
            "a descrição não separa estar registrada de ser aplicável",
        )
    }

    /**
     * A resposta do catálogo vale conforme o perfil que a produziu: o do projeto obriga o time
     * inteiro, o da instalação vale para quem abriu a IDE. Prometer o campo e não o entregar faria
     * o cliente atribuir ao time uma regra que é de uma máquina só.
     */
    @Test
    fun `a tool de catalogo promete dizer de qual perfil a resposta veio`() {
        val catalogo = tools.single { it.name == QUALITY_CATALOG }

        listOf("source", "PROJECT", "APPLICATION").forEach { termo ->
            assertTrue(catalogo.description.contains(termo), "a descrição não cita '$termo'")
        }
        assertTrue(
            catalogo.description.contains("unknownFilters") &&
                catalogo.description.contains("knownValues"),
            "a descrição não promete distinguir filtro errado de resultado vazio",
        )

        val reports = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/io/prumo/mcp/toolsets/QualityReports.kt"),
        )
        listOf("profileScope", "unknownFilters", "knownValues").forEach { campo ->
            assertTrue(reports.contains(campo), "a resposta não tem o campo '$campo' prometido")
        }
    }

    /**
     * Descrição é contrato: a tool promete não executar inspeção, e o código precisa cumprir.
     * A API que executa está a um import de distância — `InspectionEngine` e `runInspectionOnFile`
     * vivem no mesmo pacote que a enumeração usa.
     */
    @Test
    fun `quem promete nao executar inspecao nao alcanca a API que executa`() {
        val fontes = listOf(
            "src/main/kotlin/io/prumo/mcp/toolsets/QualityToolset.kt",
            "src/main/kotlin/io/prumo/mcp/toolsets/QualityReports.kt",
            "src/main/kotlin/io/prumo/mcp/ide/InspectionCatalogService.kt",
            "src/main/kotlin/io/prumo/mcp/quality/InspectionRecord.kt",
            "src/main/kotlin/io/prumo/mcp/quality/InspectionCatalog.kt",
        ).associateWith { java.nio.file.Files.readString(java.nio.file.Path.of(it)) }

        val infratores = fontes.filterValues { fonte ->
            EXECUCAO_DE_ANALISE.any { fonte.contains(it) }
        }.keys

        assertTrue(infratores.isEmpty(), "a família do catálogo alcança a API de execução: $infratores")
    }

    /**
     * A base de conhecimento é escrita por uma IA, sem consentimento humano por item. O que sustenta
     * isso não é confiança no conteúdo — é o Prumo declarar, na própria descrição, o que ele garante
     * e o que ele se recusa a garantir.
     */
    @Test
    fun `a tool que grava conhecimento declara o que o Prumo nao garante`() {
        val remember = tools.single { it.name == KNOWLEDGE_REMEMBER }

        assertTrue(
            remember.description.contains("does NOT guarantee"),
            "a descrição não declara o que o Prumo se recusa a garantir",
        )
        assertTrue(
            remember.description.contains("faithful"),
            "não diz que a fidelidade do resumo não é garantida",
        )
        assertTrue(
            remember.description.contains("it cites, it does not substitute"),
            "não diz que o registro cita a fonte em vez de substituí-la",
        )
        assertTrue(
            remember.description.contains("packs are the channel"),
            "não encaminha para o pack o material que vem de fora da fronteira",
        )
    }

    /**
     * A validação em campo de 2026-09-07 pegou a descrição prometendo que conhecimento não derivado
     * da fonte seria recusado. Não é o que acontece, nem o que pode acontecer: o Prumo carimba a
     * origem, não julga o conteúdo. Um agente cego gravou um texto inventado carimbado num arquivo
     * real e foi aceito — corretamente.
     */
    @Test
    fun `a tool que grava nao promete recusar conteudo que nao decorre da fonte`() {
        val remember = tools.single { it.name == KNOWLEDGE_REMEMBER }

        assertFalse(
            remember.description.contains("Knowledge that does not derive from a source"),
            "a descrição promete um controle de conteúdo que o produto não faz",
        )
        assertTrue(
            remember.description.contains("it cannot check that your text follows from there"),
            "não declara que a fidelidade não é conferida",
        )
        assertTrue(
            remember.description.contains("a source it cannot reach"),
            "não diz o que de fato é recusado: fonte inalcançável",
        )
    }

    /**
     * Um agente cego leu `FRESH` como "o Prumo conferiu isto" e guardou um registro apontando para
     * um PDF cujo texto o próprio Prumo declara não extrair — e recebeu `FRESH`, corretamente,
     * porque o arquivo não mudou. A descrição precisa dizer de que o veredicto fala.
     */
    @Test
    fun `a busca explica que frescor fala dos bytes da fonte, nao do conteudo do registro`() {
        val recall = tools.single { it.name == KNOWLEDGE_RECALL }

        assertTrue(
            recall.description.contains("about the bytes of the source"),
            "não diz que o veredicto é sobre o arquivo",
        )
        assertTrue(
            recall.description.contains("never that Prumo checked the text"),
            "não desfaz a leitura de que FRESH significaria conteúdo conferido",
        )
    }

    /**
     * O veredicto de frescor só serve se o cliente souber o que fazer com cada valor. Os três
     * precisam estar explicados onde ele os encontra.
     */
    @Test
    fun `a tool de busca explica os tres veredictos de frescor`() {
        val recall = tools.single { it.name == KNOWLEDGE_RECALL }

        listOf("FRESH", "STALE", "ORPHAN").forEach { veredicto ->
            assertTrue(recall.description.contains(veredicto), "não explica $veredicto")
        }
        assertTrue(
            recall.description.contains("no embedding"),
            "não declara que a recuperação é determinística",
        )
    }

    /**
     * Descrição é contrato: quem promete carimbar a fonte por conta própria não pode aceitar o
     * carimbo vindo do cliente, senão a procedência provaria apenas o que a IA disse.
     */
    @Test
    fun `quem promete carimbar a fonte nao aceita carimbo do cliente`() {
        val remember = tools.single { it.name == KNOWLEDGE_REMEMBER }
        assertTrue(remember.description.contains("Prumo stamps the source itself"))

        val fonte = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/io/prumo/mcp/toolsets/KnowledgeToolset.kt"),
        )
        val assinatura = fonte.substringAfter("suspend fun remember(").substringBefore("): KnowledgeWriteResponse")

        listOf("sha256", "sizeBytes", "modifiedAt", "stamp").forEach { proibido ->
            assertFalse(
                assinatura.contains(proibido, ignoreCase = true),
                "a tool aceita '$proibido' do cliente, e o carimbo deixaria de ser prova",
            )
        }
    }

    @Test
    fun `toda tool tem descricao`() {
        val vazias = tools.filter { it.description.isBlank() }

        assertTrue(vazias.isEmpty(), "tool sem descrição: ${vazias.map { it.name }}")
    }

    private companion object {
        /** Formulações que mandam o cliente procurar a resposta fora do Prumo. */
        val CESSAO = listOf(
            "already answer",
            "use the IDE's own",
            "the IDE's own project tools",
        )

        val INSTRUCAO = listOf("Use this tool", "Prefer it over", "Call it")

        /** Formulações que afirmam ao cliente que a tool respeita os caminhos excluídos. */
        val PROMESSA_DE_EXCLUSAO = listOf(
            "paths excluded for this repository",
            "excluded for this repository",
            "paths the developer excluded",
        )

        const val PREPARE = "prumo_workspace_prepare"

        const val QUALITY_CATALOG = "prumo_quality_list_inspections"

        const val KNOWLEDGE_REMEMBER = "prumo_knowledge_remember"

        const val KNOWLEDGE_RECALL = "prumo_knowledge_recall"

        /** A tool nativa da IDE que analisa um arquivo, e que o catálogo não substitui. */
        const val ANALISE_NATIVA = "get_file_problems"

        /** Formulações que negam ao cliente que a tool execute análise. */
        val NEGACAO_DE_ANALISE = listOf("runs no inspection", "does not run", "never runs")

        /** O que a família do catálogo não pode alcançar sem quebrar a promessa da descrição. */
        val EXECUCAO_DE_ANALISE = listOf(
            "InspectionEngine",
            "runInspectionOnFile",
            "inspectEx",
            "GlobalInspectionContext",
            "PsiFile",
            "PsiManager",
        )

        /** Tools por onde um cliente sem contexto chega ao Prumo. */
        val ENTRY_POINTS = setOf(
            "prumo_workspace_get_context",
            "prumo_workspace_get_repositories",
        )

        /**
         * Tools que competem por uma necessidade que a família nativa também atende, e por isso
         * precisam dizer ao cliente quando escolhê-las.
         */
        val INSTRUCTIVE = setOf(
            "prumo_database_describe_table",
            "prumo_database_get_schema",
            "prumo_database_list_available",
            "prumo_database_list_tables",
            "prumo_ide_get_current_context",
            "prumo_knowledge_recall",
            "prumo_quality_list_inspections",
            "prumo_repository_get_branch",
            "prumo_repository_get_diff",
            "prumo_repository_get_status",
            "prumo_repository_get_structure",
            "prumo_repository_read_file",
            "prumo_repository_search_text",
        )
    }
}
