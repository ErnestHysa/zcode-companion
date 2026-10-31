package com.zcode.android.features.conversation

/**
 * G10 (vc51) — the draft screen's suggested prompts (featureSuggestedPrompts.ts
 * + featureSuggestedPromptRotation.ts): an empty conversation offers a rotating
 * window of prompt chips; tapping one prefills the composer with the full
 * prompt body. The desktop's pool is mode-split (office | coding) and large;
 * the phone ships a curated subset of the SAME items (labels + prompts verbatim,
 * INV-15) and the same rotation contract: a per-pane cursor window, COUNT chips
 * per pane, the cursor advancing per pane open (rotation), no duplicates within
 * a pane.
 */
data class SuggestedPrompt(
    val id: String,
    val label: String,
    val prompt: String,
    /** office | coding — the desktop's mode split (the pool shown follows it). */
    val mode: String,
)

object SuggestedPrompts {

    /** COUNT chips per pane (the desktop's default pane size). */
    const val PANE_COUNT = 3

    val POOL: List<SuggestedPrompt> = listOf(
        SuggestedPrompt(
            id = "feature-recvvsPdvcWQzF",
            mode = "office",
            label = "Show what is using up space on my computer",
            prompt = "Analyze disk usage on this computer. Identify the largest directories and files, " +
                "distinguish system files, application data, and personal files, and estimate what I could " +
                "safely clean up. Do not delete any files.",
        ),
        SuggestedPrompt(
            id = "feature-office-browser-business-reading",
            mode = "office",
            label = "Find three business stories worth reading today",
            prompt = "Browse accessible public business news sites, open the articles, and pick the three " +
                "stories most worth reading today. For each: the core information, why it matters, the " +
                "publish time, and the link. Skip duplicates, paywalled, and login-required pages.",
        ),
        SuggestedPrompt(
            id = "feature-office-inbox-triage",
            mode = "office",
            label = "Summarize my notes into a meeting brief",
            prompt = "Read the notes I paste next and turn them into a one-page meeting brief: context, " +
                "decisions to make, open questions, and a suggested agenda. Ask me for the notes first.",
        ),
        SuggestedPrompt(
            id = "feature-office-doc-polish",
            mode = "office",
            label = "Polish this document for clarity",
            prompt = "Review the document I paste next for clarity and structure. Rewrite the weak " +
                "paragraphs, keep my voice, and list every change you made with a one-line reason.",
        ),
        SuggestedPrompt(
            id = "feature-coding-test-gap",
            mode = "coding",
            label = "Find the gaps in my test suite",
            prompt = "Analyze this project's test coverage. List the highest-risk untested paths, propose " +
                "the specific tests you would add first, and explain the failure each one would catch.",
        ),
        SuggestedPrompt(
            id = "feature-coding-explain-traceback",
            mode = "coding",
            label = "Explain this traceback and fix it",
            prompt = "I will paste a traceback next. Explain the root cause in plain language, point at the " +
                "exact offending code, and propose the minimal fix before applying it.",
        ),
        SuggestedPrompt(
            id = "feature-coding-refactor-plan",
            mode = "coding",
            label = "Plan a refactor without breaking behavior",
            prompt = "Propose a step-by-step refactor plan for the module I point you at. Each step must " +
                "leave the tests green, list the files touched, and name the risk it retires.",
        ),
        SuggestedPrompt(
            id = "feature-coding-deps-audit",
            mode = "coding",
            label = "Audit my dependencies for risks",
            prompt = "Audit this project's dependencies: flag anything abandoned, duplicated, or pinned to " +
                "a risky version, and propose the safe upgrade order with the breaking changes to expect.",
        ),
    )

    fun poolFor(mode: String?): List<SuggestedPrompt> =
        if (mode == "office" || mode == "coding") POOL.filter { it.mode == mode } else POOL

    /**
     * The rotation window: COUNT items starting at [cursor] (wrapping), excluding
     * the ids other panes already show. A short pool repeats rather than starving.
     */
    fun selectForPane(pool: List<SuggestedPrompt>, cursor: Int, usedElsewhere: Set<String> = emptySet()): List<SuggestedPrompt> {
        if (pool.isEmpty()) return emptyList()
        val candidates = pool.filter { it.id !in usedElsewhere }.ifEmpty { pool }
        return (0 until candidates.size).map { candidates[(cursor + it) % candidates.size] }.take(PANE_COUNT)
    }
}
