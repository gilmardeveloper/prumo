package io.prumo.mcp.security

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Mapa entre as garantias de segurança e os testes que as sustentam.
 *
 * Cada garantia precisa existir como teste nomeado e marcado com `@Tag("security")`. Este teste lê
 * a própria suíte e falha quando um deles some.
 */
@Tag("security")
class SecurityCoverageTest {

    private val requirements = listOf(
        Requirement(
            "isolamento entre workspaces",
            listOf("nao aparece no outro", "nao alcanca pack de outro workspace", "conteudo de um workspace nao aparece em outro"),
        ),
        Requirement(
            "READ_ONLY de repositorio e de datasource",
            listOf("somente leitura nao e gravavel", "somente leitura recusa escrita", "nao torna esta consulta gravavel"),
        ),
        Requirement(
            "classificacao de SQL",
            listOf("escrita declarada e recusada", "dois statements na mesma chamada", "CTE que escreve"),
        ),
        Requirement(
            "caminho nao escapa da raiz",
            listOf("caminho absoluto", "fora da raiz do repositorio", "aponta para fora do pack"),
        ),
        Requirement(
            "credencial nunca serializada",
            listOf("nao carrega senha", "sem a senha", "nao tem campo algum de segredo"),
        ),
        Requirement(
            "fingerprint sobrevive a mudanca de caminho",
            listOf("depois de mudar de diretorio", "mesmo repositorio"),
        ),
        Requirement(
            "nenhum arquivo do Prumo dentro dos repositorios",
            listOf("nada e escrito fora do diretorio do workspace", "trabalha dentro do proprio pack"),
        ),
        Requirement(
            "export sem segredo e import recusando o malformado",
            listOf("arquivo alterado depois de empacotado", "arquivo que nao e pack"),
        ),
        Requirement(
            "pack nunca ativado sem aprovacao",
            listOf("nada e instalado sem aceite", "submeter enfileira e nao instala", "so a fila de aprovacao instala"),
        ),
        Requirement(
            "capacidade nao concedida",
            listOf("capacidade nao declarada", "sem a capacidade declarada nao executa"),
        ),
        Requirement(
            "submit jamais instala",
            listOf("o toolset de autoria nao instala nada"),
        ),
        Requirement(
            "RiskClassifier marca destrutivo e bloqueado",
            listOf("sao bloqueados", "sao destrutivos", "consulta de leitura e segura"),
        ),
        Requirement(
            "execucao de script confinada",
            listOf("ambiente da IDE nao chega ao script", "estourar o tempo mata o processo", "saida grande demais e cortada"),
        ),
        Requirement(
            "documentacao resolve caminho pelo validador do produto",
            listOf("caractere de controle no caminho", "travessia disfarcada", "caminho absoluto de windows"),
        ),
        Requirement(
            "consentimento registrado e reforcado",
            listOf("aceite reforcado", "bloqueado nao tem caminho de aceitacao"),
        ),
    )

    @Test
    fun `todo principio inviolavel tem teste nomeado e marcado como seguranca`() {
        val tagged = taggedSecurityTests()
        assertTrue(tagged.size > MINIMUM_SECURITY_TESTS, "esperava uma suite de seguranca real, achei ${tagged.size} testes")

        val faltando = requirements.filter { requirement ->
            requirement.evidence.none { evidence -> tagged.any { it.contains(evidence, ignoreCase = true) } }
        }

        assertTrue(
            faltando.isEmpty(),
            "sem teste de seguranca correspondente: " + faltando.joinToString("; ") { it.name },
        )
    }

    @Test
    fun `as classes de seguranca estao todas marcadas`() {
        val marcadas = testSources()
            .filter { it.contains("@Tag(\"security\")") }
            .count()

        assertTrue(marcadas >= MINIMUM_SECURITY_CLASSES, "esperava ao menos $MINIMUM_SECURITY_CLASSES arquivos marcados, achei $marcadas")
    }

    private fun taggedSecurityTests(): List<String> =
        testSources()
            .filter { it.contains("@Tag(\"security\")") }
            .flatMap { source -> TEST_NAME.findAll(source).map { it.groupValues[1] }.toList() }

    private fun testSources(): List<String> {
        val root = Path.of("src/test/kotlin")
        return Files.walk(root).use { paths ->
            paths.filter { it.extension == "kt" }
                .map { Files.readString(it) }
                .toList()
        }
    }

    private data class Requirement(val name: String, val evidence: List<String>)

    private companion object {
        const val MINIMUM_SECURITY_TESTS = 60
        const val MINIMUM_SECURITY_CLASSES = 15
        val TEST_NAME = Regex("fun `([^`]+)`")
    }
}
