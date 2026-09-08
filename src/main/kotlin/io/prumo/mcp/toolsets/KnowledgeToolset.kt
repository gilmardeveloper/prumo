package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import io.prumo.mcp.ide.PrumoWorkspaceService
import io.prumo.mcp.ide.SourceStampReader
import io.prumo.mcp.ide.StampResult
import io.prumo.mcp.knowledge.Admission
import io.prumo.mcp.knowledge.AdmissionContext
import io.prumo.mcp.knowledge.Freshness
import io.prumo.mcp.knowledge.KnowledgeAdmission
import io.prumo.mcp.knowledge.KnowledgeRecord
import io.prumo.mcp.knowledge.Provenance
import io.prumo.mcp.knowledge.SourceKind
import io.prumo.mcp.knowledge.SourceStamp
import io.prumo.mcp.knowledge.freshnessOf
import io.prumo.mcp.knowledge.recallRecords
import io.prumo.mcp.policy.PolicyAction
import io.prumo.mcp.workspace.application.WorkspaceContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * A memória que um cliente de IA constrói e reaproveita.
 *
 * O que entra aqui deriva de fontes que já estavam ao alcance do workspace, e sai sempre com a
 * procedência e o veredicto de frescor ao lado.
 */
class KnowledgeToolset : McpToolset {

    @McpTool(name = REMEMBER_TOOL)
    @McpDescription(
        "Use this tool to store what you distilled from a source of this workspace, so that you " +
            "and any other AI client can reuse it in a later session instead of reading the " +
            "expensive source again. Call it after you worked out something durable from " +
            "documentation or repository files. Prumo stamps the source itself: you name where the " +
            "knowledge came from, and it records the size, date and digest of that file. What " +
            "Prumo guarantees about a record is where it came from, whether the source has changed " +
            "since, who wrote it and when. What Prumo does NOT guarantee is that your summary is " +
            "faithful to the source, or that it can replace the source: it cites, it does not " +
            "substitute — Prumo stamps where your text came from, it cannot check that your text " +
            "follows from there, and that judgement stays yours. What it does refuse is a record " +
            "naming a source it cannot reach: for material brought from outside this workspace, " +
            "packs are the channel, and they need the developer's consent.",
    )
    suspend fun remember(
        @McpDescription("Stable id for this record: letters, digits, hyphen or underscore. Reusing an id replaces it.")
        knowledgeId: String,
        @McpDescription("Short title of what was learned.")
        title: String,
        @McpDescription("The distilled text itself.")
        body: String,
        @McpDescription("\"DOCUMENTATION\" or \"REPOSITORY\": which family the source belongs to.")
        sourceKind: String,
        @McpDescription("Id of the documentation source or of the repository the knowledge came from.")
        sourceId: String,
        @McpDescription("Path inside that source, relative. Required for a repository source.")
        path: String? = null,
        @McpDescription("Tags for later retrieval — they are how you will find this again.")
        tags: List<String> = emptyList(),
        @McpDescription("First line of the cited range, when it came from part of a file.")
        firstLine: Int? = null,
        @McpDescription("Last line of the cited range.")
        lastLine: Int? = null,
    ): KnowledgeWriteResponse =
        prumoToolCall(REMEMBER_TOOL, "knowledge.remember", PolicyAction.WRITE_KNOWLEDGE) { call ->
            val kind = kindOf(sourceKind)
            val provenance = Provenance(
                sourceKind = kind,
                sourceId = sourceId,
                path = path,
                firstLine = firstLine,
                lastLine = lastLine,
                stamp = EMPTY_STAMP,
            )
            val stamp = when (val result = withContext(Dispatchers.IO) {
                SourceStampReader.stamp(call.context, provenance)
            }) {
                is StampResult.Stamped -> result.stamp
                StampResult.UnknownSource -> throw McpExpectedError(
                    "There is no ${kind.name.lowercase()} source '$sourceId' in this workspace. " +
                        "List them with prumo_workspace_get_repositories or " +
                        "prumo_workspace_get_documentation_sources.",
                )
                StampResult.PathMissing -> throw McpExpectedError(
                    "Source '$sourceId' is a repository, so 'path' is required: name the file the " +
                        "knowledge came from, relative to the repository root.",
                )
                StampResult.PathNotFound -> throw McpExpectedError(
                    "Source '$sourceId' exists, but path '${path.orEmpty()}' does not exist in it.",
                )
                StampResult.PathExcluded -> throw McpExpectedError(
                    "Path '${path.orEmpty()}' is excluded from '$sourceId' in this workspace, so " +
                        "Prumo does not read it and cannot derive knowledge from it.",
                )
            }

            val now = Instant.now().toString()
            val store = PrumoWorkspaceService.getInstance().knowledge
            val existing = withContext(Dispatchers.IO) { store.get(call.context.workspace.id, knowledgeId) }
            val record = KnowledgeRecord(
                schemaVersion = KnowledgeRecord.CURRENT_SCHEMA_VERSION,
                id = knowledgeId,
                title = title,
                body = body,
                tags = tags,
                provenance = provenance.copy(stamp = stamp),
                author = call.client.label,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )

            when (val verdict = KnowledgeAdmission.evaluate(record, admissionContext(call.context, store, existing))) {
                is Admission.Rejected -> KnowledgeWriteResponse(knowledgeId, stored = false, message = verdict.reason)
                Admission.Accepted -> {
                    withContext(Dispatchers.IO) { store.put(call.context.workspace.id, record) }
                    call.auditDetails["knowledgeId"] = knowledgeId
                    KnowledgeWriteResponse(knowledgeId, stored = true)
                }
            }
        }

