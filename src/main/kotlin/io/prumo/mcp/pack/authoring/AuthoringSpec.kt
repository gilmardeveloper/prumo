package io.prumo.mcp.pack.authoring

import io.prumo.mcp.pack.domain.PackTool
import io.prumo.mcp.platform.ProcessRequest
import kotlinx.serialization.Serializable

@Serializable
data class SpecField(
    val name: String,
    val type: String,
    val required: Boolean,
    val explanation: String,
    val example: String,
)

@Serializable
data class SpecCapability(
    val name: String,
    val allows: String,
    val requires: String,
)

@Serializable
data class SpecRejection(
    val rule: String,
    val why: String,
    val fix: String,
)

@Serializable
data class AuthoringSpec(
    val prumoPackVersion: Int,
    val summary: String,
    val manifestFields: List<SpecField>,
    val knowledgeFields: List<SpecField>,
    val toolFields: List<SpecField>,
    val capabilities: List<SpecCapability>,
    val rules: List<String>,
    val limits: Map<String, String>,
    val installation: String,
    val examples: Map<String, String>,
    val commonRejections: List<SpecRejection>,
)

/**
 * O que o Prumo ensina a uma LLM antes de ela escrever um pack.
 *
 * A LLM não adivinha o formato — ela consulta. E a especificação diz também **o que faz um pack ser
 * recusado**, porque um motivo de recusa antecipado é o que faz o modelo convergir em vez de tentar
 * contornar (seção 8.4).
 *
 * A mesma especificação vale para quem escreve à mão: não existe caminho exclusivo da IA.
 */
object PackAuthoringSpec {

    fun spec(): AuthoringSpec = AuthoringSpec(
        prumoPackVersion = 1,
        summary = "A Prumo Pack is a single JSON file with a manifest, optional knowledge documents " +
            "and optional tools. It lives inside the workspace, never inside the user's repository, " +
            "and it is only installed after the developer accepts it on the consent screen.",
        manifestFields = listOf(
            SpecField("id", "string", true, "Identifier of the pack. Letters, digits, hyphen and underscore.", "payroll-tools"),
            SpecField("version", "string", true, "Version of this pack, chosen by the author.", "1.0.0"),
            SpecField(
                "title",
                "string or localized object",
                true,
                "Short name. English is the base; other languages are optional additions.",
                """{"default": "Payroll tools", "pt-BR": "Ferramentas da folha"}""",
            ),
            SpecField("description", "string or localized object", true, "What the pack is for.", "Saved queries of the payroll team"),
            SpecField("author", "string", false, "Who wrote it.", "Payroll team"),
            SpecField(
                "capabilities",
                "array of capability names",
                false,
                "Everything the tools need. A capability that is not declared makes the pack refused.",
                """["DATASOURCE_QUERY"]""",
            ),
            SpecField("knowledge", "array of knowledge items", false, "Documents carried by the pack.", "see the knowledge example"),
            SpecField("tools", "array of tools", false, "Saved queries and scripts.", "see the tool example"),
            SpecField("createdAt", "ISO-8601 instant", true, "When the pack was created.", "2026-09-05T12:00:00Z"),
            SpecField("updatedAt", "ISO-8601 instant", true, "When it last changed.", "2026-09-05T12:00:00Z"),
        ),
        knowledgeFields = listOf(
            SpecField("id", "string", true, "Identifier of the document inside the pack.", "payroll-rules"),
            SpecField("title", "string or localized object", true, "Title of the document.", "Payroll rules"),
            SpecField("file", "relative path", true, "Path inside the pack. Absolute paths are refused.", "knowledge/rules.md"),
            SpecField("tags", "array of strings", false, "Words that help the deterministic search find it.", """["payroll", "rules"]"""),
        ),
        toolFields = listOf(
            SpecField("id", "string", true, "Identifier of the tool.", "compare-tables"),
            SpecField("kind", "QUERY or SCRIPT", true, "QUERY runs read-only SQL; SCRIPT runs a command.", "QUERY"),
            SpecField("sql", "string", false, "Required for QUERY. Must be a read statement.", "SELECT count(*) FROM servidor"),
            SpecField(
                "datasourceRef",
                "string",
                false,
                "Logical id of the data source, resolved in the destination workspace. Never a connection string.",
                "payroll",
            ),
            SpecField(
                "commands",
                "object of os to argument list",
                false,
                "Required for SCRIPT. Keys: windows, linux, macos. Already split into arguments, never a shell line.",
                """{"linux": ["/bin/sh", "-c", "./compare.sh"]}""",
            ),
            SpecField("timeoutSeconds", "integer", false, "Time limit of a script. Default 30, ceiling 300.", "60"),
        ),
        capabilities = listOf(
            SpecCapability("REPOSITORY_READ", "Read files from the repositories bound to the workspace.", "Nothing else."),
            SpecCapability(
                "DATASOURCE_QUERY",
                "Run read-only SQL on the databases of the workspace.",
                "The SQL must be a read statement; writes are refused.",
            ),
            SpecCapability("DOCUMENTATION_READ", "Read the documentation attached to the workspace.", "Nothing else."),
            SpecCapability(
                "PROCESS_EXECUTE",
                "Run a command on the developer's machine, confined to the pack directory.",
                "The developer must accept it explicitly, and the workspace policy must allow process execution.",
            ),
        ),
        rules = listOf(
            "Declare every capability the tools need. An undeclared capability makes the pack refused, not merely flagged.",
            "Never put a password, token or connection string inside a pack. Reference data sources by their logical id.",
            "Never use absolute paths or paths from your own machine: a pack travels to other machines.",
            "Never reach the Prumo directory of another workspace.",
            "Never download something and run it. What runs must be visible in the pack itself.",
            "SQL tools must be read statements: SELECT, WITH … SELECT or EXPLAIN without ANALYZE.",
            "Scripts are argument lists, not shell lines, so that the developer can read exactly what will run.",
            "English is the base for every text; other languages are optional additions.",
        ),
        limits = mapOf(
            "script timeout" to "${PackTool.DEFAULT_TIMEOUT_SECONDS}s by default, ${PackTool.MAX_TIMEOUT_SECONDS}s maximum",
            "script output" to "${ProcessRequest.DEFAULT_MAX_OUTPUT_BYTES / 1024} KB, truncated beyond that",
            "identifiers" to "letters, digits, hyphen and underscore, up to 64 characters",
        ),
        installation = "Prumo installs the pack under the workspace directory of the operating system " +
            "(never inside a repository). Submitting a pack does not install it: it goes to the " +
            "approval queue in the Prumo tool window, and only the developer can activate it.",
        examples = mapOf(
            "knowledge-only" to KNOWLEDGE_EXAMPLE,
            "saved-query" to QUERY_EXAMPLE,
            "script" to SCRIPT_EXAMPLE,
        ),
        commonRejections = listOf(
            SpecRejection(
                "undeclared-capability",
                "A tool needs a capability that the manifest did not declare.",
                "Add the capability to the manifest 'capabilities' list.",
            ),
            SpecRejection(
                "sql-not-read-only",
                "A saved query writes to the database.",
                "Rewrite it as SELECT, WITH … SELECT or EXPLAIN without ANALYZE.",
            ),
            SpecRejection(
                "download-and-execute",
                "The script downloads content and runs it, so what runs cannot be reviewed.",
                "Carry the script inside the pack and run it from there.",
            ),
            SpecRejection(
                "absolute-path",
                "The pack points at a path of the machine where it was written.",
                "Use paths relative to the pack, or reference the repository by its id.",
            ),
            SpecRejection(
                "missing-english",
                "A localized text has no 'default' entry.",
                "Add 'default' with the English text; other languages stay as extra keys.",
            ),
        ),
    )

