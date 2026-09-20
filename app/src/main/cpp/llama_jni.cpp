// JNI bridge between Kotlin (LlamaEngine.kt) and llama.cpp.
//
// Written against the modern llama.cpp C API (llama_model_load_from_file, llama_init_from_model,
// llama_vocab_*, llama_memory_clear). If you pin an older/newer llama.cpp and the build fails, the
// fix is almost always a rename in this file only - keep the bridge this small on purpose.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"
#include "utf8_util.h"

#define TAG "OfflineLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model*       model = nullptr;
    llama_context*     ctx   = nullptr;
    const llama_vocab* vocab = nullptr;
    int                n_ctx = 0;
};

std::atomic<bool> g_cancel{false};
std::atomic<int>  g_last_prompt_tokens{0};
bool              g_backend_ready = false;

void log_cb(ggml_log_level level, const char* text, void*) {
    __android_log_print(level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, TAG, "%s", text);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_offlineresearch_app_LlamaEngine_nativeLoad(JNIEnv* env, jobject, jstring jpath,
                                                    jint n_ctx, jint n_threads, jboolean use_mmap) {
    if (!g_backend_ready) {
        llama_log_set(log_cb, nullptr);
        llama_backend_init();
        g_backend_ready = true;
    }
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.use_mmap     = use_mmap;   // weights are paged in from flash on demand -> huge MoE models fit
    mp.n_gpu_layers = 0;
    llama_model* model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) { LOGE("model load failed"); return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = n_ctx;
    cp.n_batch         = 512;
    cp.n_ubatch        = 512;
    cp.n_threads       = n_threads;
    cp.n_threads_batch = n_threads;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); LOGE("context init failed"); return 0; }

    auto* e  = new Engine();
    e->model = model;
    e->ctx   = ctx;
    e->vocab = llama_model_get_vocab(model);
    e->n_ctx = (int) llama_n_ctx(ctx);
    LOGI("loaded model, n_ctx=%d", e->n_ctx);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_org_offlineresearch_app_LlamaEngine_nativeFree(JNIEnv*, jobject, jlong h) {
    auto* e = reinterpret_cast<Engine*>(h);
    if (!e) return;
    if (e->ctx)   llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

JNIEXPORT void JNICALL
Java_org_offlineresearch_app_LlamaEngine_nativeCancel(JNIEnv*, jobject) { g_cancel = true; }

JNIEXPORT jint JNICALL
Java_org_offlineresearch_app_LlamaEngine_nativePromptTokens(JNIEnv*, jobject) { return g_last_prompt_tokens.load(); }

// Applies the chat template stored in the GGUF. Returns null if the model has none
// (Kotlin then falls back to ChatML). Contents are byte[] to stay safe for emoji (4-byte UTF-8).
JNIEXPORT jbyteArray JNICALL
Java_org_offlineresearch_app_LlamaEngine_nativeFormatChat(JNIEnv* env, jobject, jlong h,
                                                          jobjectArray roles, jobjectArray contents) {
    auto* e = reinterpret_cast<Engine*>(h);
    const char* tmpl = llama_model_chat_template(e->model, nullptr);
    if (!tmpl) return nullptr;

    const jsize n = env->GetArrayLength(roles);
    std::vector<std::string> r(n), c(n);
    size_t total = 0;
    for (jsize i = 0; i < n; ++i) {
        auto js = (jstring) env->GetObjectArrayElement(roles, i);
        const char* s = env->GetStringUTFChars(js, nullptr);
        r[i] = s;
        env->ReleaseStringUTFChars(js, s);
        env->DeleteLocalRef(js);

        auto jb = (jbyteArray) env->GetObjectArrayElement(contents, i);
        const jsize len = env->GetArrayLength(jb);
        c[i].resize(len);
        env->GetByteArrayRegion(jb, 0, len, reinterpret_cast<jbyte*>(c[i].data()));
        env->DeleteLocalRef(jb);
        total += c[i].size();
    }
    std::vector<llama_chat_message> msgs(n);
    for (jsize i = 0; i < n; ++i) msgs[i] = { r[i].c_str(), c[i].c_str() };

    std::vector<char> buf(total * 2 + 2048);
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), /*add_ass=*/true, buf.data(), (int32_t) buf.size());
    if (len > (int32_t) buf.size()) {
        buf.resize(len);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    }
    if (len < 0) return nullptr;
    jbyteArray out = env->NewByteArray(len);
    env->SetByteArrayRegion(out, 0, len, reinterpret_cast<const jbyte*>(buf.data()));
    return out;
}

// Returns number of generated tokens, or a negative error code:
//  -2 prompt does not fit context, -3 tokenize failed, -4 bad sink, -5 decode failed
JNIEXPORT jint JNICALL
Java_org_offlineresearch_app_LlamaEngine_nativeGenerate(JNIEnv* env, jobject, jlong h, jbyteArray jprompt,
                                                        jint max_new, jfloat temp, jobject sink) {
    auto* e = reinterpret_cast<Engine*>(h);
    g_cancel = false;

    const jsize plen = env->GetArrayLength(jprompt);
    std::string prompt(plen, '\0');
    env->GetByteArrayRegion(jprompt, 0, plen, reinterpret_cast<jbyte*>(prompt.data()));

    jclass    cls = env->GetObjectClass(sink);
    jmethodID mid = env->GetMethodID(cls, "onToken", "([B)Z");
    if (!mid) return -4;

    llama_memory_clear(llama_get_memory(e->ctx), true);   // fresh KV cache per call (stateless agent steps)

    const int n_need = -llama_tokenize(e->vocab, prompt.c_str(), (int) prompt.size(), nullptr, 0, true, true);
    if (n_need <= 0) return -3;
    std::vector<llama_token> toks(n_need);
    if (llama_tokenize(e->vocab, prompt.c_str(), (int) prompt.size(), toks.data(), n_need, true, true) < 0) return -3;
    const int n_tok = n_need;
    if (n_tok >= e->n_ctx - 8) return -2;
    g_last_prompt_tokens = n_tok;
    const int budget = std::min<int>(max_new, e->n_ctx - n_tok);

    const int n_batch = 512;
    for (int i = 0; i < n_tok; i += n_batch) {
        const int n = std::min(n_batch, n_tok - i);
        if (llama_decode(e->ctx, llama_batch_get_one(toks.data() + i, n)) != 0) return -5;
        if (g_cancel) return 0;
    }

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temp <= 0.f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(20));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.8f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string pending;
    char piece[256];
    int  n_gen = 0;
    while (n_gen < budget && !g_cancel) {
        llama_token id = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, id)) break;

        const int n = llama_token_to_piece(e->vocab, id, piece, sizeof(piece), 0, true);
        if (n > 0) pending.append(piece, n);
        ++n_gen;

        const size_t ok = utf8_complete_prefix(pending);
        if (ok > 0) {
            jbyteArray arr = env->NewByteArray((jsize) ok);
            env->SetByteArrayRegion(arr, 0, (jsize) ok, reinterpret_cast<const jbyte*>(pending.data()));
            const jboolean cont = env->CallBooleanMethod(sink, mid, arr);
            env->DeleteLocalRef(arr);
            pending.erase(0, ok);
            if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
            if (!cont) break;
        }
        if (llama_decode(e->ctx, llama_batch_get_one(&id, 1)) != 0) { n_gen = -5; break; }
    }
    llama_sampler_free(smpl);
    return n_gen;
}

} // extern "C"
