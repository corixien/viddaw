// Host tests for the engine core. Plain asserts, no framework.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>
#include <vector>

#include "audio_file.h"
#include "effects.h"
#include "engine.h"

using namespace vd;

static int failures = 0;
#define CHECK(cond)                                                    \
    do {                                                               \
        if (!(cond)) {                                                 \
            printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond);     \
            failures++;                                                \
        }                                                              \
    } while (0)

static std::string tmp(const char* name) { return std::string("/tmp/viddaw_test_") + name; }

static void writeSine(const std::string& path, int channels, double seconds, float freq, float amp) {
    WavWriter w;
    w.open(path, channels, 32);
    std::vector<float> buf;
    const int n = (int)(seconds * kSampleRate);
    for (int i = 0; i < n; i++)
        for (int c = 0; c < channels; c++) buf.push_back(amp * std::sin(2 * M_PI * freq * i / kSampleRate));
    w.write(buf.data(), n);
}

static double rms(const AudioFile& f, int64_t from, int64_t to) {
    double acc = 0;
    for (int64_t i = from * f.channels; i < to * f.channels; i++) acc += (double)f.data[i] * f.data[i];
    return std::sqrt(acc / (double)((to - from) * f.channels));
}

static void testWavRoundTrip() {
    writeSine(tmp("a.wav"), 2, 1.0, 440, 0.5f);
    auto f = AudioFile::open(tmp("a.wav"));
    CHECK(f && f->frames == kSampleRate && f->channels == 2);
    CHECK(std::fabs(rms(*f, 0, f->frames) - 0.5 / std::sqrt(2.0)) < 0.01);
    auto peaks = computePeaks(tmp("a.wav"), 512);
    CHECK(peaks.size() == 94 && std::fabs(peaks[3] - 0.5f) < 0.01f);
    CHECK(!AudioFile::open("/nonexistent.wav"));
}

static void testImporter() {
    for (int rate : {44100, 48000, 96000, 22050}) {
        Importer imp;
        CHECK(imp.begin(tmp("imp.wav"), rate, 2));
        std::vector<int16_t> pcm;
        for (int i = 0; i < rate; i++) {
            const int16_t v = (int16_t)(16000 * std::sin(2 * M_PI * 1000.0 * i / rate));
            pcm.push_back(v), pcm.push_back(v);
        }
        for (size_t at = 0; at < pcm.size(); at += 4096 * 2)
            imp.write(pcm.data() + at, (int64_t)std::min<size_t>(4096, (pcm.size() - at) / 2));
        const int64_t frames = imp.end();
        CHECK(std::llabs(frames - kSampleRate) < 64);
        auto f = AudioFile::open(tmp("imp.wav"));
        CHECK(f && f->frames == frames);
        const double expect = 16000.0 / 32768.0 / std::sqrt(2.0);
        CHECK(std::fabs(rms(*f, 2000, frames - 2000) - expect) < 0.01);
    }
}

