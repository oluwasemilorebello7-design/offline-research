package org.offlineresearch.app

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File

data class Hit(val id: Long, val title: String, val text: String, val score: Double)

/**
 * Offline encyclopedia: a single SQLite file with an FTS5 (BM25) index over text chunks.
 * Built on a PC by scripts/build_index.py. Opened read-only in spirit (PRAGMA query_only).
 */
class KnowledgeBase(dbFile: File) : AutoCloseable {

    private val conn: SQLiteConnection = BundledSQLiteDriver().open(dbFile.absolutePath).also {
        it.execSQL("PRAGMA query_only = ON")
        it.execSQL("PRAGMA mmap_size = 268435456")   // let the OS page cache do the work
        it.execSQL("PRAGMA cache_size = -32768")
    }

    @Synchronized
    fun meta(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        conn.prepare("SELECT key, value FROM meta ORDER BY key").use { st ->
            while (st.step()) out[st.getText(0)] = st.getText(1)
        }
        return out
    }

    /** Keyword search. Tries all terms (AND) first, then widens to OR so multi-concept queries still hit. */
    @Synchronized
    fun search(query: String, limit: Int): List<Hit> {
        val terms = keywords(query)
        if (terms.isEmpty()) return emptyList()
        val q = { op: String -> terms.joinToString(" $op ") { "\"$it\"" } }
        var hits = run(q("AND"), limit)
        if (hits.size < limit && terms.size > 1) {
            hits = (hits + run(q("OR"), limit)).distinctBy { it.id }.take(limit)
        }
        return hits
    }

    private fun run(match: String, limit: Int): List<Hit> =
        conn.prepare(SQL).use { st ->
            st.bindText(1, match)
            st.bindLong(2, limit.toLong())
            val out = ArrayList<Hit>()
            while (st.step()) out += Hit(st.getLong(0), st.getText(1), st.getText(2), st.getDouble(3))
            out
        }

    override fun close() = conn.close()

    companion object {
        // bm25() is negative-better; column weights: title 5x, text 1x.
        private const val SQL = """
            SELECT c.id, c.title, c.text, bm25(chunks_fts, 5.0, 1.0) AS score
            FROM chunks_fts JOIN chunks c ON c.id = chunks_fts.rowid
            WHERE chunks_fts MATCH ?1
            ORDER BY score LIMIT ?2
        """

        private val SPLIT = Regex("[^\\p{L}\\p{N}]+")
        private val STOP = setOf(
            "a", "an", "the", "of", "in", "on", "at", "to", "for", "and", "or", "is", "are", "was", "were", "be",
            "it", "its", "this", "that", "with", "as", "by", "from", "what", "which", "who", "how", "why", "when",
            "where", "do", "does", "did", "can", "could", "would", "should", "about", "between", "vs", "versus",
            "difference", "explain", "me", "my", "i", "you", "your", "there", "their", "than", "then", "into",
        )

        /** Lowercased, stopword-free, alphanumeric-only terms (safe to quote in an FTS5 MATCH). Max 8. */
        fun keywords(q: String): List<String> =
            q.lowercase().split(SPLIT).filter { it.length > 1 && it !in STOP }.distinct().take(8)
    }
}
