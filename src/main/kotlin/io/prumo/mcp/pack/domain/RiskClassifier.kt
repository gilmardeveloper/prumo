package io.prumo.mcp.pack.domain

import io.prumo.mcp.datasource.security.SqlClassification
import io.prumo.mcp.datasource.security.SqlStatementClassifier
import io.prumo.mcp.policy.Capability
import java.util.Locale

/** Quatro níveis, na ordem em que a gravidade cresce. `BLOCKED` não tem caminho de aceitação. */
enum class RiskLevel {
    SAFE,
    SENSITIVE,
    DESTRUCTIVE,
    BLOCKED,
}

/** Um sinal encontrado no pack, com o trecho exato que o originou. */
data class RiskFinding(
    val level: RiskLevel,
    val rule: String,
    val explanation: String,
    val evidence: String,
    val location: String,
)

data class RiskAssessment(
    val level: RiskLevel,
    val findings: List<RiskFinding>,
) {
    val blocked: Boolean get() = level == RiskLevel.BLOCKED

    val destructive: Boolean get() = level == RiskLevel.DESTRUCTIVE
}

/**
 * Análise estática do que o pack é capaz de fazer.
 *
 * Detecção de sinal, não prova: script ofuscado passa. As regras são explícitas e determinísticas —
 * mesmo pack, mesmo veredito.
 */
object RiskClassifier {

    fun assess(manifest: PackManifest): RiskAssessment {
        val findings = buildList {
            manifest.tools.forEach { tool ->
                addAll(undeclaredCapabilities(manifest, tool))
                addAll(secretFindings(tool))
                tool.sql?.let { addAll(sqlFindings(tool, it)) }
                tool.commands.forEach { (platform, command) ->
                    addAll(commandFindings(tool, platform, command.joinToString(" ")))
                }
            }
        }

        val level = findings.maxByOrNull { it.level.ordinal }?.level ?: RiskLevel.SAFE
        return RiskAssessment(level, findings.sortedByDescending { it.level.ordinal })
    }

    /** Capacidade não declarada é recusa, não alerta. */
    private fun undeclaredCapabilities(manifest: PackManifest, tool: PackTool): List<RiskFinding> {
        val required = buildSet {
            if (!tool.sql.isNullOrBlank()) add(Capability.DATASOURCE_QUERY)
            if (tool.commands.isNotEmpty()) add(Capability.PROCESS_EXECUTE)
            addAll(tool.capabilities)
        }
        return required.filterNot { it in manifest.capabilities }.map { capability ->
            RiskFinding(
                level = RiskLevel.BLOCKED,
                rule = "undeclared-capability",
                explanation = "The tool needs ${capability.name} but the pack did not declare it.",
                evidence = capability.name,
                location = "tool:${tool.id}",
            )
        }
    }

    /**
     * Segredo dentro do pack é recusa, não alerta.
     *
     * O pack viaja para outra máquina e para outro desenvolvedor: uma senha escrita aqui é uma senha
     * publicada. A referência ao banco é um identificador lógico, resolvido no destino.
     */
    private fun secretFindings(tool: PackTool): List<RiskFinding> = buildList {
        val reference = tool.datasourceRef
        if (reference != null && CONNECTION_STRING.containsMatchIn(reference)) {
            add(
                RiskFinding(
                    level = RiskLevel.BLOCKED,
                    rule = "datasource-ref-is-connection-string",
                    explanation = "The data source reference is a connection string, not a logical id. " +
                        "A pack travels to another machine, and this one carries how to reach a database.",
                    evidence = reference.take(EVIDENCE_LENGTH).substringBefore(':'),
                    location = "tool:${tool.id}",
                ),
            )
        }
        val secretIn = listOfNotNull(tool.sql, reference).firstOrNull { SECRET.containsMatchIn(it) }
        if (secretIn != null) {
            add(
                RiskFinding(
                    level = RiskLevel.BLOCKED,
                    rule = "secret-inside-pack",
                    explanation = "The pack carries what looks like a password, token or key. Nothing " +
                        "secret can travel inside a pack.",
                    evidence = SECRET.find(secretIn)?.groupValues?.getOrNull(1).orEmpty(),
                    location = "tool:${tool.id}",
                ),
            )
        }
    }

