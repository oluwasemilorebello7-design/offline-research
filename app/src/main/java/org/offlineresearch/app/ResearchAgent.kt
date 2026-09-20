package org.offlineresearch.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

sealed interface AgentEvent {
    data class Status(val text: String) : AgentEvent
    data class Sources(val hits: List<Hit>) : AgentEvent
    data class Token(val text: String) : AgentEvent
    data class Done(val stats: LlamaEngine.Stats?, val totalMs: Long) : AgentEvent
}

/**
 * The whole "research" pipeline, fully on-device:
 *   1. PLAN     - the LLM rewrites the question into 1-3 keyword searches (one per entity for comparisons)
 *   2. RETRIEVE - BM25 over the offline knowledge base per query, fused with reciprocal-rank fusion
 *   3. ANSWER   - the LLM writes a cited answer grounded in the retrieved passages
 * This is what lets a small/sparse model answer comparison, synthesis and multi-hop questions
 * that it could never answer from its weights alone.
 */
class ResearchAgent(private val engine: LlamaEngine, private val kb: KnowledgeBase?) {

    fun ask(question: String): Flow<AgentEvent> = channelFlow {
        val t0 = System.currentTimeMillis()
        var hits = emptyList<Hit>()

        if (kb != null) {
            send(AgentEvent.Status("Planning searches…"))
            val queries = planQueries(question)
            send(AgentEvent.Status("Searching: " + queries.joinToString(" | ")))
            hits = retrieve(question, queries, kb)
            send(AgentEvent.Sources(hits))
        }

        send(AgentEvent.Status(if (hits.isEmpty()) "Answering from model knowledge…" else "Reading ${hits.size} sources…"))
        val prompt = engine.formatChat(buildMessages(question, hits))
        val stats = try {
            engine.generate(prompt, AppConfig.MAX_ANSWER_TOKENS, 0.3f) { bytes ->
                trySend(AgentEvent.Token(String(bytes, Charsets.UTF_8)))
                isActive
            }
        } catch (e: Exception) {
            send(AgentEvent.Token("\n\n[error: ${e.message}]"))
            null
        }
        send(AgentEvent.Done(stats, System.currentTimeMillis() - t0))
    }.flowOn(Dispatchers.Default)

    // ---- 1. plan -------------------------------------------------------------------------------

    private fun planQueries(question: String): List<String> {
        val prompt = engine.formatChat(
            listOf("system" to PLAN_SYSTEM, "user" to question)
        )
        val sb = StringBuilder()
        engine.generate(prompt, AppConfig.MAX_PLAN_TOKENS, 0f) { sb.append(String(it, Charsets.UTF_8)); true }
        val planned = sb.lines()
            .map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "") }
            .filter { it.length in 2..120 }
            .take(3)
        return (planned + question).distinct()   // the raw question is always a fallback query
    }

    // ---- 2. retrieve ---------------------------------------------------------------------------

    private fun retrieve(question: String, queries: List<String>, kb: KnowledgeBase): List<Hit> {
        val fused = HashMap<Long, Double>()
        val byId = HashMap<Long, Hit>()
        for (q in queries) {
            kb.search(q, AppConfig.CHUNKS_PER_QUERY).forEachIndexed { rank, hit ->
                byId[hit.id] = hit
                fused[hit.id] = (fused[hit.id] ?: 0.0) + 1.0 / (60 + rank)   // reciprocal-rank fusion
            }
        }
        val perTitle = HashMap<String, Int>()
        val picked = ArrayList<Hit>()
        var chars = 0
        for ((id, _) in fused.entries.sortedByDescending { it.value }) {
            val h = byId.getValue(id)
            val n = perTitle.getOrDefault(h.title, 0)
            if (n >= AppConfig.MAX_CHUNKS_PER_ARTICLE) continue
            if (chars + h.text.length > AppConfig.SOURCE_CHAR_BUDGET) continue
            perTitle[h.title] = n + 1
            picked += h
            chars += h.text.length
            if (picked.size >= AppConfig.MAX_SOURCES) break
        }
        return picked
    }

    // ---- 3. answer -----------------------------------------------------------------------------

    private fun buildMessages(question: String, hits: List<Hit>): List<Pair<String, String>> {
        val user = buildString {
            if (hits.isNotEmpty()) {
                append("SOURCES\n")
                hits.forEachIndexed { i, h -> append("[${i + 1}] ${h.title}\n${h.text}\n\n") }
            } else {
                append("(No sources available.)\n\n")
            }
            append("QUESTION\n").append(question)
        }
        return listOf("system" to ANSWER_SYSTEM, "user" to user)
    }

    companion object {
        private const val PLAN_SYSTEM =
            "You turn a question into search queries for an offline encyclopedia. " +
            "Output 1 to 3 short keyword queries, one per line, no numbering, no commentary. " +
            "For comparisons use one query per thing being compared. For multi-step questions " +
            "query each fact that must be looked up."

        private const val ANSWER_SYSTEM =
            "You are an offline research assistant on a phone with no internet. " +
            "Answer clearly and concisely. Prefer facts from the numbered SOURCES and cite them inline like [1] or [2][3]. " +
            "If the sources do not cover something, say so, then give your best answer from general knowledge marked (unverified). " +
            "For comparisons, cover each side and then state the key differences. " +
            "For calculations or multi-step questions, show the steps briefly."
    }
}