static void testEffects() {
    struct Case {
        const char* type;
        std::vector<float> params;
    };
    // Default parameters, mirrored from the Kotlin EffectSpecs.
    const std::vector<Case> cases = {
        {"eq", {20, 3, 120, -4, 500, 1, 2, 3000, 1, 4, 8000, 20000}},
        {"comp", {-18, 4, 10, 120, 6, 0, 100}},
        {"limiter", {-1, 100, 6}},
        {"gate", {-45, 1, 50, 150, -80}},
        {"reverb", {50, 2, 50, 10, 100, 25}},
        {"delay", {375, 35, 0, 100, 6000, 25}},
        {"chorus", {0.8f, 40, 12, 50}},
        {"flanger", {0.3f, 60, 50, 50}},
        {"phaser", {0.4f, 70, 50, 800, 50}},
        {"filter", {0, 1000, 20}},
        {"dist", {0, 12, 8000, -6, 100}},
        {"width", {100}},
        {"deesser", {6000, -30, 8}},
    };
    for (const auto& c : cases) {
        auto fx = createEffect(c.type);
        CHECK(fx);
        if (!fx) continue;
        for (size_t i = 0; i < c.params.size(); i++) fx->params[i].store(c.params[i]);
        uint32_t rng = 1;
        double energy = 0;
        float peak = 0;
        bool finite = true;
        for (int block = 0; block < 200; block++) {
            float l[256], r[256];
            for (int i = 0; i < 256; i++) {
                rng = rng * 1664525u + 1013904223u;
                // Noise bursts with silence in between exercise attack, release and tails.
                const float v = block % 40 < 20 ? ((float)(rng >> 8) / 8388608.f - 1.f) * 0.5f : 0.f;
                l[i] = v, r[i] = -v * 0.5f;
            }
            fx->process(l, r, 256);
            for (int i = 0; i < 256; i++) {
                finite = finite && std::isfinite(l[i]) && std::isfinite(r[i]);
                peak = std::max(peak, std::max(std::fabs(l[i]), std::fabs(r[i])));
                energy += l[i] * l[i];
            }
        }
        if (!finite || peak > 4 || energy < 1e-3) printf("effect %s: finite=%d peak=%f energy=%f\n", c.type, finite, peak, energy);
        CHECK(finite && peak <= 4 && energy >= 1e-3);
    }
    CHECK(!createEffect("nope"));

    // The limiter must never exceed its ceiling.
    auto lim = createEffect("limiter");
    lim->params[0].store(-6), lim->params[1].store(100), lim->params[2].store(12);
    float worst = 0;
    for (int block = 0; block < 50; block++) {
        float l[256], r[256];
        for (int i = 0; i < 256; i++) l[i] = r[i] = std::sin(i * 0.3f + block);
        lim->process(l, r, 256);
        for (float v : l) worst = std::max(worst, std::fabs(v));
    }
    CHECK(worst <= 0.5012f + 1e-4f);
}

static std::string bounce(Engine& e, const char* name, int64_t frames) {
    const std::string out = tmp(name);
    CHECK(e.renderToFile(out, 0, frames, 32));
    return out;
}

static void testEngineMix() {
    writeSine(tmp("clip.wav"), 1, 1.0, 440, 0.5f);
    Engine e;
    // One audio track: clip of half a second starting at 0.5 s, read from 0.25 s into the file.
    e.sync("P 24000 4 0 0 0 1 0\nT 1 0 0.5 0 1\nC 24000 12000 24000 1 0 0 " + tmp("clip.wav") + "\n");
    auto f = AudioFile::open(bounce(e, "mix.wav", kSampleRate * 2));
    CHECK(f && f->frames == kSampleRate * 2 && f->channels == 2);
    if (!f) return;
    CHECK(rms(*f, 0, 23000) < 1e-6);
    CHECK(std::fabs(rms(*f, 25000, 47000) - 0.25 / std::sqrt(2.0)) < 0.005);
    CHECK(rms(*f, 49000, 96000) < 1e-6);

    // Hard left pan silences the right channel; an inaudible track is silent.
    e.sync("P 24000 4 0 0 0 1 0\nT 1 0 1 -1 1\nC 0 0 48000 1 0 0 " + tmp("clip.wav") + "\n");
    f = AudioFile::open(bounce(e, "pan.wav", kSampleRate));
    double left = 0, right = 0;
    for (int64_t i = 4800; i < f->frames; i++) left += std::fabs(f->data[i * 2]), right += std::fabs(f->data[i * 2 + 1]);
    CHECK(left > 1000 && right < 1e-3);
    e.sync("P 24000 4 0 0 0 1 0\nT 1 0 1 0 0\nC 0 0 48000 1 0 0 " + tmp("clip.wav") + "\n");
    f = AudioFile::open(bounce(e, "mute.wav", kSampleRate));
    CHECK(rms(*f, 4800, f->frames) < 1e-6);

    // An effect in the chain changes the signal: a closed low-pass removes the 440 Hz tone.
    e.sync("P 24000 4 0 0 0 1 0\nT 1 0 1 0 1\nC 0 0 48000 1 0 0 " + tmp("clip.wav") + "\nE 9 filter 1 3 0 40 0\n");
    f = AudioFile::open(bounce(e, "fx.wav", kSampleRate));
    CHECK(rms(*f, 4800, f->frames) < 0.02);
}