    private const val KNOWLEDGE_EXAMPLE = """{
  "prumoPackVersion": 1,
  "manifest": {
    "id": "payroll-knowledge",
    "version": "1.0.0",
    "title": {"default": "Payroll knowledge", "pt-BR": "Conhecimento da folha"},
    "description": "Business rules of the payroll system",
    "capabilities": [],
    "knowledge": [
      {"id": "rules", "title": "Payroll rules", "file": "knowledge/rules.md", "tags": ["payroll"]}
    ],
    "createdAt": "2026-09-05T12:00:00Z",
    "updatedAt": "2026-09-05T12:00:00Z"
  },
  "knowledge": {"rules": "# Payroll rules\n Rubric 101 is the base salary."}
}"""

    private const val QUERY_EXAMPLE = """{
  "prumoPackVersion": 1,
  "manifest": {
    "id": "payroll-queries",
    "version": "1.0.0",
    "title": "Payroll queries",
    "description": "Saved read-only queries",
    "capabilities": ["DATASOURCE_QUERY"],
    "tools": [
      {
        "id": "headcount",
        "title": "Headcount",
        "description": "How many active employees",
        "kind": "QUERY",
        "capabilities": ["DATASOURCE_QUERY"],
        "datasourceRef": "payroll",
        "sql": "SELECT count(*) AS total FROM servidor WHERE ativo"
      }
    ],
    "createdAt": "2026-09-05T12:00:00Z",
    "updatedAt": "2026-09-05T12:00:00Z"
  }
}"""

    private const val SCRIPT_EXAMPLE = """{
  "prumoPackVersion": 1,
  "manifest": {
    "id": "payroll-scripts",
    "version": "1.0.0",
    "title": "Payroll scripts",
    "description": "Comparison script kept by the team",
    "capabilities": ["PROCESS_EXECUTE"],
    "tools": [
      {
        "id": "compare",
        "title": "Compare tables",
        "description": "Compares the legacy table with the new one",
        "kind": "SCRIPT",
        "capabilities": ["PROCESS_EXECUTE"],
        "commands": {
          "windows": ["cmd.exe", "/c", "compare.bat"],
          "linux": ["/bin/sh", "-c", "./compare.sh"]
        },
        "timeoutSeconds": 60
      }
    ],
    "createdAt": "2026-09-05T12:00:00Z",
    "updatedAt": "2026-09-05T12:00:00Z"
  }
}"""
}