    private fun sqlFindings(tool: PackTool, sql: String): List<RiskFinding> {
        val classification = SqlStatementClassifier.classify(sql)
        if (classification is SqlClassification.Denied) {
            return listOf(
                RiskFinding(
                    level = RiskLevel.DESTRUCTIVE,
                    rule = "sql-not-read-only",
                    explanation = "The saved query is not a read statement: ${classification.reason}",
                    evidence = sql.trim().take(EVIDENCE_LENGTH),
                    location = "tool:${tool.id}",
                ),
            )
        }
        return emptyList()
    }

    private fun commandFindings(tool: PackTool, platform: String, command: String): List<RiskFinding> {
        val normalized = command.lowercase(Locale.ROOT)
        return RULES.mapNotNull { rule ->
            if (!rule.matches(normalized)) {
                return@mapNotNull null
            }
            RiskFinding(
                level = rule.level,
                rule = rule.name,
                explanation = rule.explanation,
                evidence = command.take(EVIDENCE_LENGTH),
                location = "tool:${tool.id} ($platform)",
            )
        }
    }

    private class Rule(
        val name: String,
        val level: RiskLevel,
        val explanation: String,
        val matches: (String) -> Boolean,
    )

    /** Regra por trecho literal: o comando contém alguma destas formas. */
    /** Esquema de conexão em lugar de identificador lógico. */
    private val CONNECTION_STRING = Regex("""^\s*(jdbc:|postgres(ql)?://|mysql://|sqlserver://)""", RegexOption.IGNORE_CASE)

    /** Nome de campo de segredo seguido de valor. O grupo 1 é só o nome, nunca o valor. */
    private val SECRET = Regex(
        """(password|passwd|senha|secret|api[_-]?key|token|access[_-]?key)\s*[=:]\s*\S""",
        RegexOption.IGNORE_CASE,
    )

    private fun anyOf(vararg fragments: String): (String) -> Boolean =
        { command -> fragments.any { command.contains(it) } }

    /**
     * Regra por palavra inteira: o comando usa algum destes verbos.
     *
     * `del` e `rd` são curtos e vivem dentro de outras palavras — `model`, `handle`, `board` —, então
     * casar por trecho literal marcaria comando inocente como destrutivo.
     */
    private fun anyWord(vararg words: String): (String) -> Boolean {
        val pattern = Regex("(^|[^a-z0-9_-])(" + words.joinToString("|") { Regex.escape(it) } + ")([^a-z0-9_-]|$)")
        return { command -> pattern.containsMatchIn(command) }
    }

    /**
     * Baixar-e-executar é a combinação de duas coisas, não um trecho fixo: alguma forma de trazer
     * conteúdo da rede **e** um cano para um interpretador. `curl https://… | sh` tem o endereço no
     * meio, e procurar por `curl | sh` literal deixaria passar justamente o caso real.
     */
    private fun downloadAndExecute(): (String) -> Boolean = { command ->
        DOWNLOADERS.any { command.contains(it) } && PIPE_TO_INTERPRETER.containsMatchIn(command)
    }

    private const val EVIDENCE_LENGTH = 400

    private val DOWNLOADERS = listOf("curl", "wget", "invoke-webrequest", "iwr ", "irm ", "fetch ")

    private val PIPE_TO_INTERPRETER =
        Regex("""\|\s*(sh|bash|zsh|ksh|iex|invoke-expression|python[0-9.]*|perl|ruby|node)\b""")

