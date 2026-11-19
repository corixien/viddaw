// Encodes a 16-bit stereo 48 kHz WAV (as Engine::renderToFile writes it) to MP3 with Shine.
#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

extern "C" {
#include "layer3.h"
}

namespace {
struct Utf {
    JNIEnv* env;
    jstring s;
    const char* c;
    Utf(JNIEnv* e, jstring j) : env(e), s(j), c(e->GetStringUTFChars(j, nullptr)) {}
    ~Utf() { env->ReleaseStringUTFChars(s, c); }
};
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL Java_io_github_corixien_viddaw_engine_Mp3_encode(JNIEnv* env, jclass, jstring wavPath, jstring outPath, jint kbps) {
    Utf wav(env, wavPath), out(env, outPath);
    FILE* in = std::fopen(wav.c, "rb");
    if (!in) return JNI_FALSE;
    // Walk the chunks to the sample data; the header may carry extra chunks.
    char tag[4];
    uint32_t size = 0;
    std::fseek(in, 12, SEEK_SET);
    bool found = false;
    while (std::fread(tag, 1, 4, in) == 4 && std::fread(&size, 4, 1, in) == 1) {
        if (std::memcmp(tag, "data", 4) == 0) {
            found = true;
            break;
        }
        std::fseek(in, (long)size + (long)(size & 1), SEEK_CUR);
    }
    FILE* mp3 = found ? std::fopen(out.c, "wb") : nullptr;
    if (!mp3) {
        std::fclose(in);
        return JNI_FALSE;
    }

    shine_config_t config;
    std::memset(&config, 0, sizeof(config));
    shine_set_config_mpeg_defaults(&config.mpeg);
    config.wave.channels = PCM_STEREO;
    config.wave.samplerate = 48000;
    config.mpeg.mode = STEREO;
    config.mpeg.bitr = kbps;
    shine_t s = shine_initialise(&config);
    bool ok = s != nullptr;
    if (ok) {
        const int perPass = shine_samples_per_pass(s);  // frames per channel
        std::vector<int16_t> buf((size_t)perPass * 2);
        uint32_t left = size / 4;  // frames
        while (left > 0) {
            const size_t want = std::min<uint32_t>(left, (uint32_t)perPass);
            std::fill(buf.begin(), buf.end(), (int16_t)0);
            if (std::fread(buf.data(), 4, want, in) != want) left = (uint32_t)want;
            left -= (uint32_t)want;
            int written = 0;
            const unsigned char* data = shine_encode_buffer_interleaved(s, buf.data(), &written);
            if (written > 0 && std::fwrite(data, 1, (size_t)written, mp3) != (size_t)written) ok = false;
        }
        int written = 0;
        const unsigned char* tail = shine_flush(s, &written);
        if (written > 0 && std::fwrite(tail, 1, (size_t)written, mp3) != (size_t)written) ok = false;
        shine_close(s);
    }
    std::fclose(in);
    std::fclose(mp3);
    return ok ? JNI_TRUE : JNI_FALSE;
}
