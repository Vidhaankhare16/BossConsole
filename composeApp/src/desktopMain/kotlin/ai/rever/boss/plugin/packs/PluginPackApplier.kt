package ai.rever.boss.plugin.packs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The outcome of writing one pack rule through the policy engine. */
enum class RuleWrite {
    ADDED,

    /** A rule appeared between planning and writing. The engine refused to replace it. */
    KEPT_EXISTING,

    POLICY_UNREADABLE,

    /** The engine accepted the rule but could not persist it. */
    NOT_SAVED,
}

/**
 * Everything [PluginPackApplier] does to the host, behind one seam.
 *
 * Production wiring is `DesktopPluginPackEffects`, which reaches only install, enable and policy
 * paths the host already trusts. Tests substitute a fake, so every combination of success and
 * failure is reachable without a store, a plugin loader or a policy file.
 */
interface PluginPackEffects {
    /** A consistent view of plugin, store and policy state for [pack]. */
    suspend fun snapshot(pack: PluginPack): PackSnapshot

    /**
     * Put [version] of [pluginId] in place, from the store.
     *
     * @param latest whether [version] is the store's current release, which is the only version the
     *   dependency-resolving installer can fetch
     */
    suspend fun install(
        pluginId: String,
        version: String,
        latest: Boolean,
    ): Result<Unit>

    /** Replace the installed build of [pluginId] with the store's [version]. */
    suspend fun changeVersion(
        pluginId: String,
        version: String,
    ): Result<Unit>

    suspend fun enable(pluginId: String): Result<Unit>

    /** Add [rule] only if no rule exists for its subject. Never replaces an operator's rule. */
    fun addRule(rule: PackRule): RuleWrite
}

enum class PluginResultKind { ALREADY_SATISFIED, DONE, FAILED, BLOCKED }

data class PluginResult(
    val step: PluginStep,
    val kind: PluginResultKind,
    val message: String,
)

enum class RuleResultKind { ADDED, ALREADY_SET, KEPT_EXISTING, POLICY_UNREADABLE, NOT_SAVED }

data class RuleResult(
    val step: RuleStep,
    val kind: RuleResultKind,
)

enum class PackApplyStatus {
    /** Nothing needed doing. */
    ALREADY_SATISFIED,

    /** Every required plugin is in place and every rule the pack could add was added. */
    APPLIED,

    /** Something changed, but a required plugin or a rule did not land. */
    PARTIAL,

    /** A required plugin or a rule did not land, and nothing changed. */
    FAILED,
}

data class PackApplyResult(
    val packId: String,
    val status: PackApplyStatus,
    val plugins: List<PluginResult>,
    val rules: List<RuleResult>,
)

/**
 * Applies a pack: re-plans against a fresh snapshot, then performs each step in the pack's order.
 *
 * Deliberately not all-or-nothing. The host has no transaction spanning plugin installs, and
 * uninstalling a plugin the pack just installed could remove one the operator relied on in the
 * meantime. A row that fails is reported, later rows still run, and the status says [PackApplyStatus.PARTIAL]
 * so a caller can re-apply once the cause is fixed: every row is idempotent, so a second apply
 * does only what the first left undone.
 *
 * An optional plugin that cannot be satisfied is reported but does not make the pack partial.
 */