    @McpTool(name = RECALL_TOOL)
    @McpDescription(
        "Use this tool to find what was already distilled in this workspace, by text, by tag or by " +
            "source. Call it BEFORE reading a large document again: what you need may already be " +
            "here. A text query is matched by relevance over title, tags and body, so a different " +
            "inflection of the same word finds the record: \"pagamentos\" finds \"pagamento\", " +
            "and \"payments\" finds \"payment\". Portuguese and English are both covered, and " +
            "results come ordered by how well they answer, strongest first. This is still " +
            "deterministic and explainable: it is word matching with stemming and BM25 ranking, " +
            "never an embedding and never a model deciding what is similar. Tag, source and " +
            "freshness stay exact filters. Every result carries its freshness — FRESH means the " +
            "source has not changed since the record was written, STALE means it has and the " +
            "record may be wrong, ORPHAN means the source is gone. FRESH is about the bytes of " +
            "the source, not about the record: it means nobody edited that file, never that Prumo " +
            "checked the text against it. When a text query matches nothing, searchedTerms says " +
            "what was actually searched for: an empty list means the whole query was common words " +
            "and nothing was left to look for, and a filled one means those terms were searched " +
            "and no record has them. Each result carries the score that put it there, " +
            "comparable only against the others in the same answer, and ties are broken by id so " +
            "the same query always returns the same order. The text itself is not returned here; " +
            "read it with prumo_knowledge_read.",
    )
    suspend fun recall(
        @McpDescription(
            "Text to look for in title, tags and body. Matched by relevance, not literally: the " +
                "query is reduced to word stems before the search, so inflections of the same word " +
                "match each other and very common words are dropped.",
        )
        query: String? = null,
        @McpDescription("Keep only records carrying this tag.")
        tag: String? = null,
        @McpDescription("Keep only records distilled from this source id.")
        sourceId: String? = null,
        @McpDescription("Keep only records with this freshness: FRESH, STALE or ORPHAN.")
        freshness: String? = null,
        @McpDescription("How many records to return at most. The counts always cover the whole match.")
        maxResults: Int = 20,
    ): KnowledgeRecallResponse =
        prumoToolCall(RECALL_TOOL, "knowledge.recall", PolicyAction.READ_KNOWLEDGE) { call ->
            val service = PrumoWorkspaceService.getInstance()
            val workspaceId = call.context.workspace.id
            val stored = withContext(Dispatchers.IO) { service.knowledge.list(workspaceId) }
            val found = withContext(Dispatchers.IO) {
                recallRecords(stored, tag, sourceId, query, service.knowledgeSearch)
            }
            val matches = found.records
                .map { (record, score) ->
                    KnowledgeMatch(
                        record = record,
                        freshness = freshnessOf(record.provenance.stamp, stampOf(call.context, record)),
                        score = score,
                    )
                }
                .filter { match -> freshness.isNullOrBlank() || match.freshness.name.equals(freshness, true) }
            KnowledgeReports.search(workspaceId, stored.size, matches, maxResults, found.terms)
        }

