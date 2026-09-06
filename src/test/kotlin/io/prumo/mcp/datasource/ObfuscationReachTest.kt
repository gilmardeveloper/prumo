package io.prumo.mcp.datasource

import io.prumo.mcp.datasource.application.ReadOnlyQueryExecutor
import io.prumo.mcp.datasource.domain.DataSourceProfile
import io.prumo.mcp.datasource.security.PersonalDataKind
import io.prumo.mcp.datasource.security.PersonalDataObfuscator
import io.prumo.mcp.pack.execution.PackQueryRunner
import io.prumo.mcp.workspace.domain.AccessMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.reflect.full.declaredMemberFunctions

/**
 * A ofuscação precisa alcançar toda saída de célula, não apenas a do Core Toolkit.
 *
 * Há dois caminhos de consulta e o de packs não passa política de máscara: herda o valor padrão do
 * parâmetro, silenciosamente. Injetar a proteção nos chamadores deixaria esse caminho descoberto sem
 * que o compilador acusasse, e é por isso que ela vive dentro do executor.
 */
@Tag("security")
class ObfuscationReachTest {

    @Test
    fun `a ofuscacao nasce ligada num datasource recem-criado`() {
        val profile = DataSourceProfile(
            id = "dev",
            name = "dev",
            host = "localhost",
            database = "folha",
            user = "leitor",
        )

        assertTrue(profile.obfuscatePersonalData, "um banco recém-vinculado precisa proteger sem configuração")
    }

    @Test
    fun `o interruptor pode ser desligado deliberadamente`() {
        val profile = DataSourceProfile(
            id = "dev",
            name = "dev",
            host = "localhost",
            database = "folha",
            user = "leitor",
            accessMode = AccessMode.READ_ONLY,
            obfuscatePersonalData = false,
        )

        assertEquals(false, profile.obfuscatePersonalData)
    }

    /**
     * Interruptor sem controle na tela é interruptor que não existe.
     *
     * A primeira versão gravou o campo no domínio, respeitou-o na execução e publicou-o para a IA,
     * mas não colocou a caixa de seleção no formulário: ficava sempre ligado, sem como desligar.
     */
    @Test
    fun `o formulario do banco oferece o interruptor e o le do perfil`() {
        val fonte = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/io/prumo/mcp/ui/datasource/DataSourceDialog.kt"),
        )

        assertTrue(fonte.contains("datasource.field.obfuscate"), "o formulário não oferece o interruptor")
        assertTrue(
            fonte.contains("existing?.obfuscatePersonalData"),
            "o formulário não lê o estado gravado: reabrir o banco perderia a escolha",
        )
        assertTrue(
            fonte.contains("obfuscatePersonalData = obfuscatePersonalData"),
            "o formulário não grava a escolha no perfil",
        )
    }

    @Test
    fun `desligado, o dado pessoal sai exatamente como esta no banco`() {
        val kind = PersonalDataObfuscator.classify("num_cpf", "12345678901")

        assertEquals("123***789**", PersonalDataObfuscator.obfuscate(kind, "12345678901"))
        assertEquals(
            "12345678901",
            PersonalDataObfuscator.obfuscate(PersonalDataKind.NONE, "12345678901"),
            "com o interruptor desligado a coluna vira NONE e o valor atravessa intacto",
        )
    }

    /**
     * O ponto de aplicação é o executor, e ele recebe o perfil. Se algum dia a decisão subir para os
     * chamadores, este teste falha e o caminho de packs volta a ficar descoberto.
     */
    @Test
    fun `o executor recebe o perfil, que e onde mora o interruptor`() {
        val execute = ReadOnlyQueryExecutor::class.declaredMemberFunctions.single { it.name == "execute" }

        val parametros = execute.parameters.mapNotNull { it.name }
        assertTrue("profile" in parametros, "o executor precisa do perfil para decidir a ofuscação: $parametros")
    }

    /**
     * O caminho de packs não passa `masking` — herda o default. Se a ofuscação dependesse de um
     * argumento passado pelo chamador, este caminho sairia sem proteção.
     */
    @Test
    fun `o caminho de packs alcanca o mesmo executor`() {
        val runner = PackQueryRunner::class.java
        val usaExecutor = runner.declaredFields.any { field ->
            field.type.name.contains("QueryExecutor") || field.type.name.contains("QueryExecution")
        }

        assertTrue(usaExecutor, "o runner de pack precisa executar pelo mesmo executor: ${runner.declaredFields.map { it.type.name }}")
    }

    @Test
    fun `a coluna publica a categoria aplicada sem alterar nome nem tipo`() {
        val column = io.prumo.mcp.datasource.application.QueryColumn(
            name = "num_cpf",
            type = "varchar",
            masked = false,
            obfuscatedAs = PersonalDataKind.CPF,
        )

        assertEquals("num_cpf", column.name, "o nome da coluna nunca é ofuscado")
        assertEquals("varchar", column.type, "o tipo da coluna nunca é ofuscado")
        assertEquals(PersonalDataKind.CPF, column.obfuscatedAs)
    }

    @Test
    fun `coluna sem dado pessoal declara categoria nenhuma`() {
        val column = io.prumo.mcp.datasource.application.QueryColumn(
            name = "total",
            type = "int8",
            masked = false,
        )

        assertEquals(PersonalDataKind.NONE, column.obfuscatedAs)
    }
}
