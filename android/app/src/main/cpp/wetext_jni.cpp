// WeTextProcessing tag -> token reorder -> verbalize, Apache-2.0.
// Adapted from wenet-e2e/WeTextProcessing 1.2.0 tn/processor.py and token_parser.py.
#include <jni.h>
#include <fst/compose.h>
#include <fst/shortest-path.h>
#include <fst/string.h>
#include <fst/vector-fst.h>
#include <memory>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
using Fst = fst::StdVectorFst;
using Members = std::vector<std::pair<std::string, std::string>>;
std::string bytes(JNIEnv* env, jbyteArray input) {
    std::string value(env->GetArrayLength(input), '\0');
    env->GetByteArrayRegion(input, 0, value.size(), reinterpret_cast<jbyte*>(&value[0])); return value;
}
struct Normalizer {
    std::unique_ptr<Fst> tagger, verbalizer;
    bool english;
    Normalizer(const std::string& tag, const std::string& verbal, bool en)
        : tagger(Fst::Read(tag)), verbalizer(Fst::Read(verbal)), english(en) {
        if (!tagger || !verbalizer) throw std::runtime_error("Cannot load text normalization graphs");
    }
    std::string rewrite(const std::string& input, const Fst& rule) {
        if (input.empty()) return "";
        Fst text, lattice, shortest;
        fst::StringCompiler<fst::StdArc> compile(fst::TokenType::BYTE);
        fst::StringPrinter<fst::StdArc> print(fst::TokenType::BYTE);
        if (!compile(input, &text)) throw std::runtime_error("Invalid normalization input");
        fst::Compose(text, rule, &lattice);
        fst::ShortestPath(lattice, &shortest, 1, true);
        std::string output;
        if (!print(shortest, &output)) throw std::runtime_error("Text normalization has no output path");
        return output;
    }
    std::string reorder(const std::string& text) {
        const std::unordered_map<std::string, std::vector<std::string>> zh = {
            {"date", {"year", "month", "day"}}, {"fraction", {"denominator", "numerator"}},
            {"measure", {"denominator", "numerator", "value"}}, {"money", {"value", "currency"}},
            {"time", {"noon", "hour", "minute", "second"}}};
        const std::unordered_map<std::string, std::vector<std::string>> en = {
            {"date", {"preserve_order", "text", "day", "month", "year"}},
            {"money", {"integer_part", "fractional_part", "quantity", "currency_maj"}}};
        const auto& orders = english ? en : zh;
        size_t at = 0; std::string result;
        auto skip = [&]() { while (at < text.size() && text[at] == ' ') ++at; };
        auto key = [&]() {
            size_t start = at;
            while (at < text.size() && ((text[at] >= 'A' && text[at] <= 'Z') ||
                (text[at] >= 'a' && text[at] <= 'z') || text[at] == '_')) ++at;
            if (at == start) throw std::runtime_error("Invalid normalization token key");
            return text.substr(start, at - start);
        };
        auto expect = [&](char c) { skip(); if (at >= text.size() || text[at++] != c) throw std::runtime_error("Invalid normalization token"); };
        skip();
        while (at < text.size()) {
            auto name = key(); expect('{'); Members members; skip();
            while (at < text.size() && text[at] != '}') {
                auto field = key(); expect(':'); expect('"'); std::string value;
                while (at < text.size() && text[at] != '"') {
                    char c = text[at++]; value += c;
                    if (c == '\\' && at < text.size()) value += text[at++];
                }
                expect('"'); members.emplace_back(field, value); skip();
            }
            expect('}'); skip();
            std::vector<std::string> fields; bool preserve = false;
            for (const auto& item : members) { fields.push_back(item.first); if (item.first == "preserve_order" && item.second == "true") preserve = true; }
            auto order = orders.find(name); if (order != orders.end() && !preserve) fields = order->second;
            if (!result.empty()) result += " "; result += name + " {";
            for (const auto& field : fields) for (const auto& item : members)
                if (item.first == field) { result += " " + field + ": \"" + item.second + "\""; break; }
            result += " }";
        }
        return result;
    }
    std::string normalize(const std::string& input) { return rewrite(reorder(rewrite(input, *tagger)), *verbalizer); }
};
void fail(JNIEnv* env, const std::exception& e) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what()); }
}
extern "C" JNIEXPORT jlong JNICALL Java_io_myreads_app_tts_NativeWeText_open(JNIEnv* env, jclass, jbyteArray tag, jbyteArray verbal, jboolean english) {
    try { return reinterpret_cast<jlong>(new Normalizer(bytes(env, tag), bytes(env, verbal), english)); }
    catch (const std::exception& e) { fail(env, e); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_myreads_app_tts_NativeWeText_normalize(JNIEnv* env, jclass, jlong handle, jbyteArray text) {
    try {
        auto output = reinterpret_cast<Normalizer*>(handle)->normalize(bytes(env, text));
        auto result = env->NewByteArray(output.size());
        if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte*>(output.data())); return result;
    } catch (const std::exception& e) { fail(env, e); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_io_myreads_app_tts_NativeWeText_release(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Normalizer*>(handle);
}