    @McpTool(name = READ_TOOL)
    @McpDescription(
        "Use this tool to read a stored record in full, by its id. The answer carries the text " +
            "together with its provenance and its freshness — a STALE record is still returned, " +
            "because what it says may still be useful, but the source is the truth and you should " +
            "go back to it before relying on the record.",
    )
    suspend fun read(
        @McpDescription("Id of the record, from prumo_knowledge_recall.")
        knowledgeId: String,
    ): KnowledgeRecordResponse =
        prumoToolCall(READ_TOOL, "knowledge.read", PolicyAction.READ_KNOWLEDGE) { call ->
            val store = PrumoWorkspaceService.getInstance().knowledge
            val record = withContext(Dispatchers.IO) { store.get(call.context.workspace.id, knowledgeId) }
                ?: throw McpExpectedError("No knowledge record with id '$knowledgeId' in this workspace.")
            call.auditDetails["knowledgeId"] = knowledgeId
            KnowledgeReports.detail(record, freshnessOf(record.provenance.stamp, stampOf(call.context, record)))
        }

    @McpTool(name = FORGET_TOOL)
    @McpDescription(
        "Use this tool to remove a record that is wrong or no longer useful — a STALE record you " +
            "replaced, for instance. Removal is immediate and does not ask the developer. The " +
            "source is untouched: only what was distilled from it goes away.",
    )
    suspend fun forget(
        @McpDescription("Id of the record to remove.")
        knowledgeId: String,
    ): KnowledgeForgetResponse =
        prumoToolCall(FORGET_TOOL, "knowledge.forget", PolicyAction.WRITE_KNOWLEDGE) { call ->
            val store = PrumoWorkspaceService.getInstance().knowledge
            val removed = withContext(Dispatchers.IO) { store.remove(call.context.workspace.id, knowledgeId) }
            call.auditDetails["knowledgeId"] = knowledgeId
            KnowledgeForgetResponse(
                knowledgeId = knowledgeId,
                removed = removed,
                message = if (removed) null else "There was no record with id '$knowledgeId' to remove.",
            )
        }

    private fun kindOf(value: String): SourceKind =
        SourceKind.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
            ?: throw McpExpectedError(
                "Unknown source kind '$value'. Use DOCUMENTATION for a catalogued document, or " +
                    "REPOSITORY for a file of a bound repository.",
            )

    private fun stampOf(context: WorkspaceContext, record: KnowledgeRecord): SourceStamp? =
        SourceStampReader.stamp(context, record.provenance).stampOrNull

    private fun admissionContext(
        context: WorkspaceContext,
        store: io.prumo.mcp.knowledge.KnowledgeStore,
        existing: KnowledgeRecord?,
    ) = AdmissionContext(
        documentationIds = context.workspace.documentation.map { it.id }.toSet(),
        repositoryIds = context.workspace.repositories.map { it.id }.toSet(),
        // Substituir um registro que já existe não faz a base crescer.
        existingRecords = store.list(context.workspace.id).size - if (existing != null) 1 else 0,
    )

    private companion object {
        const val REMEMBER_TOOL = "prumo_knowledge_remember"
        const val RECALL_TOOL = "prumo_knowledge_recall"
        const val READ_TOOL = "prumo_knowledge_read"
        const val FORGET_TOOL = "prumo_knowledge_forget"

        /** Marcador do carimbo antes de o Prumo calculá-lo: nunca chega ao disco. */
        val EMPTY_STAMP = SourceStamp(0, 0, "")
    }
}
