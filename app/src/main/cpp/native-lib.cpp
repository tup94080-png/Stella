#include <jni.h>
#include <string>
#include <vector>
#include "llama.h"

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omni_ai_LocalBridgeService_nativeLoadModel(
        JNIEnv* env, jobject, jstring modelPathObj, jint nCtx, jint nThreads) {
    const char* modelPath = env->GetStringUTFChars(modelPathObj, nullptr);

    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true;
    mparams.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(modelPath, mparams);
    env->ReleaseStringUTFChars(modelPathObj, modelPath);
    if (!g_model) return JNI_FALSE;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;

    g_ctx = llama_init_from_model(g_model, cparams);
    return g_ctx != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_omni_ai_LocalBridgeService_nativeGenerate(
        JNIEnv* env, jobject, jstring promptObj, jint maxTokens) {
    if (!g_model || !g_ctx) {
        std::string err = "Model not loaded. Check your .gguf path and file existence.";
        jbyteArray out = env->NewByteArray(err.size());
        env->SetByteArrayRegion(out, 0, err.size(), reinterpret_cast<const jbyte*>(err.data()));
        return out;
    }

    const char* promptChars = env->GetStringUTFChars(promptObj, nullptr);
    std::string prompt(promptChars);
    env->ReleaseStringUTFChars(promptObj, promptChars);

    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    std::vector<llama_token> tokens(prompt.size() + 16);
    int n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(), tokens.data(), tokens.size(), true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(), tokens.data(), tokens.size(), true, true);
    }
    tokens.resize(n_tokens);

    llama_memory_clear(llama_get_memory(g_ctx), true);

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(g_ctx, batch) != 0) {
        std::string err = "Decode failed.";
        jbyteArray out = env->NewByteArray(err.size());
        env->SetByteArrayRegion(out, 0, err.size(), reinterpret_cast<const jbyte*>(err.data()));
        return out;
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.15f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));

    std::string result;
    for (int i = 0; i < maxTokens; ++i) {
        llama_token new_token = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, new_token)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) result.append(buf, n);

        llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_ctx, next_batch) != 0) break;
    }

    llama_sampler_free(smpl);

    jbyteArray out = env->NewByteArray(result.size());
    env->SetByteArrayRegion(out, 0, result.size(), reinterpret_cast<const jbyte*>(result.data()));
    return out;
}

