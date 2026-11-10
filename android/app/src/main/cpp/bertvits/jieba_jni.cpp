// JNI adapter for cppjieba (MIT); calls are serialized by SpeechModelRuntime.
#include <jni.h>
#include <memory>
#include <string>
#include "cppjieba/Jieba.hpp"

static std::unique_ptr<cppjieba::Jieba> jieba;
void release_jieba() { jieba.reset(); }
static std::string text(JNIEnv* env, jstring value) {
    const char* data=env->GetStringUTFChars(value,nullptr);
    if (!data) return {};
    std::string result(data); env->ReleaseStringUTFChars(value,data); return result;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_cppjieba_CppJiebaJNI_initJieba(JNIEnv* env,jobject,jstring dict,jstring hmm,jstring user,jstring idf,jstring stop) {
    if (!jieba) jieba=std::make_unique<cppjieba::Jieba>(text(env,dict),text(env,hmm),text(env,user),text(env,idf),text(env,stop));
    return JNI_TRUE;
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_cppjieba_CppJiebaJNI_tag(JNIEnv* env,jobject,jstring input) {
    std::vector<std::pair<std::string,std::string>> words;
    if (jieba) jieba->Tag(text(env,input),words);
    jclass cls=env->FindClass("com/example/cppjieba/WordTag");
    jmethodID ctor=env->GetMethodID(cls,"<init>","(Ljava/lang/String;Ljava/lang/String;)V");
    auto output=env->NewObjectArray(words.size(),cls,nullptr);
    for (size_t i=0;i<words.size();++i) {
        auto word=env->NewStringUTF(words[i].first.c_str()), tag=env->NewStringUTF(words[i].second.c_str());
        auto item=env->NewObject(cls,ctor,word,tag); env->SetObjectArrayElement(output,i,item);
        env->DeleteLocalRef(word); env->DeleteLocalRef(tag); env->DeleteLocalRef(item);
        if (env->ExceptionCheck()) break;
    }
    env->DeleteLocalRef(cls); return output;
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_cppjieba_CppJiebaJNI_cut(JNIEnv* env,jobject,jstring input) {
    std::vector<std::string> words; if (jieba) jieba->Cut(text(env,input),words,true);
    auto cls=env->FindClass("java/lang/String"); auto output=env->NewObjectArray(words.size(),cls,nullptr);
    for (size_t i=0;i<words.size();++i) {
        auto word=env->NewStringUTF(words[i].c_str()); env->SetObjectArrayElement(output,i,word); env->DeleteLocalRef(word);
    }
    env->DeleteLocalRef(cls); return output;
}
