package com.openminis.app.speech

/**
 * [T-orion-speak-answer-only] Turns an assistant reply into something worth
 * hearing.
 *
 * A reply is written for the screen: fenced code blocks, inline code, tables,
 * bullet markers, links and headings all read as noise when spoken, and a task
 * that touched a file can easily produce two screens of shell output before the
 * one sentence that matters. The wake-word flow used to speak the model's raw
 * text, which is why Orion announced the code it had just used.
 *
 * The rule here is deliberately conservative: keep the prose, drop everything
 * that exists only to be looked at.
 */
object VoiceReplyFormatter {

    /** Longest reply spoken verbatim; the tail is cut at a sentence boundary. */
    const val MAX_CHARS = 1_200

    private val fencedCode = Regex("(?s)```.*?```")
    private val inlineCode = Regex("`([^`\\n]*)`")
    private val linkedLabel = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
    private val bareUrl = Regex("(?i)\\b(?:https?://|www\\.)\\S+")
    private val tableRow = Regex("(?m)^\\s*\\|.*\\|\\s*$")
    private val heading = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
    private val quote = Regex("(?m)^\\s*>\\s?")
    private val bullet = Regex("(?m)^\\s*(?:[-*+•]|\\d+[.)])\\s+")
    private val thinkingLine = Regex("(?im)^\\s*(?:thinking|pensando|reasoning|razonando)\\b.*$")
    private val emphasis = Regex("\\*\\*|__|~~")
    private val horizontalRule = Regex("(?m)^\\s*(?:---+|___+|\\*\\*\\*+)\\s*$")
    private val spaces = Regex("[ \\t]+")
    private val blankRuns = Regex("\\n{2,}")
    private val sentenceBreaks = charArrayOf('.', '!', '?', '\n', ';', ':')

    /**
     * @return the parts of [raw] that make sense out loud, or "" when nothing
     *   does (a reply that was only code, for example).
     */
    fun forSpeech(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var text = raw
        text = fencedCode.replace(text, " ")
        text = tableRow.replace(text, "")
        text = horizontalRule.replace(text, "")
        text = thinkingLine.replace(text, "")
        text = linkedLabel.replace(text, "\$1")
        // Inline code is often a path or an identifier; keeping only its prose
        // shell reads better than spelling symbols out loud.
        text = inlineCode.replace(text, "\$1")
        text = bareUrl.replace(text, "")
        text = heading.replace(text, "")
        text = quote.replace(text, "")
        text = bullet.replace(text, "")
        text = emphasis.replace(text, "")
        text = text.replace("*", "").replace("#", "")
        text = spaces.replace(text, " ")
        text = blankRuns.replace(text, "\n")
        text = text.trim()
        if (text.length > MAX_CHARS) {
            val cut = text.lastIndexOfAny(sentenceBreaks, MAX_CHARS)
            text = text.substring(0, if (cut > MAX_CHARS / 2) cut + 1 else MAX_CHARS).trim()
        }
        return text
    }
}
