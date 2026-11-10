package com.github.vladimirvaca.cliagentdock.terminal

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.terminal.JBTerminalWidget
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.util.concurrency.AppExecutorUtil
import com.jediterm.terminal.TtyConnector
import java.util.concurrent.CompletableFuture

/**
 * Types into the agent's stdin **without submitting** and without touching the PTY on the
 * calling thread (callers are on the EDT). Writes reach the agent in the order they were
 * sent. See AGENTS.md §4.2.
 *
 * Two routes, both asynchronous:
 *  - **JediTerm widgets** go through the terminal panel's `TerminalOutputStream`, which is
 *    the widget's `TerminalStarter`: its `sendBytes`/`sendString` only enqueue onto
 *    JediTerm's own single-threaded writer, the same queue the user's keystrokes take. So
 *    our input is also ordered relative to what the user types.
 *  - **Any other widget** (or a JediTerm one whose session has not started yet) falls back
 *    to the `TtyConnectorAccessor`. Its `executeWithTtyConnector` is a bare
 *    `CompletableFuture.thenAccept`, which runs the write synchronously on the caller's
 *    thread once the connector exists. Each write is therefore chained onto the previous
 *    one and run on the application pool, which keeps strict ordering (even for writes
 *    queued before the connector appears) and keeps the PTY write off the EDT.
 *
 * Call from one thread (the EDT); the fallback chain is not synchronized.
 */
internal class AgentInputWriter(private val widget: TerminalWidget) {

    private val jediPanel = JBTerminalWidget.asJediTermWidget(widget)?.terminalPanel

    /** Tail of the fallback chain; every write runs after the one before it. */
    private var tail: CompletableFuture<TtyConnector>? = null

    fun send(text: String) {
        val out = jediPanel?.terminalOutputStream
        if (out != null) out.sendString(text, true) else chain { it.write(text) }
    }

    fun send(bytes: ByteArray) {
        val out = jediPanel?.terminalOutputStream
        if (out != null) out.sendBytes(bytes, true) else chain { it.write(bytes) }
    }

    private fun chain(write: (TtyConnector) -> Unit) {
        val previous = tail ?: CompletableFuture<TtyConnector>().also { connector ->
            widget.ttyConnectorAccessor.executeWithTtyConnector { connector.complete(it) }
        }
        tail = previous.thenApplyAsync({ connector ->
            try {
                write(connector)
            } catch (e: Exception) {
                // Keep the chain alive: one failed write must not swallow every later one.
                thisLogger().warn("Failed to write to the agent terminal", e)
            }
            connector
        }, AppExecutorUtil.getAppExecutorService())
    }
}