    /**
     * Corpus de sinais, por plataforma.
     *
     * Cada regra cita o comando concreto que a dispara.
     */
    private val RULES = listOf(
        Rule(
            name = "download-and-execute",
            level = RiskLevel.BLOCKED,
            explanation = "Downloads content from the network and runs it. What actually runs is decided elsewhere, after you approve.",
            matches = downloadAndExecute(),
        ),
        Rule(
            name = "obfuscated-command",
            level = RiskLevel.BLOCKED,
            explanation = "Hides what it runs behind encoding or dynamic evaluation, so nobody can review it.",
            matches = anyOf(
                "-encodedcommand", "frombase64string", "base64 -d", "base64 --decode",
                "invoke-expression", "iex(", "iex (",
            ),
        ),
        Rule(
            name = "credential-access",
            level = RiskLevel.BLOCKED,
            explanation = "Reads stored credentials or dumps the environment, which is where secrets live.",
            matches = anyOf(
                ".aws/credentials", "id_rsa", ".git-credentials", ".npmrc", ".pgpass",
                "printenv", "get-childitem env:", "gci env:", "/etc/shadow",
            ),
        ),
        Rule(
            name = "other-workspace",
            level = RiskLevel.BLOCKED,
            explanation = "Reaches the Prumo directory of other workspaces, which no pack may see.",
            matches = { command ->
                listOf("prumomcp", "prumo-mcp").any { command.contains(it) } && command.contains("workspaces")
            },
        ),
        Rule(
            name = "recursive-delete",
            level = RiskLevel.DESTRUCTIVE,
            explanation = "Deletes files recursively and without confirmation.",
            matches = anyOf("rm -rf", "rm -fr", "remove-item -recurse", "remove-item -force", "rmdir /s", "rd /s"),
        ),
        Rule(
            name = "windows-delete",
            level = RiskLevel.DESTRUCTIVE,
            explanation = "Deletes files with the Windows shell, which asks nothing and reports nothing.",
            matches = anyWord("del", "erase", "rd", "rmdir"),
        ),
        Rule(
            name = "backup-destruction",
            level = RiskLevel.BLOCKED,
            explanation = "Destroys the copies the machine keeps to recover from a mistake, which is how ransomware works.",
            matches = anyOf("vssadmin delete", "shadowcopy delete", "delete shadows", "wbadmin delete", "bcdedit /set"),
        ),
        Rule(
            name = "disk-write",
            level = RiskLevel.DESTRUCTIVE,
            explanation = "Writes directly to a device or formats a volume.",
            matches = anyOf("mkfs", "dd if=", "format-volume", "diskpart", "shred ", "> /dev/sd", "cipher /w"),
        ),
        Rule(
            name = "volume-format",
            level = RiskLevel.DESTRUCTIVE,
            explanation = "Formats a volume, which erases everything on it.",
            matches = anyWord("format"),
        ),
        Rule(
            name = "git-history-rewrite",
            level = RiskLevel.DESTRUCTIVE,
            explanation = "Rewrites or discards Git history, which can destroy work that was never pushed.",
            matches = anyOf(
                "git reset --hard", "git push --force", "git push -f", "git clean -fd",
                "git checkout -- .", "git filter-branch", "git branch -d",
            ),
        ),
        Rule(
            name = "permission-change",
            level = RiskLevel.DESTRUCTIVE,
            explanation = "Changes file permissions or execution policy, weakening the machine's defences.",
            matches = anyOf("chmod 777", "chmod -r 777", "set-executionpolicy", "icacls", "takeown"),
        ),
        Rule(
            name = "process-or-machine-control",
            level = RiskLevel.DESTRUCTIVE,
            explanation = "Kills processes or shuts the machine down.",
            matches = anyOf("kill -9", "taskkill /f", "stop-computer", "shutdown", "pkill"),
        ),
        Rule(
            name = "network-egress",
            level = RiskLevel.SENSITIVE,
            explanation = "Sends or fetches data over the network. What leaves the machine is not visible here.",
            matches = anyOf("curl ", "wget ", "invoke-webrequest", "nc ", "scp ", "ssh ", "ftp "),
        ),
        Rule(
            name = "absolute-path",
            level = RiskLevel.SENSITIVE,
            explanation = "Uses a machine-specific path, so the pack may not work — or may reach the wrong place — on another machine.",
            matches = anyOf("c:\\users\\", "c:/users/", "/home/", "/users/", "%userprofile%", "~/"),
        ),
        Rule(
            name = "package-install",
            level = RiskLevel.SENSITIVE,
            explanation = "Installs software on the machine.",
            matches = anyOf("npm install -g", "pip install", "apt-get install", "choco install", "winget install"),
        ),
    )
}
