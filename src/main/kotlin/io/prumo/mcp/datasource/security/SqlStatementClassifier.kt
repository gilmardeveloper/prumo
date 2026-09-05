package io.prumo.mcp.datasource.security

import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.ExplainStatement
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.UnsupportedStatement
import net.sf.jsqlparser.statement.select.ParenthesedSelect
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.select.SetOperationList

/** O que o Prumo entendeu do statement. Vai para a auditoria; o SQL em si, nunca. */
enum class SqlStatementType {
    SELECT,
    EXPLAIN,
    WRITE,
    MULTIPLE,
    UNPARSEABLE,
    OTHER,
}

sealed interface SqlClassification {

    val type: SqlStatementType

    data class Allowed(override val type: SqlStatementType) : SqlClassification

    data class Denied(override val type: SqlStatementType, val reason: String) : SqlClassification

    val allowed: Boolean get() = this is Allowed
}

/**
 * Camada 3 da defesa em profundidade da seção 9: decide se um statement é de leitura.
 *
 * A decisão é por **lista de permissão sobre a árvore sintática**, não por reconhecimento de texto.
 * Só `SELECT`, `WITH … SELECT` e `EXPLAIN` sem `ANALYZE` passam; qualquer outra forma é recusada,
 * inclusive a que o parser não entendeu. Recusar o que não se entendeu é o ponto: uma sintaxe
 * desconhecida pode ser um `DELETE` de um dialeto que a biblioteca não cobre.
 *
 * A análise usa JSqlParser porque escrever o próprio reconhecedor de SQL é como a maioria dos
 * bypass de segurança acontece — um `;` dentro de literal, um comentário aninhado, um caractere
 * unicode equivalente, e a expressão regular que parecia certa deixa passar uma escrita.
 *
 * Esta camada **não** é a barreira principal: a credencial somente-leitura no banco é. Ela existe
 * para recusar cedo, com mensagem clara, o que o banco recusaria depois com erro obscuro.
 */
object SqlStatementClassifier {

    fun classify(sql: String): SqlClassification {
        if (sql.isBlank()) {
            return SqlClassification.Denied(SqlStatementType.UNPARSEABLE, "The statement is empty.")
        }

        // `Statements` e uma `List<Statement>`; `getStatements()` esta deprecado na 5.3.
        val statements: List<Statement> = try {
            CCJSqlParserUtil.parseStatements(sql)
        } catch (failure: Exception) {
            // Inclui erro de sintaxe e timeout interno do parser. Falha fechada, sempre.
            return SqlClassification.Denied(
                SqlStatementType.UNPARSEABLE,
                "Prumo could not parse this statement, so it will not run it. " +
                    "Only SELECT, WITH … SELECT and EXPLAIN are accepted.",
            )
        }

        if (statements.size != 1) {
            return SqlClassification.Denied(
                SqlStatementType.MULTIPLE,
                "Send one statement at a time: ${statements.size} were found.",
            )
        }

        return classifyOne(statements.single())
    }

    private fun classifyOne(statement: Statement): SqlClassification = when (statement) {
        is Select -> classifySelect(statement)
        is ExplainStatement -> classifyExplain(statement)

        is UnsupportedStatement -> SqlClassification.Denied(
            SqlStatementType.UNPARSEABLE,
            "Prumo does not recognise this statement and will not run it.",
        )

        else -> SqlClassification.Denied(
            typeOf(statement),
            "Only read statements are accepted here: ${statement.javaClass.simpleName} is not one.",
        )
    }

    private fun classifySelect(select: Select): SqlClassification {
        writingWithItem(select)?.let { return it }

        val plain = when (select) {
            is PlainSelect -> select
            is ParenthesedSelect -> return classifySelect(select.select)
            is SetOperationList -> return classifySetOperation(select)
            else -> null
        }

        // `SELECT … INTO` cria tabela. É escrita com forma de leitura, e é o caso que mais engana.
        if (plain != null && (!plain.intoTables.isNullOrEmpty() || plain.intoTempTable != null)) {
            return SqlClassification.Denied(
                SqlStatementType.WRITE,
                "SELECT … INTO creates a table and is not a read statement.",
            )
        }

        return SqlClassification.Allowed(SqlStatementType.SELECT)
    }

    private fun classifySetOperation(operation: SetOperationList): SqlClassification {
        operation.selects.forEach { part ->
            val classification = classifySelect(part)
            if (classification is SqlClassification.Denied) {
                return classification
            }
        }
        return SqlClassification.Allowed(SqlStatementType.SELECT)
    }

    /**
     * `WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x` escreve no banco e termina em
     * `SELECT`. Cada item do `WITH` precisa ser, ele próprio, uma consulta.
     */
    private fun writingWithItem(select: Select): SqlClassification.Denied? {
        val items = select.withItemsList ?: return null
        items.forEach { item ->
            val inner = item.parenthesedStatement
            if (inner !is ParenthesedSelect) {
                return SqlClassification.Denied(
                    SqlStatementType.WRITE,
                    "The WITH clause contains a statement that writes to the database.",
                )
            }
        }
        return null
    }

    private fun classifyExplain(explain: ExplainStatement): SqlClassification {
        val analyze = explain.options?.keys?.any { it == ExplainStatement.OptionType.ANALYZE } ?: false
        if (analyze) {
            // EXPLAIN ANALYZE executa o comando de verdade: em um INSERT, escreve.
            return SqlClassification.Denied(
                SqlStatementType.WRITE,
                "EXPLAIN ANALYZE runs the statement for real. Use EXPLAIN without ANALYZE.",
            )
        }
        val inner = explain.statement
            ?: return SqlClassification.Denied(
                SqlStatementType.UNPARSEABLE,
                "Prumo could not read what this EXPLAIN refers to.",
            )
        val classification = classifySelect(inner)
        return if (classification is SqlClassification.Denied) {
            classification
        } else {
            SqlClassification.Allowed(SqlStatementType.EXPLAIN)
        }
    }

    private fun typeOf(statement: Statement): SqlStatementType {
        val name = statement.javaClass.simpleName
        return if (WRITING_STATEMENTS.any { name.startsWith(it) }) {
            SqlStatementType.WRITE
        } else {
            SqlStatementType.OTHER
        }
    }

    private val WRITING_STATEMENTS = listOf(
        "Insert", "Update", "Delete", "Merge", "Upsert", "Truncate", "Create", "Alter", "Drop",
        "Grant", "Revoke", "Execute", "Call", "Copy", "Refresh", "Comment", "Rename",
    )
}