class PluginPackApplier(
    private val effects: PluginPackEffects,
) {
    suspend fun apply(
        pack: PluginPack,
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> },
    ): PackApplyResult {
        val plan = PluginPackPlanner.plan(pack, effects.snapshot(pack))
        val total = plan.plugins.size + plan.rules.size
        var done = 0

        val pluginResults =
            plan.plugins.map { step ->
                currentCoroutineContext().ensureActive()
                onProgress(done, total, step.plugin.pluginId)
                applyPlugin(step).also { done++ }
            }
        val ruleResults =
            plan.rules.map { step ->
                currentCoroutineContext().ensureActive()
                onProgress(done, total, step.rule.subject)
                applyRule(step).also { done++ }
            }
        onProgress(done, total, "")
        return PackApplyResult(pack.id, statusOf(plan, pluginResults, ruleResults), pluginResults, ruleResults)
    }

    private suspend fun applyPlugin(step: PluginStep): PluginResult {
        val pluginId = step.plugin.pluginId
        val attempt: Result<Unit>? =
            when (step.kind) {
                PluginStepKind.SATISFIED, PluginStepKind.UNAVAILABLE, PluginStepKind.STORE_UNREACHABLE -> {
                    null
                }

                PluginStepKind.ENABLE -> {
                    guarded { effects.enable(pluginId) }
                }

                PluginStepKind.INSTALL -> {
                    guarded { effects.install(pluginId, checkNotNull(step.targetVersion), step.targetIsLatest) }
                }

                PluginStepKind.CHANGE_VERSION -> {
                    guarded { effects.changeVersion(pluginId, checkNotNull(step.targetVersion)) }
                }
            }
        val failure = attempt?.exceptionOrNull()
        return when {
            step.kind == PluginStepKind.SATISFIED -> {
                PluginResult(step, PluginResultKind.ALREADY_SATISFIED, step.detail)
            }

            attempt == null -> {
                PluginResult(step, PluginResultKind.BLOCKED, step.detail)
            }

            failure == null -> {
                PluginResult(step, PluginResultKind.DONE, doneMessage(step))
            }

            else -> {
                PluginResult(step, PluginResultKind.FAILED, failure.message ?: "Failed without a reason.")
            }
        }
    }

    private fun applyRule(step: RuleStep): RuleResult {
        val kind =
            when (step.kind) {
                RuleStepKind.ALREADY_SET -> {
                    RuleResultKind.ALREADY_SET
                }

                RuleStepKind.KEPT_EXISTING -> {
                    RuleResultKind.KEPT_EXISTING
                }

                RuleStepKind.POLICY_UNREADABLE -> {
                    RuleResultKind.POLICY_UNREADABLE
                }

                RuleStepKind.ADD -> {
                    when (effects.addRule(step.rule)) {
                        RuleWrite.ADDED -> RuleResultKind.ADDED
                        RuleWrite.KEPT_EXISTING -> RuleResultKind.KEPT_EXISTING
                        RuleWrite.POLICY_UNREADABLE -> RuleResultKind.POLICY_UNREADABLE
                        RuleWrite.NOT_SAVED -> RuleResultKind.NOT_SAVED
                    }
                }
            }
        return RuleResult(step, kind)
    }

    private fun statusOf(
        plan: PackPlan,
        plugins: List<PluginResult>,
        rules: List<RuleResult>,
    ): PackApplyStatus {
        if (plan.satisfied && plan.requiredBlocked.isEmpty()) {
            return PackApplyStatus.ALREADY_SATISFIED
        }
        val changed = plugins.any { it.kind == PluginResultKind.DONE } || rules.any { it.kind == RuleResultKind.ADDED }
        val missed =
            plugins.any { !it.step.plugin.optional && it.kind.missed() } || rules.any { it.kind.missed() }
        return when {
            !missed -> PackApplyStatus.APPLIED
            changed -> PackApplyStatus.PARTIAL
            else -> PackApplyStatus.FAILED
        }
    }

    /**
     * A rule that did not land for a reason the operator did not choose. An existing operator rule
     * is kept by design, so [RuleResultKind.KEPT_EXISTING] is not a miss.
     */
    private fun RuleResultKind.missed(): Boolean {
        val missed = setOf(RuleResultKind.POLICY_UNREADABLE, RuleResultKind.NOT_SAVED)
        return this in missed
    }

    private fun PluginResultKind.missed(): Boolean {
        val missed = setOf(PluginResultKind.FAILED, PluginResultKind.BLOCKED)
        return this in missed
    }

    private fun doneMessage(step: PluginStep): String =
        when (step.kind) {
            PluginStepKind.ENABLE -> "Enabled."
            PluginStepKind.INSTALL -> "Installed ${step.targetVersion}."
            PluginStepKind.CHANGE_VERSION -> "Changed ${step.installedVersion} to ${step.targetVersion}."
            else -> step.detail
        }

    /** A thrown failure becomes a row result; cancellation still stops the whole apply. */
    @Suppress("TooGenericExceptionCaught") // One broken installer must not abort the other rows.
    private suspend fun guarded(block: suspend () -> Result<Unit>): Result<Unit> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}
