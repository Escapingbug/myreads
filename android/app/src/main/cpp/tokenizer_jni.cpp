#include <jni.h>
#include <memory>
#include <string>
#include <vector>
#include "sentencepiece_processor.h"

// Java's modified UTF-8 differs from UTF-8 for supplementary characters.
static std::string utf8(JNIEnv* env, jstring value) {
    const jchar* chars = env->GetStringChars(value, nullptr);
    std::string result;
    const auto length = env->GetStringLength(value);
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length &&
            chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF)
            cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[++i] - 0xDC00);
        else if (cp >= 0xD800 && cp <= 0xDFFF) cp = 0xFFFD;
        if (cp < 0x80) result.push_back(cp);
        else if (cp < 0x800) {
            result.push_back(0xC0 | (cp >> 6)); result.push_back(0x80 | (cp & 63));
        } else if (cp < 0x10000) {
            result.push_back(0xE0 | (cp >> 12)); result.push_back(0x80 | ((cp >> 6) & 63)); result.push_back(0x80 | (cp & 63));
        } else {
            result.push_back(0xF0 | (cp >> 18)); result.push_back(0x80 | ((cp >> 12) & 63));
            result.push_back(0x80 | ((cp >> 6) & 63)); result.push_back(0x80 | (cp & 63));
        }
    }
    env->ReleaseStringChars(value, chars);
    return result;
}
static void fail(JNIEnv* env, const std::string& message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
}
extern "C" JNIEXPORT jlong JNICALL
Java_io_myreads_app_tts_NativeTokenizer_open(JNIEnv* env, jclass, jstring path) {
    auto processor = std::make_unique<sentencepiece::SentencePieceProcessor>();
    auto status = processor->Load(utf8(env, path));
    if (!status.ok()) { fail(env, status.ToString()); return 0; }
    return reinterpret_cast<jlong>(processor.release());
}
extern "C" JNIEXPORT jintArray JNICALL
Java_io_myreads_app_tts_NativeTokenizer_encode(JNIEnv* env, jclass, jlong handle, jstring input) {
    std::vector<int> ids;
    auto status = reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle)->Encode(utf8(env, input), &ids);
    if (!status.ok()) { fail(env, status.ToString()); return nullptr; }
    auto result = env->NewIntArray(ids.size());
    if (result) env->SetIntArrayRegion(result, 0, ids.size(), ids.data());
    return result;
}
extern "C" JNIEXPORT void JNICALL
Java_io_myreads_app_tts_NativeTokenizer_release(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);
}
