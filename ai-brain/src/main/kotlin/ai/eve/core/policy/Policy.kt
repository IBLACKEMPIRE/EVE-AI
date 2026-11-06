package ai.eve.core.policy

import ai.eve.core.tools.RiskLevel
import ai.eve.core.tools.Tool
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Global kill switch. Once engaged, every tool call is denied and running agents stop at the next step. */
class EmergencyStop {
    private val engaged = AtomicBoolean(false)
    val isEngaged: Boolean get() = engaged.get()
    fun engage() = engaged.set(true)
    fun release() = engaged.set(false)
}

sealed interface Decision {
    data object Allow : Decision
    data class RequireApproval(val reason: String) : Decision
    data class Deny(val reason: String) : Decision
}

/**
 * Deterministic gate between the model and the device. The model can propose any call;
 * only this engine (plus the user) decides whether it runs.
 */
class PolicyEngine(private val stop: EmergencyStop) {
    private val standingGrants = ConcurrentHashMap.newKeySet<String>()
    private val blocked = ConcurrentHashMap.newKeySet<String>()

    /** User says "always allow EVE to do X". Only SENSITIVE tools can be granted. */
    fun grant(tool: Tool): Boolean {
        if (tool.risk != RiskLevel.SENSITIVE) return false
        standingGrants += tool.name
        return true
    }

    fun revoke(toolName: String) { standingGrants -= toolName }
    fun block(toolName: String) { blocked += toolName }
    fun unblock(toolName: String) { blocked -= toolName }
    fun grants(): Set<String> = standingGrants.toSet()

    fun evaluate(tool: Tool?, toolName: String): Decision {
        if (stop.isEngaged) return Decision.Deny("Emergency stop is engaged")
        if (tool == null) return Decision.Deny("Unknown tool '$toolName'")
        if (tool.name in blocked) return Decision.Deny("Tool '${tool.name}' is blocked by the user")
        return when (tool.risk) {
            RiskLevel.READ_ONLY, RiskLevel.LOW -> Decision.Allow
            RiskLevel.SENSITIVE ->
                if (tool.name in standingGrants) Decision.Allow
                else Decision.RequireApproval("Uses personal data or contacts other people")
            RiskLevel.DESTRUCTIVE -> Decision.RequireApproval("Deletes or overwrites data")
            RiskLevel.FINANCIAL -> Decision.RequireApproval("Financial action — explicit confirmation required")
            RiskLevel.CREDENTIAL -> Decision.RequireApproval("Credential access — explicit confirmation required")
        }
    }
}

data class ApprovalRequest(val toolName: String, val risk: RiskLevel, val summary: String, val reason: String)

/** Implemented by the UI (dialog, notification action, voice confirmation). Must return false on timeout. */
fun interface ApprovalGateway {
    suspend fun requestApproval(request: ApprovalRequest): Boolean
}

/** For headless/background runs: nothing that needs approval is ever executed. */
object DenyAllApprovals : ApprovalGateway {
    override suspend fun requestApproval(request: ApprovalRequest) = false
}
