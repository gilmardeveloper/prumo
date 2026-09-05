package io.prumo.mcp.datasource

import io.prumo.mcp.audit.AuditLog
import io.prumo.mcp.audit.AuditResult
import io.prumo.mcp.datasource.domain.DataSourceProfile

/**
 * Registro do teste de conexão na trilha de auditoria.
 *
 * Guarda o desfecho e nada mais: sem host, usuário, banco ou mensagem do driver.
 */
object DataSourceAudit {

    const val TOOL = "prumo_ide"
    const val OPERATION = "datasource.test_connection"

    fun recordConnectionTest(
        log: AuditLog,
        workspaceId: String,
        profile: DataSourceProfile,
        outcome: ConnectionTestOutcome,
        durationMillis: Long,
    ) {
        log.record(
            workspaceId = workspaceId,
            tool = TOOL,
            operation = OPERATION,
            result = if (outcome == ConnectionTestOutcome.SUCCESS) AuditResult.SUCCESS else AuditResult.ERROR,
            durationMillis = durationMillis,
            datasourceId = profile.id,
            details = mapOf("outcome" to outcome.name),
        )
    }
}
