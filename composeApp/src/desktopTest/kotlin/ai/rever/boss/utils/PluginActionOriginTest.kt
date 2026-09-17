package ai.rever.boss.utils

import ai.rever.boss.components.plugin.registries.DeepLinkActionRegistryImpl
import ai.rever.boss.plugin.api.DeepLinkActionHandler
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `boss://` is registered with the OS, so a `boss://plugin?id=…&action=…` link is
 * not evidence the operator asked for anything: any web page or local program can
 * produce the same input. These tests pin the gate that decides whether such a
 * link reaches a plugin's [DeepLinkActionHandler] unattended.
 */
class PluginActionOriginTest {
    /** Lets anything already queued on the EDT finish before the assertion reads the counter. */
    private fun drainUiThread() {
        SwingUtilities.invokeAndWait { }
    }

    private fun registerCounting(
        handlerId: String,
        calls: AtomicInteger,
    ) {
        DeepLinkActionRegistryImpl.register(
            object : DeepLinkActionHandler {
                override val handlerId = handlerId

                override fun handle(
                    action: String,
                    params: Map<String, String>,
                ): Boolean {
                    calls.incrementAndGet()
                    return action == "ping"
                }
            },
        )
    }

    @Test
    fun `an external action link never reaches the handler unattended`() {
        val id = "external-action-test-${System.nanoTime()}"
        val calls = AtomicInteger()
        registerCounting(id, calls)
        try {
            val verdict = DeepLinkHandler.processDeepLink("boss://plugin?id=$id&action=ping", DeepLinkOrigin.EXTERNAL)

            // No window is registered in a test JVM, so there is nowhere to ask
            // and the action is refused outright rather than run unattended --
            // the same early return `boss://terminal` takes when no window can
            // show the command. With a window it is held instead, and the verdict
            // is null because nothing has run yet to have an outcome.
            assertFalse(
                runBlocking { requireNotNull(verdict).await() },
                "with no window to confirm in, an externally delivered action must be refused",
            )
            drainUiThread()
            assertEquals(0, calls.get(), "the handler must not run for an externally delivered link")
        } finally {
            DeepLinkActionRegistryImpl.unregister(id)
        }
    }

    @Test
    fun `an operator initiated action link still dispatches and reports its verdict`() {
        val id = "operator-action-test-${System.nanoTime()}"
        val calls = AtomicInteger()
        registerCounting(id, calls)
        try {
            val handled =
                runBlocking {
                    requireNotNull(
                        DeepLinkHandler.processDeepLink(
                            "boss://plugin?id=$id&action=ping",
                            DeepLinkOrigin.OPERATOR_CLI,
                        ),
                    ).await()
                }
            assertTrue(handled, "the operator's own invocation must still run the action and report the outcome")
            assertEquals(1, calls.get())

            val declined =
                runBlocking {
                    requireNotNull(
                        DeepLinkHandler.processDeepLink(
                            "boss://plugin?id=$id&action=unknown",
                            DeepLinkOrigin.OPERATOR_CLI,
                        ),
                    ).await()
                }
            assertFalse(declined, "a declining handler is still reported honestly for the operator's own link")
        } finally {
            DeepLinkActionRegistryImpl.unregister(id)
        }
    }

    @Test
    fun `a malformed action is refused for every origin, not held for confirmation`() {
        val id = "malformed-action-test-${System.nanoTime()}"
        val calls = AtomicInteger()
        registerCounting(id, calls)
        try {
            for (origin in DeepLinkOrigin.entries) {
                val verdict = DeepLinkHandler.processDeepLink("boss://plugin?id=$id&action=ping%0Arm%20-rf", origin)
                assertFalse(
                    runBlocking { requireNotNull(verdict).await() },
                    "an action carrying a control character cannot be shown in full, so it is refused outright",
                )
            }
            drainUiThread()
            assertEquals(0, calls.get())
        } finally {
            DeepLinkActionRegistryImpl.unregister(id)
        }
    }
}
