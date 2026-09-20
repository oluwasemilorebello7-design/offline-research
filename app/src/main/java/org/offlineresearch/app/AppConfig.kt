package org.offlineresearch.app

object AppConfig {
    const val PACKAGE = "org.offlineresearch.app"

    /** Context window. 4096 keeps the KV cache tiny (<1 GB even for 30B MoE) so RAM goes to weights/page cache. */
    const val N_CTX = 4096

    /** Big cores only. On most flagships that is 4 (1 prime + 3 performance). More threads often make it slower. */
    const val THREADS = 4

    const val MAX_ANSWER_TOKENS = 700
    const val MAX_PLAN_TOKENS = 48

    /** How many characters of retrieved text we stuff into the prompt (~3.5 chars/token -> ~2k tokens). */
    const val SOURCE_CHAR_BUDGET = 7000
    const val CHUNKS_PER_QUERY = 6
    const val MAX_CHUNKS_PER_ARTICLE = 2
    const val MAX_SOURCES = 6

    const val MODELS_DIR = "models"
    const val KB_DIR = "kb"
    const val KB_FILE = "knowledge.sqlite"
}
