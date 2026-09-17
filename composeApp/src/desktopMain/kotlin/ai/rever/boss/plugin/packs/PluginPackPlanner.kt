package ai.rever.boss.plugin.packs

import ai.rever.boss.mcp.McpPolicyAction

/** What the host knows about one plugin when a pack is planned. */
data class InstalledPlugin(
    val version: String,
    val enabled: Boolean,
)

/**
 * The store's answer about one plugin.
 *
 * [Unreachable] is kept apart from [NotPublished] because "could not ask" and "does not exist" need
 * different next steps, and reporting the first as the second is a mistake this codebase has made
 * before (see `StoreMissingDependencyInstaller.installFromStore`).
 */
sealed interface StoreListing {
    data class Published(
        val latest: String,
        val versions: Set<String>,
    ) : StoreListing

    data object NotPublished : StoreListing

    data class Unreachable(
        val reason: String,
    ) : StoreListing
}

/** Everything a plan is computed from, read once so the plan describes one consistent moment. */
data class PackSnapshot(
    val installed: Map<String, InstalledPlugin>,
    val store: Map<String, StoreListing>,
    val toolRules: Map<String, McpPolicyAction>,
    val providerRules: Map<String, McpPolicyAction>,
    val policyReadable: Boolean,
)

/** What applying a pack would do for one plugin row. */
enum class PluginStepKind {
    /** Installed, enabled, and at the requested version (or any version, when none was named). */
    SATISFIED,

    /** Installed at the requested version but disabled. */
    ENABLE,

    /** Not installed; the store has what was asked for. */
    INSTALL,

    /** Installed at a different version than the one named; the store has the named one. */
    CHANGE_VERSION,

    /** The store does not publish this plugin, or not at the named version. */
    UNAVAILABLE,

    /** The store could not be asked. Nothing is known either way. */
    STORE_UNREACHABLE,
}

data class PluginStep(
    val plugin: PackPlugin,
    val kind: PluginStepKind,
    /** The version the step installs, or null when it installs nothing. */
    val targetVersion: String?,
    val installedVersion: String?,
    val detail: String,
    /** Whether [targetVersion] is the store's current release. */
    val targetIsLatest: Boolean = false,
) {
    val needsWork: Boolean
        get() = kind == PluginStepKind.ENABLE || kind == PluginStepKind.INSTALL || kind == PluginStepKind.CHANGE_VERSION
    val blocked: Boolean get() = kind == PluginStepKind.UNAVAILABLE || kind == PluginStepKind.STORE_UNREACHABLE
}

/** What applying a pack would do for one policy rule. */
enum class RuleStepKind {
    /** No rule exists for this subject; the pack's rule would be added. */
    ADD,

    /** The operator already has exactly this rule. */
    ALREADY_SET,

    /** The operator has a different rule. A pack never overrides one, in either direction. */
    KEPT_EXISTING,

    /** The policy file is unreadable, so the host is failing closed and nothing is written. */
    POLICY_UNREADABLE,
}

data class RuleStep(
    val rule: PackRule,
    val kind: RuleStepKind,
    val existing: McpPolicyAction?,
)

/** The full, side-effect-free answer to "what would applying this pack do?". */
data class PackPlan(
    val pack: PluginPack,
    val plugins: List<PluginStep>,
    val rules: List<RuleStep>,
) {
    /**
     * True when the pack is already in effect: no plugin needs work and every rule is either set or
     * deliberately kept. A rule blocked by an unreadable policy is not in effect, so it is not.
     */
    val satisfied: Boolean
        get() =
            plugins.none { it.needsWork } &&
                rules.all { it.kind == RuleStepKind.ALREADY_SET || it.kind == RuleStepKind.KEPT_EXISTING }

    /** Required rows that cannot be satisfied; applying would leave the pack incomplete. */
    val requiredBlocked: List<PluginStep>
        get() = plugins.filter { it.blocked && !it.plugin.optional }
}

