package com.openminis.app.localmodel

import android.content.Context
import com.openminis.app.agent.SoulStore
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-orion-local-chat] Prompt shaping for the on-device engine.
 *
 * The normal agent prompt is built for a cloud model: it documents the agent
 * tools, the Linux sandbox, memory files and the app's URL scheme, and it
 * lands around 6-8k tokens before the user has typed anything. A 1-3B GGUF on
 * a phone cannot pay that:
 *
 *  - the engine runs with a small context (8k) and llama-server REJECTS the
 *    request outright when the prompt does not fit
 *    (`400 exceed_context_size_error`), so the chat died before producing a
 *    single token;
 *  - even when it fits, prefilling several thousand tokens on a phone CPU
 *    takes minutes, which reads as "it doesn't answer".
 *
 * On-device chats therefore use a compact identity prompt plus a small,
 * terse subset of the real agent tools ([com.openminis.app.tools.AgentTools.makeLocalAgentTools]).
 * That subset is enough to do everything the phone can do offline �? screen
 * control through `android-a11y-cli`, links through `android-open`, files and
 * searches �? while staying around a thousand tokens.
 *
 * Tool schemas are only handed to models whose GGUF chat template actually
 * supports tool calls; llama.cpp refuses the request otherwise. See
 * [GgufTools].
 */
object LocalChatMode {

    /** Rough characters per token for budget maths; measured 4.8 on Qwen. */
    private const val CHARS_PER_TOKEN = 4

    /**
     * [T-orion-local-tools] True when the model Orion would run declares
     * tool-calling support in its chat template. Unknown files answer false:
     * a text-only chat is far better than a request the engine rejects.
     */
    fun supportsTools(context: Context): Boolean {
        val file = runCatching { LlamaServer.activeModelFile(context) }.getOrNull() ?: return false
        return GgufTools.chatTemplateSupportsTools(file) == true
    }

    /**
     * The system prompt handed to the phone model. Deliberately short, so it
     * leaves the context window for the actual conversation and keeps prefill
     * in seconds.
     *
     * With [toolsEnabled] the model is told it can act on the phone and given
     * the handful of shell commands that matter; without it, it is told
     * plainly that it can only talk.
     */
    fun systemPrompt(context: Context, toolsEnabled: Boolean): String {
        val soul = runCatching { SoulStore.load(context) }.getOrNull()
        val name = soul?.metadata?.name?.trim().takeUnless { it.isNullOrEmpty() } ?: "Orion"
        val style = soul?.metadata?.style?.trim().orEmpty()
        // Cap the personality body: it is decorative here, and every extra
        // character is prefill time on the phone's CPU.
        val body = soul?.body?.trim().orEmpty().take(700)
        val today = java.time.LocalDate.now().toString()

        return buildString {
            append("You are ").append(name)
            append(", an AI assistant running entirely on this Android phone, not on a server. ")
            if (toolsEnabled) {
                append("You have tools and you act with them: never say you cannot do something a tool allows, ")
                append("and never claim you did something you did not call a tool for. ")
                append("Rules: call at most one tool per turn, then wait for its result. ")
                append("Do not invent tool output, file paths or commands. ")
                append("Answer briefly, in the user's language.\n\n")
                append("Phone control, run with shell_execute:\n")
                append("  android-a11y-cli ui dump --compact   read the current screen\n")
                append("  android-a11y-cli tap text \"Send\"     tap a visible label (or: tap xy X Y)\n")
                append("  android-a11y-cli input text \"hola\"   type into the focused field\n")
                append("  android-a11y-cli input key HOME      HOME, BACK or RECENTS\n")
                append("  android-app launch \"WhatsApp\"        open an app by name\n")
                append("  android-app list --query \"chat\"      find an app's package\n")
                append("  android-open \"https://...\"           open a link in the browser\n")
                append("  <command> --help                     usage of any android-* command\n")
                append("Write the FULL command every time, starting with the command name; never send just the last word. ")
                append("To open an app the command is exactly: android-app launch \"WhatsApp\". ")
                append("Run it by calling the shell_execute tool; never answer with a command as text. ")
                append("Before acting on the screen, dump it. ")
                append("Put anything you create in /var/minis/workspace. ")
                append("If a tool fails for lack of network, say so plainly. ")
            } else {
                append("Answer what the user asks in their own language, directly and concisely. ")
                append("You cannot run tools in this mode; if something needs the ")
                append("internet or the phone's apps, say so plainly and offer what you can do. ")
                append("Never invent file paths, commands or facts. ")
            }
            append("Today is ").append(today).append('.')
            if (style.isNotEmpty()) {
                append("\n\nResponse style: ").append(style)
            }
            if (body.isNotEmpty()) {
                append("\n\nPersonality: ").append(body)
            }
            // [T-orion-local-language] Last, so it survives the tool round: the
            // small models tested (Qwen3 4B, LFM 1.7B) answer in English after
            // a tool returns English JSON, even though the rule above already
            // asked for the user's language. Restating it at the end of the
            // prompt keeps the reply in Spanish for a Spanish message.
            append("\n\nLanguage rule, always: write the entire reply in the same language as the user's ")
            append("most recent message. Tool output in English does not change the reply language. ")
            append("If the user wrote in Spanish, the reply must be in Spanish.")
        }
    }

