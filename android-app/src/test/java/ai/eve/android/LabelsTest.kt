package ai.eve.android

import ai.eve.android.ui.Labels
import ai.eve.core.agent.AgentResult
import ai.eve.core.llm.ProviderId
import ai.eve.core.llm.RoutingMode
import ai.eve.core.providers.ModelCatalog
import ai.eve.core.tasks.TaskStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LabelsTest {
    private val models = { p: ProviderId -> ModelCatalog.defaults.getValue(p) }
    private val all = ProviderId.entries.toList()

    @Test fun routingLabelIsDisplayOnlyAndNeverAValidModelId() {
        assertEquals("Offline demo", Labels.routing(emptyList(), RoutingMode.AUTOMATIC, null, models))
        val auto = Labels.routing(all, RoutingMode.AUTOMATIC, ProviderId.GEMINI, models)
        assertEquals("Automatic · Gemini, Claude, GPT", auto)
        assertFalse(ModelCatalog.isPlausibleModelId(auto), "the header must never be mistakable for a model ID")
        assertEquals("Manual · GPT (gpt-6-luna)", Labels.routing(all, RoutingMode.MANUAL, ProviderId.OPENAI, models))
        assertEquals("Claude (claude-sonnet-5-5)", Labels.routing(listOf(ProviderId.ANTHROPIC), RoutingMode.AUTOMATIC, null, models))
    }

    @Test fun attributionNamesContributingProviders() {
        fun r(p: List<ProviderId>, collab: Boolean = false, synth: ProviderId? = null) =
            AgentResult("t", TaskStatus.SUCCEEDED, "x", steps = 1, providers = p, synthesizer = synth, collaborated = collab)
        assertNull(Labels.attribution(r(emptyList()), models))
        assertEquals("via Claude · claude-sonnet-5-5", Labels.attribution(r(listOf(ProviderId.ANTHROPIC)), models))
        assertEquals("via Claude → GPT (fallback)", Labels.attribution(r(listOf(ProviderId.ANTHROPIC, ProviderId.OPENAI)), models))
        assertEquals("Claude + Gemini · synthesized by Claude",
            Labels.attribution(r(listOf(ProviderId.ANTHROPIC, ProviderId.GEMINI), true, ProviderId.ANTHROPIC), models))
    }
}
