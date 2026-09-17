package ai.rever.boss.plugin.packs

import ai.rever.boss.mcp.McpPolicyAction

/**
 * A scriptable [PluginPackEffects] that records every call.
 *
 * Installing, re-versioning or enabling a plugin updates [installed], and a written rule updates the
 * rule maps, so a second apply against the same fake sees the first one's effects, as the host would.
 */
internal class FakePackEffects(
    val installed: MutableMap<String, InstalledPlugin> = mutableMapOf(),
    val store: MutableMap<String, StoreListing> = mutableMapOf(),
    val toolRules: MutableMap<String, McpPolicyAction> = mutableMapOf(),
    val providerRules: MutableMap<String, McpPolicyAction> = mutableMapOf(),
    var policyReadable: Boolean = true,
) : PluginPackEffects {
    val calls = mutableListOf<String>()
    var snapshots = 0
    val failures = mutableMapOf<String, Throwable>()
    val ruleWrites = mutableMapOf<String, RuleWrite>()
    var beforeSnapshot: suspend () -> Unit = {}

    override suspend fun snapshot(pack: PluginPack): PackSnapshot {
        beforeSnapshot()
        snapshots++
        return PackSnapshot(installed.toMap(), store.toMap(), toolRules.toMap(), providerRules.toMap(), policyReadable)
    }

    override suspend fun install(
        pluginId: String,
        version: String,
        latest: Boolean,
    ): Result<Unit> {
        calls += "install $pluginId $version latest=$latest"
        return outcome(pluginId) { installed[pluginId] = InstalledPlugin(version, enabled = true) }
    }

    override suspend fun changeVersion(
        pluginId: String,
        version: String,
    ): Result<Unit> {
        calls += "changeVersion $pluginId $version"
        return outcome(pluginId) { installed[pluginId] = InstalledPlugin(version, enabled = true) }
    }

    override suspend fun enable(pluginId: String): Result<Unit> {
        calls += "enable $pluginId"
        return outcome(pluginId) { installed[pluginId] = installed.getValue(pluginId).copy(enabled = true) }
    }

    override fun addRule(rule: PackRule): RuleWrite {
        calls += "addRule ${rule.scope} ${rule.subject} ${rule.action}"
        val map = if (rule.scope == PackRuleScope.TOOL) toolRules else providerRules
        return ruleWrites[rule.subject]
            ?: if (rule.subject in map) {
                RuleWrite.KEPT_EXISTING
            } else {
                map[rule.subject] = rule.action
                RuleWrite.ADDED
            }
    }

    private fun outcome(
        pluginId: String,
        onSuccess: () -> Unit,
    ): Result<Unit> {
        failures[pluginId]?.let { throw it }
        onSuccess()
        return Result.success(Unit)
    }
}
