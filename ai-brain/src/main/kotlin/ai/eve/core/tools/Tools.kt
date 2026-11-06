package ai.eve.core.tools

import ai.eve.core.llm.ToolSpec

/**
 * Risk classes drive the policy engine. They are declared by the tool author and
 * cannot be lowered at runtime by the model.
 */
enum class RiskLevel {
    /** Reads non-sensitive state (time, own memory). Runs without asking. */
    READ_ONLY,
    /** Visible, easily reversible actions (open an app, create a reminder). */
    LOW,
    /** Touches personal data or other people (read SMS, place a call). Needs approval unless a standing grant exists. */
    SENSITIVE,
    /** Deletes or overwrites data. Always needs approval for each call. */
    DESTRUCTIVE,
    /** Moves money or makes purchases. Always needs approval; never grantable. */
    FINANCIAL,
    /** Reads or uses passwords, tokens, keys. Always needs approval; never grantable. */
    CREDENTIAL,
}

data class ToolResult(val ok: Boolean, val output: String) {
    companion object {
        fun ok(output: String) = ToolResult(true, output)
        fun error(output: String) = ToolResult(false, output)
    }
}

interface Tool {
    val name: String
    val description: String
    val parameters: Map<String, String>
    val risk: RiskLevel
    val required: List<String> get() = parameters.keys.toList()
    /** Short human sentence shown in approval dialogs, e.g. "Call +972… ". */
    fun describeCall(args: Map<String, String>): String = "$name ${args.entries.joinToString { "${it.key}=${it.value}" }}"
    suspend fun execute(args: Map<String, String>): ToolResult

    fun spec() = ToolSpec(name, description, parameters, required)
}

class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool>()

    fun register(tool: Tool): ToolRegistry {
        require(tool.name.matches(Regex("[a-z][a-z0-9_]{0,63}"))) { "Invalid tool name: ${tool.name}" }
        require(tool.name !in tools) { "Duplicate tool: ${tool.name}" }
        tools[tool.name] = tool
        return this
    }

    operator fun get(name: String): Tool? = tools[name]
    fun all(): List<Tool> = tools.values.toList()
    fun specs(): List<ToolSpec> = tools.values.map { it.spec() }
}

/** Convenience for small tools. */
fun tool(
    name: String,
    description: String,
    risk: RiskLevel,
    parameters: Map<String, String> = emptyMap(),
    required: List<String> = parameters.keys.toList(),
    describe: ((Map<String, String>) -> String)? = null,
    block: suspend (Map<String, String>) -> ToolResult,
): Tool = object : Tool {
    override val name = name
    override val description = description
    override val parameters = parameters
    override val risk = risk
    override val required = required
    override fun describeCall(args: Map<String, String>) = describe?.invoke(args) ?: super.describeCall(args)
    override suspend fun execute(args: Map<String, String>) = block(args)
}