static void testInstruments() {
    Engine e;
    // Drum track: kick on beat 1, snare half a second later. Sampler track: one note.
    e.sync("P 24000 4 0 0 0 1 0\nT 1 2 1 0 1\nN 0 2400 36 127\nN 24000 2400 38 127\n"
           "T 2 1 1 0 1\nS 60 0 2 200 1 -\nN 48000 12000 64 100\n");
    auto f = AudioFile::open(bounce(e, "inst.wav", kSampleRate * 2));
    CHECK(f);
    if (!f) return;
    CHECK(rms(*f, 0, 4800) > 0.05);        // kick
    CHECK(rms(*f, 24000, 28800) > 0.02);   // snare
    CHECK(rms(*f, 48000, 58000) > 0.02);   // sampler note
    CHECK(rms(*f, 90000, 96000) < 0.01);   // released

    // Live notes sound without transport.
    float buf[512 * 2];
    e.noteOn(1, 36, 127);
    double acc = 0;
    for (int i = 0; i < 10; i++) {
        e.render(buf, 512);
        for (float v : buf) acc += std::fabs(v);
    }
    CHECK(acc > 10);
}

static void testTransport() {
    Engine e;
    e.sync("P 24000 4 1 1000 3000 1 1\n");
    float buf[512 * 2];
    e.seek(1000);
    e.play();
    double click = 0;
    for (int i = 0; i < 100; i++) {
        e.render(buf, 512);
        for (float v : buf) click += std::fabs(v);
        CHECK(e.position() >= 1000 && e.position() < 3000);
    }
    e.pause();
    e.render(buf, 512);
    const int64_t held = e.position();
    e.render(buf, 512);
    CHECK(e.position() == held);

    // Metronome clicks on beats when not looping around them.
    e.sync("P 24000 4 0 0 0 1 1\n");
    e.seek(0);
    e.play();
    for (int i = 0; i < 100; i++) {
        e.render(buf, 512);
        for (float v : buf) click += std::fabs(v);
    }
    CHECK(click > 1);
    float m[8];
    CHECK(e.meters(m, 8) == 2 && m[0] > 0.01f);
}

static void testRecording() {
    Engine e;
    CHECK(e.startRecording(tmp("rec.wav")));
    std::vector<float> in(480, 0.25f);
    for (int i = 0; i < 100; i++) e.pushInput(in.data(), 480);
    std::this_thread::sleep_for(std::chrono::milliseconds(60));
    CHECK(!e.recordingPeaks(0).empty());
    int64_t start = -1;
    CHECK(e.stopRecording(&start) == 48000 && start == 0);
    auto f = AudioFile::open(tmp("rec.wav"));
    CHECK(f && f->frames == 48000 && f->channels == 1 && std::fabs(f->data[100] - 0.25f) < 1e-6);
    CHECK(e.inputLevel() > 0.2f);
}

static void testStretch() {
    writeSine(tmp("st_in.wav"), 2, 2.0, 440.f, 0.5f);
    // Half speed: twice as long, same pitch.
    int64_t n = stretchFile(tmp("st_in.wav"), tmp("st_slow.wav"), 0.5, 0);
    CHECK(std::llabs(n - 192000) < 200);
    // One octave up at the same length.
    n = stretchFile(tmp("st_in.wav"), tmp("st_up.wav"), 1.0, 12);
    CHECK(std::llabs(n - 96000) < 200);
    for (const char* name : {"st_slow.wav", "st_up.wav"}) {
        auto f = AudioFile::open(tmp(name));
        CHECK(f && f->channels == 2);
        // Count zero crossings in the middle second to read the frequency.
        int crossings = 0;
        float peak = 0;
        for (int64_t i = 24000; i < 72000; i++) {
            if (f->data[i * 2] <= 0 && f->data[(i + 1) * 2] > 0) crossings++;
            peak = std::max(peak, std::fabs(f->data[i * 2]));
        }
        const int want = std::string(name) == "st_up.wav" ? 880 : 440;
        CHECK(std::abs(crossings - want) <= 4);
        CHECK(peak > 0.4f && peak < 0.62f);
    }
}

int main() {
    testWavRoundTrip();
    testImporter();
    testEffects();
    testEngineMix();
    testInstruments();
    testTransport();
    testRecording();
    testStretch();
    printf(failures ? "%d FAILED\n" : "all engine tests passed\n", failures);
    return failures ? 1 : 0;
}