    /**
     * [T-orion-local-chat] Keep only the newest history that still fits the
     * engine's window.
     *
     * llama-server does not trim an oversized request: it answers
     * `400 exceed_context_size_error`. The app's own context policy only
     * starts compacting above 32k windows, so without this a local chat died
     * after a handful of exchanges. Trimming here costs one pass over the
     * history and leaves the persisted messages untouched.
     *
     * @param contextWindow the engine's `-c` value, as advertised by the
     *   model entry.
     * @param promptChars the system prompt plus tool schemas for this request.
     *   They travel outside [messages], so they must come out of the window
     *   before the history gets a budget.
     */
    fun trimToFit(
        messages: List<LLMMessage>,
        contextWindow: Int?,
        reserveOutputTokens: Int = 1024,
        promptChars: Int = 0,
    ): List<LLMMessage> {
        val window = contextWindow?.takeIf { it > 0 } ?: 8192
        val promptTokens = (promptChars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
        // Leave a little room for role markers the chat template adds.
        val availableHistoryTokens = (window - reserveOutputTokens - promptTokens - 128)
            .coerceAtLeast(512)
        val budgetChars = availableHistoryTokens * CHARS_PER_TOKEN
        // The loop always keeps the newest message, even when it alone is
        // bigger than the whole window (an accessibility dump of a busy
        // screen easily is). Clamp any oversized message here so one tool
        // result cannot push the request over the engine's limit.
        val maxMessageChars = (budgetChars - 1024).coerceAtLeast(2048)
        var used = 0
        val kept = ArrayDeque<LLMMessage>()
        for (message in messages.asReversed()) {
            val clamped = clampMessage(message, maxMessageChars)
            val cost = approximateChars(clamped)
            if (kept.isNotEmpty() && used + cost > budgetChars) break
            kept.addFirst(clamped)
            used += cost
        }
        // A tool result whose call was trimmed off is invalid for the chat
        // template, so never let the window start with one.
        while (kept.isNotEmpty()) {
            val head = kept.first()
            val onlyToolResults = head.role == LLMMessage.Role.USER &&
                head.contentParts.isNotEmpty() &&
                head.contentParts.all { it is AgentContentPart.ToolResult }
            if (!onlyToolResults) break
            kept.removeFirst()
        }
        return kept.toList()
    }

    private fun clampMessage(message: LLMMessage, maxChars: Int): LLMMessage {
        val total = approximateChars(message)
        if (total <= maxChars) return message
        // Several tool results can arrive in one message (the loop runs calls
        // concurrently), so split the budget across every text holder instead
        // of giving each one the full clamp.
        data class Slot(val index: Int, val size: Int)
        val slots = mutableListOf<Slot>()
        var overhead = 32
        message.contentParts.forEachIndexed { index, part ->
            when (part) {
                is AgentContentPart.Text -> slots.add(Slot(index, part.text.length))
                is AgentContentPart.ToolResult -> slots.add(Slot(index, part.content.length))
                is AgentContentPart.ToolUse -> overhead += part.input.toString().length
                else -> Unit
            }
        }
        val available = (maxChars - overhead).coerceAtLeast(1024)
        val contentSize = message.content.length
        val textTotal = (slots.sumOf { it.size } + contentSize).coerceAtLeast(1)
        val limits = HashMap<Int, Int>()
        for (slot in slots) {
            val share = (available.toLong() * slot.size / textTotal).toInt()
            limits[slot.index] = if (slot.size <= 256) slot.size else share.coerceIn(256, slot.size)
        }
        val parts = message.contentParts.mapIndexed { index, part ->
            val limit = limits[index] ?: return@mapIndexed part
            when (part) {
                is AgentContentPart.ToolResult ->
                    if (part.content.length > limit) part.copy(content = headTail(part.content, limit)) else part
                is AgentContentPart.Text ->
                    if (part.text.length > limit) part.copy(text = headTail(part.text, limit)) else part
                else -> part
            }
        }
        val contentLimit = (available.toLong() * contentSize / textTotal).toInt()
        val content = if (contentSize > contentLimit) {
            headTail(message.content, contentLimit)
        } else {
            message.content
        }
        return message.copy(content = content, contentParts = parts)
    }

    /** Keep the beginning and the end: errors and the latest lines live there. */
    private fun headTail(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val head = limit * 3 / 4
        val tail = (limit - head - 80).coerceAtLeast(0)
        val removed = text.length - head - tail
        return text.take(head) +
            "\n[... $removed characters trimmed to fit the phone's context ...]\n" +
            text.takeLast(tail)
    }

    private fun approximateChars(message: LLMMessage): Int {
        var n = message.content.length + 32
        for (part in message.contentParts) {
            n += when (part) {
                is AgentContentPart.Text -> part.text.length
                is AgentContentPart.ToolResult -> part.content.length
                is AgentContentPart.ToolUse -> part.input.toString().length
                else -> 0
            }
        }
        return n
    }
}
