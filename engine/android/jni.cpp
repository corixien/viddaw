// Android binding: Oboe streams drive the engine, JNI exposes it to
// io.github.corixien.viddaw.engine.Native.
#include <jni.h>
#include <oboe/Oboe.h>

#include <memory>
#include <mutex>
#include <string>

#include "audio_file.h"
#include "engine.h"

namespace {

using namespace vd;

class AudioIO : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    explicit AudioIO(Engine& engine) : engine_(engine), input_(engine) {}

    bool startOutput() {
        std::lock_guard<std::mutex> lock(mutex_);
        wantOutput_ = true;
        return openOutput();
    }
    void stopOutput() {
        std::lock_guard<std::mutex> lock(mutex_);
        wantOutput_ = false;
        if (out_) out_->stop(), out_->close(), out_.reset();
    }
    bool startInput() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (in_) return true;
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Input)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(1)
            ->setSampleRate(kSampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setInputPreset(oboe::InputPreset::VoicePerformance)
            ->setDataCallback(&input_);
        if (b.openStream(in_) != oboe::Result::OK) return false;
        if (in_->requestStart() != oboe::Result::OK) {
            in_->close(), in_.reset();
            return false;
        }
        return true;
    }
    void stopInput() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (in_) in_->stop(), in_->close(), in_.reset();
    }
    // Estimated round trip in frames, used to line recordings up with the playback they were made to.
    int latencyFrames() {
        std::lock_guard<std::mutex> lock(mutex_);
        double ms = 0;
        for (auto& s : {out_, in_}) {
            if (!s) continue;
            auto r = s->calculateLatencyMillis();
            ms += r ? r.value() : (double)s->getBufferSizeInFrames() * 1000.0 / kSampleRate;
        }
        return (int)(ms * kSampleRate / 1000.0);
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data, int32_t frames) override {
        engine_.render((float*)data, frames);
        return oboe::DataCallbackResult::Continue;
    }
    // The device went away (headphones unplugged, route change): reopen on the new default.
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result) override {
        std::lock_guard<std::mutex> lock(mutex_);
        if (stream->getDirection() == oboe::Direction::Output && wantOutput_) {
            out_.reset();
            openOutput();
        }
    }

private:
    struct Input : oboe::AudioStreamDataCallback {
        explicit Input(Engine& e) : engine(e) {}
        Engine& engine;
        oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data, int32_t frames) override {
            engine.pushInput((const float*)data, frames);
            return oboe::DataCallbackResult::Continue;
        }
    };

    bool openOutput() {
        if (out_) return true;
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(2)
            ->setSampleRate(kSampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        if (b.openStream(out_) != oboe::Result::OK) return false;
        out_->setBufferSizeInFrames(out_->getFramesPerBurst() * 2);
        if (out_->requestStart() != oboe::Result::OK) {
            out_->close(), out_.reset();
            return false;
        }
        return true;
    }

    Engine& engine_;
    Input input_;
    std::mutex mutex_;
    std::shared_ptr<oboe::AudioStream> out_, in_;
    bool wantOutput_ = false;
};

Engine* engine() {
    static Engine* e = new Engine();
    return e;
}
AudioIO* io() {
    static AudioIO* a = new AudioIO(*engine());
    return a;
}
Importer* importer = nullptr;

std::string str(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

jfloatArray floats(JNIEnv* env, const std::vector<float>& v) {
    jfloatArray a = env->NewFloatArray((jsize)v.size());
    if (!v.empty()) env->SetFloatArrayRegion(a, 0, (jsize)v.size(), v.data());
    return a;
}

}  // namespace

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_io_github_corixien_viddaw_engine_Native_##name

