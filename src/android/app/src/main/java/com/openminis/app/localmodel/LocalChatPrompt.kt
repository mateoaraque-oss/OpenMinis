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
 *  - the engine runs with a small context (4-8k) and llama-server REJECTS the
 *    request outright when the prompt does not fit
 *    (`400 exceed_context_size_error`), so the chat died before producing a
 *    single token;
 *  - even when it fits, prefilling several thousand tokens on a phone CPU
 *    takes minutes, which reads as "it doesn't answer".
 *
 * On-device chats therefore use a compact identity prompt and no tool
 * schemas: the model just talks. Screen control and the other agent tools
 * stay available whenever a cloud model is selected.
 */
object LocalChatMode {

    /**
     * The system prompt handed to the phone model. Deliberately short (a few
     * hundred tokens), so it leaves the context window for the actual
     * conversation and keeps prefill under a second or two.
     */
    fun systemPrompt(context: Context): String {
        val soul = runCatching { SoulStore.load(context) }.getOrNull()
        val name = soul?.metadata?.name?.trim().takeUnless { it.isNullOrEmpty() } ?: "Orion"
        val style = soul?.metadata?.style?.trim().orEmpty()
        // Cap the personality body: it is decorative here, and every extra
        // character is prefill time on the phone's CPU.
        val body = soul?.body?.trim().orEmpty().take(700)
        val today = java.time.LocalDate.now().toString()

        return buildString {
            append("You are ").append(name)
                .append(", a friendly AI assistant running entirely on this Android phone, offline. ")
            append("Answer what the user asks in their own language, directly and concisely. ")
            append("You cannot browse the web or run tools in this mode; if something needs the ")
            append("internet or the phone's apps, say so plainly and offer what you can do. ")
            append("Never invent file paths, commands or facts. Today is ").append(today).append('.')
            if (style.isNotEmpty()) {
                append("\n\nResponse style: ").append(style)
            }
            if (body.isNotEmpty()) {
                append("\n\nPersonality: ").append(body)
            }
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
     */
    fun trimToFit(
        messages: List<LLMMessage>,
        contextWindow: Int?,
        reserveOutputTokens: Int = 1024,
    ): List<LLMMessage> {
        val window = contextWindow?.takeIf { it > 0 } ?: 8192
        // ~4 characters per token is close enough for a budget; the engine
        // gets some slack from `reserveOutputTokens`.
        val budgetChars = ((window - reserveOutputTokens - 256).coerceAtLeast(512)) * 4
        var used = 0
        val kept = ArrayDeque<LLMMessage>()
        for (message in messages.asReversed()) {
            val cost = approximateChars(message)
            if (kept.isNotEmpty() && used + cost > budgetChars) break
            kept.addFirst(message)
            used += cost
        }
        // A tool result whose call was trimmed off is invalid for the chat
        // template, and the phone engine has no tools anyway.
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
