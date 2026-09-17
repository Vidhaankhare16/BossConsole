package ai.rever.boss.components.events

import ai.rever.boss.ipc.IpcEventBridge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A `boss://plugin?id=…&action=…` request that arrived from outside the
 * operator's own `boss` invocation and is waiting to be shown to them.
 *
 * @property handlerId the plugin deep-link handler the action is addressed to
 * @property action the action name the handler would be asked to run
 * @property params the parameters the handler would receive. Carried verbatim so
 *   a confirmed action runs exactly what was asked; only the KEYS are ever
 *   displayed, because a value is attacker-chosen text and a prompt is not a
 *   place to render it.
 * @property sourceWindowId the window that will show the prompt (required for
 *   multi-window support)
 */
data class PluginActionConfirmEvent(
    val handlerId: String,
    val action: String,
    val params: Map<String, String>,
    val sourceWindowId: String,
)

/**
 * Event bus carrying plugin action links that need the operator's say-so.
 *
 * `boss://` is registered with the OS, so an action link is not evidence the
 * operator asked for anything. `DeepLinkHandler` decides which links need
 * confirming and emits them here; the matching window's BossApp queues the
 * request and dispatches it only if the operator agrees.
 */
object PluginActionEventBus {
    /** Optional IPC bridge for forwarding events cross-process in kernel mode. */
    @Volatile var ipcBridge: IpcEventBridge? = null

    private val _confirmEvents =
        MutableSharedFlow<PluginActionConfirmEvent>(
            // No replay: a held action must not be re-offered to every window
            // opened afterwards, which would prompt for the same request twice.
            replay = 0,
            extraBufferCapacity = 10,
        )
    val confirmEvents: SharedFlow<PluginActionConfirmEvent> = _confirmEvents.asSharedFlow()

    /** Emit an action link for the operator of [sourceWindowId] to confirm or dismiss. */
    suspend fun requestConfirmation(
        handlerId: String,
        action: String,
        params: Map<String, String>,
        sourceWindowId: String,
    ) {
        val event = PluginActionConfirmEvent(handlerId, action, params, sourceWindowId)
        _confirmEvents.emit(event)
        ipcBridge?.forward("PluginActionConfirmEvent", event, sourceWindowId)
    }
}