JNI(jboolean, start)(JNIEnv*, jobject) { return io()->startOutput(); }
JNI(void, stop)(JNIEnv*, jobject) { io()->stopOutput(); }
JNI(void, sync)(JNIEnv* env, jobject, jstring snapshot) { engine()->sync(str(env, snapshot)); }
JNI(void, play)(JNIEnv*, jobject) { engine()->play(); }
JNI(void, pause)(JNIEnv*, jobject) { engine()->pause(); }
JNI(void, seek)(JNIEnv*, jobject, jlong frame) { engine()->seek(frame); }
JNI(jlong, position)(JNIEnv*, jobject) { return engine()->position(); }
JNI(jboolean, isPlaying)(JNIEnv*, jobject) { return engine()->playing(); }
JNI(void, noteOn)(JNIEnv*, jobject, jlong track, jint pitch, jint velocity) { engine()->noteOn(track, pitch, velocity); }
JNI(void, noteOff)(JNIEnv*, jobject, jlong track, jint pitch) { engine()->noteOff(track, pitch); }

JNI(jint, meters)(JNIEnv* env, jobject, jfloatArray out) {
    float buf[130];
    const int n = engine()->meters(buf, std::min<int>(130, env->GetArrayLength(out)));
    env->SetFloatArrayRegion(out, 0, n, buf);
    return n;
}

JNI(jboolean, startInput)(JNIEnv*, jobject) { return io()->startInput(); }
JNI(void, stopInput)(JNIEnv*, jobject) { io()->stopInput(); }
JNI(jfloat, inputLevel)(JNIEnv*, jobject) { return engine()->inputLevel(); }
JNI(jint, latencyFrames)(JNIEnv*, jobject) { return io()->latencyFrames(); }

JNI(jboolean, startRecording)(JNIEnv* env, jobject, jstring path) {
    if (!io()->startInput()) return false;
    return engine()->startRecording(str(env, path));
}

// Returns [frames, startFrame].
JNI(jlongArray, stopRecording)(JNIEnv* env, jobject) {
    jlong r[2];
    int64_t start = 0;
    r[0] = engine()->stopRecording(&start);
    r[1] = start;
    io()->stopInput();
    jlongArray a = env->NewLongArray(2);
    env->SetLongArrayRegion(a, 0, 2, r);
    return a;
}

JNI(jfloatArray, recordingPeaks)(JNIEnv* env, jobject, jint from) { return floats(env, engine()->recordingPeaks(from)); }

JNI(jfloatArray, peaks)(JNIEnv* env, jobject, jstring path) {
    return floats(env, computePeaks(str(env, path), Engine::kPeakFrames));
}

JNI(jlong, fileFrames)(JNIEnv* env, jobject, jstring path) {
    auto f = AudioFile::open(str(env, path));
    return f ? f->frames : 0;
}

JNI(jlong, stretch)(JNIEnv* env, jobject, jstring src, jstring dst, jdouble speed, jdouble semitones) {
    return stretchFile(str(env, src), str(env, dst), speed, semitones);
}

// Blocks until done. The output stream is paused so only this thread drives the engine.
JNI(jboolean, renderToFile)(JNIEnv* env, jobject, jstring path, jlong start, jlong end, jint bits) {
    engine()->pause();
    io()->stopOutput();
    const bool ok = engine()->renderToFile(str(env, path), start, end, bits);
    io()->startOutput();
    return ok;
}
JNI(jfloat, renderProgress)(JNIEnv*, jobject) { return engine()->renderProgress(); }
JNI(void, cancelRender)(JNIEnv*, jobject) { engine()->cancelRender(); }

JNI(jboolean, importBegin)(JNIEnv* env, jobject, jstring path, jint rate, jint channels) {
    delete importer;
    importer = new Importer();
    return importer->begin(str(env, path), rate, channels);
}
JNI(void, importWrite)(JNIEnv* env, jobject, jshortArray pcm, jint frames) {
    if (!importer) return;
    jshort* p = env->GetShortArrayElements(pcm, nullptr);
    importer->write(p, frames);
    env->ReleaseShortArrayElements(pcm, p, JNI_ABORT);
}
JNI(jlong, importEnd)(JNIEnv*, jobject) {
    if (!importer) return 0;
    const jlong frames = importer->end();
    delete importer;
    importer = nullptr;
    return frames;
}
