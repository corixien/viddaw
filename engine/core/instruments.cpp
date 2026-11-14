#include "instruments.h"

#include <algorithm>
#include <cmath>

#include "audio_file.h"

namespace vd {
namespace {
constexpr float kSr = (float)kSampleRate;
constexpr float kTwoPi = 6.28318530718f;
}  // namespace

std::shared_ptr<Sample> loadSample(const std::string& path) {
    auto file = AudioFile::open(path);
    if (!file) return nullptr;
    auto s = std::make_shared<Sample>();
    const int64_t frames = std::min<int64_t>(file->frames, (int64_t)kSampleRate * 60);
    s->data.resize((size_t)frames);
    for (int64_t i = 0; i < frames; i++) {
        float v = 0;
        for (int c = 0; c < file->channels; c++) v += file->data[i * file->channels + c];
        s->data[(size_t)i] = v / (float)file->channels;
    }
    return s;
}

std::shared_ptr<Sample> builtinTone() {
    auto s = std::make_shared<Sample>();
    const int n = (int)(kSr * 2.5f);
    s->data.resize((size_t)n);
    const float f = 261.626f, amp[4] = {1.f, 0.5f, 0.25f, 0.12f}, decay[4] = {1.6f, 2.6f, 4.f, 6.f};
    for (int i = 0; i < n; i++) {
        const float t = (float)i / kSr;
        float v = 0;
        for (int h = 0; h < 4; h++) v += amp[h] * std::exp(-t * decay[h]) * std::sin(kTwoPi * f * (float)(h + 1) * t);
        const float edge = std::min(1.f, (float)(n - i) / (kSr * 0.05f));
        s->data[(size_t)i] = v * 0.4f * std::min(1.f, t * 500.f) * edge;
    }
    return s;
}

std::shared_ptr<Sample> builtinDrum(int pad) {
    // Each sound is a pitch-swept sine plus filtered noise, both with exponential decays.
    struct Spec {
        float f0, f1, sweep, toneDecay, tone, noise, noiseDecay, hp, lp, len;
        int kind;  // 0 plain, 1 clap bursts, 2 two square oscillators
    };
    static const Spec specs[kPads] = {
        {150, 45, 0.06f, 0.30f, 1.0f, 0.06f, 0.012f, 200, 8000, 0.6f, 0},    // kick
        {220, 170, 0.03f, 0.10f, 0.5f, 0.8f, 0.14f, 900, 12000, 0.4f, 0},    // snare
        {0, 0, 0, 0, 0, 0.8f, 0.035f, 7000, 16000, 0.15f, 0},                // closed hat
        {0, 0, 0, 0, 0, 0.7f, 0.28f, 7000, 16000, 0.8f, 0},                  // open hat
        {0, 0, 0, 0, 0, 0.9f, 0.16f, 900, 5000, 0.5f, 1},                    // clap
        {800, 780, 0.01f, 0.02f, 0.8f, 0.3f, 0.01f, 1500, 9000, 0.1f, 0},    // rim
        {110, 78, 0.08f, 0.28f, 0.9f, 0.05f, 0.02f, 300, 6000, 0.6f, 0},     // low tom
        {160, 118, 0.08f, 0.24f, 0.9f, 0.05f, 0.02f, 300, 6000, 0.5f, 0},    // mid tom
        {225, 170, 0.08f, 0.20f, 0.9f, 0.05f, 0.02f, 300, 6000, 0.45f, 0},   // high tom
        {0, 0, 0, 0, 0, 0.6f, 1.1f, 5000, 16000, 2.4f, 0},                   // crash
        {5200, 5200, 1, 0.5f, 0.08f, 0.4f, 0.7f, 8000, 16000, 1.6f, 0},      // ride
        {540, 800, 0, 0.22f, 0.6f, 0, 0, 0, 0, 0.6f, 2},                     // cowbell
        {0, 0, 0, 0, 0, 0.5f, 0.07f, 5000, 14000, 0.2f, 0},                  // shaker
        {330, 300, 0.03f, 0.14f, 0.9f, 0.08f, 0.01f, 600, 7000, 0.35f, 0},   // conga
        {90, 40, 0.12f, 0.85f, 1.0f, 0.03f, 0.01f, 100, 4000, 1.6f, 0},      // sub kick
        {1500, 1400, 0.01f, 0.02f, 0.3f, 0.9f, 0.03f, 2000, 9000, 0.12f, 0}, // snap
    };
    const Spec& d = specs[std::clamp(pad, 0, kPads - 1)];
    auto s = std::make_shared<Sample>();
    const int n = (int)(kSr * d.len);
    s->data.resize((size_t)n);
    uint32_t rng = 12345u + (uint32_t)pad * 7919u;
    float phase = 0, phase2 = 0, hpState = 0, lpState = 0;
    const float hpA = std::exp(-kTwoPi * std::max(d.hp, 20.f) / kSr);
    const float lpA = std::exp(-kTwoPi * std::max(d.lp, 200.f) / kSr);
    for (int i = 0; i < n; i++) {
        const float t = (float)i / kSr;
        float v = 0;
        if (d.kind == 2) {
            phase += d.f0 / kSr, phase2 += d.f1 / kSr;
            const float sq = (std::fmod(phase, 1.f) < 0.5f ? 1.f : -1.f) + (std::fmod(phase2, 1.f) < 0.5f ? 1.f : -1.f);
            v = sq * 0.3f * d.tone * std::exp(-t / d.toneDecay);
        } else if (d.tone > 0) {
            const float f = d.f1 + (d.f0 - d.f1) * std::exp(-t / std::max(d.sweep, 1e-4f));
            phase += f / kSr;
            v = std::sin(kTwoPi * phase) * d.tone * std::exp(-t / d.toneDecay);
        }
        if (d.noise > 0) {
            rng = rng * 1664525u + 1013904223u;
            const float white = (float)(rng >> 8) / 8388608.f - 1.f;
            lpState = white + (lpState - white) * lpA;
            hpState = lpState + (hpState - lpState) * hpA;
            float env = std::exp(-t / d.noiseDecay);
            if (d.kind == 1) {
                // Three short bursts, then the tail.
                const float burst = std::fmod(t, 0.011f);
                env = t < 0.033f ? std::exp(-burst / 0.004f) : std::exp(-(t - 0.033f) / d.noiseDecay);
            }
            v += (lpState - hpState) * d.noise * env * 1.6f;
        }
        const float edge = std::min(1.f, (float)(n - i) / (kSr * 0.01f));
        s->data[(size_t)i] = std::clamp(v * 0.8f * edge, -1.f, 1.f);
    }
    return s;
}

void Instrument::noteOn(const InstrumentConfig& cfg, int pitch, int velocity, int delay) {
    const Sample* sample;
    int pad = -1;
    float gain, pan = 0, semis;
    if (cfg.drums) {
        pad = pitch - kFirstPadNote;
        if (pad < 0 || pad >= kPads) return;
        const auto& p = cfg.pads[pad];
        sample = p.sample.get();
        gain = p.gain, pan = p.pan, semis = p.tune;
    } else {
        sample = cfg.sample.get();
        gain = cfg.gain, semis = (float)(pitch - cfg.root) + cfg.tune;
    }
    if (!sample || sample->data.empty()) return;

    int slot = 0;
    for (int i = 0; i < kVoices; i++) {
        Voice& v = voices_[i];
        // Retriggering a pad or key fades its previous voice quickly instead of stacking.
        if (v.active && !v.releasing && (cfg.drums ? v.pad == pad : v.pitch == pitch)) {
            v.releasing = true;
            v.releaseCoef = std::exp(-1.f / (0.005f * kSr));
        }
    }
    for (int i = 0; i < kVoices; i++) {
        if (!voices_[i].active) { slot = i; break; }
        if (age_[i] < age_[slot]) slot = i;  // otherwise steal the oldest
    }
    Voice& v = voices_[slot];
    age_[slot] = ++clock_;
    const float vel = (float)velocity / 127.f;
    const float amp = gain * vel * vel;
    v = Voice{};
    v.sample = sample;
    v.rate = std::pow(2.0, semis / 12.0);
    v.gainL = amp * std::min(1.f, 1.f - pan);
    v.gainR = amp * std::min(1.f, 1.f + pan);
    v.pitch = pitch, v.pad = pad, v.delay = std::max(delay, 0);
    v.active = true;
    if (cfg.drums) {
        v.env = 1;
    } else {
        v.attackInc = 1.f / std::max(1.f, cfg.attackMs * 0.001f * kSr);
        v.releaseCoef = std::exp(-1.f / (std::max(cfg.releaseMs, 1.f) * 0.001f * kSr / 4.6f));
    }
}

void Instrument::noteOff(int pitch, int delay) {
    for (auto& v : voices_)
        if (v.active && v.pad < 0 && v.pitch == pitch && !v.releasing && v.offDelay < 0) v.offDelay = std::max(delay, 0);
}

void Instrument::allOff() {
    for (auto& v : voices_)
        if (v.active && !v.releasing) {
            v.releasing = true;
            v.releaseCoef = std::exp(-1.f / (0.01f * kSr));
        }
}

void Instrument::render(const InstrumentConfig& cfg, float* l, float* r, int n) {
    for (auto& v : voices_) {
        if (!v.active) continue;
        // A voice whose sample left the configuration must stop before the old snapshot is freed.
        const Sample* current = v.pad >= 0 ? (cfg.drums ? cfg.pads[v.pad].sample.get() : nullptr) : cfg.sample.get();
        if (current != v.sample) {
            v.active = false;
            continue;
        }
        const float* data = v.sample->data.data();
        const double last = (double)v.sample->data.size() - 1;
        for (int i = 0; i < n; i++) {
            if (v.delay > 0) {
                v.delay--;
                continue;
            }
            if (v.offDelay == 0) v.releasing = true, v.offDelay = -1;
            else if (v.offDelay > 0) v.offDelay--;
            if (v.pos >= last) {
                v.active = false;
                break;
            }
            if (v.releasing) {
                v.env *= v.releaseCoef;
                if (v.env < 1e-4f) {
                    v.active = false;
                    break;
                }
            } else if (v.env < 1) {
                v.env = std::min(1.f, v.env + v.attackInc);
            }
            const int64_t k = (int64_t)v.pos;
            const float f = (float)(v.pos - (double)k);
            const float s = (data[k] + (data[k + 1] - data[k]) * f) * v.env;
            l[i] += s * v.gainL, r[i] += s * v.gainR;
            v.pos += v.rate;
        }
    }
}

}  // namespace vd