/**
 * Turns a pack and a [PackSnapshot] into a [PackPlan], without touching anything.
 *
 * Kept pure so every row kind is reachable from a test, and so `pack_plan` and `pack_apply` cannot
 * disagree: the applier re-plans against a fresh snapshot with this same function immediately before
 * acting, rather than trusting a plan the caller computed earlier.
 */
object PluginPackPlanner {
    fun plan(
        pack: PluginPack,
        snapshot: PackSnapshot,
    ): PackPlan =
        PackPlan(
            pack = pack,
            plugins = pack.plugins.map { pluginStep(it, snapshot) },
            rules = pack.rules.map { ruleStep(it, snapshot) },
        )

    private fun pluginStep(
        plugin: PackPlugin,
        snapshot: PackSnapshot,
    ): PluginStep {
        val installed = snapshot.installed[plugin.pluginId]
        val step = Stepper(plugin, installed)
        val wanted = plugin.version
        return when {
            installed != null && (wanted == null || wanted == installed.version) -> {
                if (installed.enabled) {
                    step.make(PluginStepKind.SATISFIED, "Installed at ${installed.version}.")
                } else {
                    step.make(PluginStepKind.ENABLE, "Installed at ${installed.version} but disabled.")
                }
            }

            else -> {
                storeStep(step, snapshot.store[plugin.pluginId])
            }
        }
    }

    private fun storeStep(
        step: Stepper,
        listing: StoreListing?,
    ): PluginStep =
        when (listing) {
            null, StoreListing.NotPublished -> {
                step.make(PluginStepKind.UNAVAILABLE, "Not published in the plugin store.")
            }

            is StoreListing.Unreachable -> {
                step.make(PluginStepKind.STORE_UNREACHABLE, listing.reason)
            }

            is StoreListing.Published -> {
                publishedStep(step, listing)
            }
        }

    private fun publishedStep(
        step: Stepper,
        listing: StoreListing.Published,
    ): PluginStep {
        val wanted = step.plugin.version
        val target = wanted ?: listing.latest
        val installed = step.installed
        return when {
            wanted != null && wanted !in listing.versions -> {
                step.make(
                    PluginStepKind.UNAVAILABLE,
                    "The store does not publish version $wanted (latest is ${listing.latest}).",
                )
            }

            installed == null -> {
                step.make(PluginStepKind.INSTALL, "Install $target from the plugin store.", target, listing.latest)
            }

            else -> {
                step.make(
                    PluginStepKind.CHANGE_VERSION,
                    "Replace ${installed.version} with $target from the plugin store.",
                    target,
                    listing.latest,
                )
            }
        }
    }

    /** Builds the steps for one plugin row, so each call site names only what differs. */
    private class Stepper(
        val plugin: PackPlugin,
        val installed: InstalledPlugin?,
    ) {
        fun make(
            kind: PluginStepKind,
            detail: String,
            target: String? = null,
            latest: String? = null,
        ) = PluginStep(
            plugin = plugin,
            kind = kind,
            targetVersion = target,
            installedVersion = installed?.version,
            detail = detail,
            targetIsLatest = target != null && target == latest,
        )
    }

    private fun ruleStep(
        rule: PackRule,
        snapshot: PackSnapshot,
    ): RuleStep {
        if (!snapshot.policyReadable) return RuleStep(rule, RuleStepKind.POLICY_UNREADABLE, null)
        val existing =
            when (rule.scope) {
                PackRuleScope.TOOL -> snapshot.toolRules[rule.subject]
                PackRuleScope.PROVIDER -> snapshot.providerRules[rule.subject]
            }
        val kind =
            when (existing) {
                null -> RuleStepKind.ADD
                rule.action -> RuleStepKind.ALREADY_SET
                else -> RuleStepKind.KEPT_EXISTING
            }
        return RuleStep(rule, kind, existing)
    }
}
