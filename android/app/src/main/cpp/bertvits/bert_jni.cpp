#include <jni.h>
#include <string>
#include <vector>
#include "bert_vits2_v23_loader.hpp"
void release_jieba();

namespace {
struct JavaInterrupted {};
std::string string(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw JavaInterrupted();
    std::string result(chars); env->ReleaseStringUTFChars(value, chars); return result;
}
std::vector<int> ints(JNIEnv* env, jintArray value) {
    int size = env->GetArrayLength(value);
    std::vector<int> result(size); env->GetIntArrayRegion(value, 0, size, result.data());
    if (env->ExceptionCheck()) throw JavaInterrupted();
    return result;
}
void error(JNIEnv* env, const char* type, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass(type), message);
}
}

extern "C" JNIEXPORT void JNICALL
Java_io_myreads_app_tts_BertVitsEngine_nativeLoad(JNIEnv* env, jobject, jstring directory) {
    try {
        using namespace MNN_BERT_VITS2;
        std::string base = string(env, directory);
        std::string prefix = base + "/bv2_model/mix/22k_bili22_mix_finetune_";
        init_vits_loader();
        set_vits_model_path(prefix+"enc.mnn", prefix+"dec.mnn", prefix+"sdp.mnn", prefix+"dp.mnn",
            prefix+"emb.mnn", prefix+"flow.mnn", base+"/bert/zh/chinese-roberta-wwm-ext-large-distilled-fp16.mnn");
    } catch (const JavaInterrupted&) {} catch (const std::exception& e) {
        MNN_BERT_VITS2::destroy_vits_loader(); release_jieba(); error(env,"java/io/IOException",e.what());
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_myreads_app_tts_BertVitsEngine_nativeSynthesize(JNIEnv* env, jobject,
        jintArray phones, jintArray tones, jintArray languages, jintArray ids,
        jintArray word2ph, jintArray mask, jobject callback) {
    try {
        using namespace MNN_BERT_VITS2;
        jclass cls = env->GetObjectClass(callback);
        jmethodID await = env->GetMethodID(cls,"awaitReady","()V");
        env->DeleteLocalRef(cls);
        if (!await) throw JavaInterrupted();
        // The calling JVM thread owns every graph. Exceptions unwind at bounded
        // graph boundaries before a paused narrator can hold the shared engine.
        set_checkpoint([&] {
            env->CallVoidMethod(callback, await);
            if (env->ExceptionCheck()) throw JavaInterrupted();
        });
        struct Reset { ~Reset() { MNN_BERT_VITS2::set_checkpoint(nullptr); } } reset;
        auto x = ints(env, phones), t = ints(env, tones), lang = ints(env, languages);
        auto input = ints(env, ids), words = ints(env, word2ph), attention = ints(env, mask);
        if (x.size()<3 || x.size()>512 || x.size()!=t.size() || x.size()!=lang.size()
            || input.size()!=words.size() || input.size()!=attention.size())
            throw std::runtime_error("语音输入长度无效");
        if (!input.empty()) {
            int count = 0; for (int n : words) { if (n<1 || n>64) throw std::runtime_error("音素对齐无效"); count+=n; }
            if (count!=x.size()) throw std::runtime_error("音素对齐无效");
        }
        auto result = start_audio_infer(x,t,lang,input,words,attention,0);
        if (result.empty() || result.size()>22050*31) throw TooLong();
        jfloatArray output = env->NewFloatArray(result.size());
        if (output) env->SetFloatArrayRegion(output,0,result.size(),result.data());
        return output;
    } catch (const JavaInterrupted&) {
    } catch (const MNN_BERT_VITS2::TooLong& e) {
        error(env,"io/myreads/app/tts/BertVitsEngine$TooLong", e.what());
    } catch (const std::exception& e) { error(env,"java/io/IOException",e.what()); }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_io_myreads_app_tts_BertVitsEngine_nativeClose(JNIEnv*, jobject) {
    MNN_BERT_VITS2::destroy_vits_loader();
    release_jieba();
}
