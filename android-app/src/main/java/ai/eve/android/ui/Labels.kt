package ai.eve.android.ui

import ai.eve.core.agent.AgentResult
import ai.eve.core.llm.ProviderId
import ai.eve.core.llm.RoutingMode

/** Pure display helpers (no Android dependencies) so they can be unit-tested on the JVM. */
object Labels {
    /** Header subtitle. Display only — it is never stored or used as a model ID. */
    fun routing(active: List<ProviderId>, mode: RoutingMode, preferred: ProviderId?, models: (ProviderId) -> String): String = when {
        active.isEmpty() -> "Offline demo"
        mode == RoutingMode.MANUAL -> {
            val p = preferred?.takeIf { it in active } ?: active.first()
            "Manual · ${p.displayName} (${models(p)})"
        }
        active.size == 1 -> "${active.single().displayName} (${models(active.single())})"
        else -> "Automatic · " + active.sortedBy { if (it == preferred) -1 else it.ordinal }.joinToString(", ") { it.displayName }
    }

    /** Small attribution line under an assistant message. */
    fun attribution(r: AgentResult, models: (ProviderId) -> String): String? {
        val synth = r.synthesizer
        return when {
        r.collaborated && synth != null ->
            r.providers.joinToString(" + ") { it.displayName } + " · synthesized by ${synth.displayName}"
        r.collaborated -> r.providers.joinToString(" + ") { it.displayName }
        r.providers.isEmpty() -> null
        r.providers.size == 1 -> "via ${r.providers.single().displayName} · ${models(r.providers.single())}"
        else -> "via " + r.providers.joinToString(" → ") { it.displayName } + " (fallback)"
        }
    }

    const val COLLABORATION_COST =
        "Complex questions are sent to every enabled provider plus one synthesis call — up to 4× the API cost of a normal answer. " +
            "Secondary models only analyse; phone actions always go through EVE's approval rules."
}
