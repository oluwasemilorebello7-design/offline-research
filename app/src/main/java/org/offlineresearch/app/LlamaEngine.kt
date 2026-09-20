package org.offlineresearch.app

import java.io.File

/** Thin, thread-safe wrapper over the llama.cpp JNI bridge. Generation calls are serialized. */
class LlamaEngine : AutoCloseable {

    fun interface TokenSink {
        /** Receives a chunk of complete UTF-8 bytes. Return false to stop generation. */
        fun onToken(bytes: ByteArray): Boolean
    }

    data class Stats(
        val promptTokens: Int,
        val generatedTokens: Int,
        val prefillMs: Long,
        val decodeMs: Long,
    ) {
        val prefillTps: Float get() = if (prefillMs > 0) promptTokens * 1000f / prefillMs else 0f
        val decodeTps: Float get() = if (decodeMs > 0 && generatedTokens > 1) (generatedTokens - 1) * 1000f / decodeMs else 0f
    }

    private var handle = 0L
    private val lock = Any()

    val isLoaded: Boolean get() = handle != 0L

    fun load(model: File, nCtx: Int = AppConfig.N_CTX, nThreads: Int = AppConfig.THREADS): Boolean =
        synchronized(lock) {
            if (handle != 0L) { nativeFree(handle); handle = 0L }
            handle = nativeLoad(model.absolutePath, nCtx, nThreads, true)
            handle != 0L
        }

    fun formatChat(messages: List<Pair<String, String>>): String = synchronized(lock) {
        check(handle != 0L) { "model not loaded" }
        val roles = messages.map { it.first }.toTypedArray()
        val contents = messages.map { it.second.toByteArray(Charsets.UTF_8) }.toTypedArray()
        val out = nativeFormatChat(handle, roles, contents)
        if (out != null) String(out, Charsets.UTF_8) else chatMl(messages)
    }

    /** Blocking. Call from a background thread. temperature <= 0 means greedy. */
    fun generate(prompt: String, maxTokens: Int, temperature: Float, sink: TokenSink): Stats = synchronized(lock) {
        check(handle != 0L) { "model not loaded" }
        val t0 = System.nanoTime()
        var tFirst = 0L
        val timed = TokenSink { b ->
            if (tFirst == 0L) tFirst = System.nanoTime()
            sink.onToken(b)
        }
        val n = nativeGenerate(handle, prompt.toByteArray(Charsets.UTF_8), maxTokens, temperature, timed)
        val t1 = System.nanoTime()
        when (n) {
            -2 -> error("Prompt does not fit in the context window")
            -3, -4, -5 -> error("Native generation failed (code $n)")
        }
        val first = if (tFirst == 0L) t1 else tFirst
        Stats(nativePromptTokens(), n, (first - t0) / 1_000_000, (t1 - first) / 1_000_000)
    }

    /** Safe to call from any thread while generate() is running. */
    fun cancel() = nativeCancel()

    override fun close() = synchronized(lock) {
        if (handle != 0L) { nativeFree(handle); handle = 0L }
    }

    private fun chatMl(messages: List<Pair<String, String>>): String = buildString {
        for ((role, content) in messages) append("<|im_start|>").append(role).append('\n').append(content).append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, useMmap: Boolean): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeCancel()
    private external fun nativePromptTokens(): Int
    private external fun nativeFormatChat(handle: Long, roles: Array<String>, contents: Array<ByteArray>): ByteArray?
    private external fun nativeGenerate(handle: Long, prompt: ByteArray, maxNew: Int, temp: Float, sink: TokenSink): Int

    companion object {
        init { System.loadLibrary("offline_llm") }
    }
}
