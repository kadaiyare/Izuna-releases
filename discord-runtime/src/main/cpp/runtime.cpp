#include <jni.h>
#include <memory>
#include <string>
#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"

namespace {
// All accesses, including RunCallbacks and destruction, belong to one HandlerThread.
struct Result {
    int status = 0;
    int errorType = 0;
    int errorCode = 0;
    uint64_t generation = 0;
    bool alive = true;
};
struct Probe {
    std::shared_ptr<discordpp::Client> client;
    std::shared_ptr<Result> result = std::make_shared<Result>();
};
Probe* probe(jlong handle) { return reinterpret_cast<Probe*>(handle); }
void fail(JNIEnv* env) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Discord native operation failed");
}
std::string utf8(JNIEnv* env, jbyteArray value) {
    const auto size = env->GetArrayLength(value);
    std::string result(size, '\0');
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(result.data()));
    return result;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_kadaiyare_izuna_discordruntime_DiscordNative_create(JNIEnv* env, jobject, jlong applicationId) {
    try {
        auto value = std::make_unique<Probe>();
        value->client = std::make_shared<discordpp::Client>();
        value->client->SetApplicationId(static_cast<uint64_t>(applicationId));
        // Do not Connect or authorize: this probe exercises the Android RPC transport only.
        return reinterpret_cast<jlong>(value.release());
    } catch (...) { fail(env); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_kadaiyare_izuna_discordruntime_DiscordNative_publish(JNIEnv* env, jobject, jlong handle, jbyteArray title, jbyteArray artist, jboolean listening,
                                                         jboolean musicDetails, jlong startMs, jlong endMs, jbyteArray imageUrl, jint statusDisplayType) {
    try {
        auto* value = probe(handle);
        discordpp::Activity activity;
        activity.SetType(listening ? discordpp::ActivityTypes::Listening : discordpp::ActivityTypes::Playing);
        activity.SetDetails(utf8(env, title));
        activity.SetState(utf8(env, artist));
        discordpp::ActivityButton repositoryButton;
        repositoryButton.SetLabel("GitHub");
        repositoryButton.SetUrl("https://github.com/kadaiyare/Izuna-releases");
        activity.AddButton(repositoryButton);
        if (statusDisplayType != 0 && statusDisplayType != 2) { fail(env); return; }
        activity.SetStatusDisplayType(statusDisplayType == 2 ? discordpp::StatusDisplayTypes::Details : discordpp::StatusDisplayTypes::Name);
        if (imageUrl) {
            discordpp::ActivityAssets assets;
            assets.SetLargeImage(utf8(env, imageUrl));
            activity.SetAssets(assets);
        }
        if (env->ExceptionCheck()) return;
        if (musicDetails) {
            activity.SetName("Izuna");
            if (startMs != 0 || endMs != 0) {
                if (startMs <= 0 || endMs <= startMs) { fail(env); return; }
                discordpp::ActivityTimestamps timestamps;
                timestamps.SetStart(static_cast<uint64_t>(startMs));
                timestamps.SetEnd(static_cast<uint64_t>(endMs));
                activity.SetTimestamps(timestamps);
            }
        }
        auto state = value->result;
        const auto generation = ++state->generation;
        state->status = 1; state->errorType = 0; state->errorCode = 0;
        value->client->UpdateRichPresence(std::move(activity), [state, generation](discordpp::ClientResult result) {
            if (!state->alive || state->generation != generation) return;
            state->status = result.Successful() ? 2 : 3;
            state->errorType = static_cast<int>(result.Type());
            state->errorCode = result.ErrorCode();
        });
    } catch (...) { fail(env); }
}

extern "C" JNIEXPORT void JNICALL
Java_com_kadaiyare_izuna_discordruntime_DiscordNative_clear(JNIEnv* env, jobject, jlong handle) {
    try {
        auto* value = probe(handle);
        ++value->result->generation;
        value->client->ClearRichPresence();
        value->result->status = 4; value->result->errorType = 0; value->result->errorCode = 0;
    } catch (...) { fail(env); }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_kadaiyare_izuna_discordruntime_DiscordNative_poll(JNIEnv* env, jobject, jlong handle) {
    try {
        discordpp::RunCallbacks();
        const auto state = probe(handle)->result;
        const jint fields[] = {state->status, state->errorType, state->errorCode};
        auto output = env->NewIntArray(3);
        if (output) env->SetIntArrayRegion(output, 0, 3, fields);
        return output;
    } catch (...) { fail(env); return nullptr; }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kadaiyare_izuna_discordruntime_DiscordNative_version(JNIEnv* env, jobject) {
    try {
        const auto version = std::to_string(discordpp::Client::GetVersionMajor()) + "." +
            std::to_string(discordpp::Client::GetVersionMinor()) + "." + std::to_string(discordpp::Client::GetVersionPatch());
        return env->NewStringUTF(version.c_str()); // ASCII version only; tags use UTF-8 byte arrays.
    } catch (...) { fail(env); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_kadaiyare_izuna_discordruntime_DiscordNative_destroy(JNIEnv* env, jobject, jlong handle) {
    try {
        auto value = std::unique_ptr<Probe>(probe(handle));
        value->result->alive = false;
        ++value->result->generation;
        value->client.reset();
    } catch (...) { fail(env); }
}
