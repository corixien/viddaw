#include "effects.h"

#include <algorithm>
#include <vector>

#include "audio_file.h"

namespace vd {
namespace {

constexpr float kSr = (float)kSampleRate;
constexpr float kPi = 3.14159265358979f;

inline float linToDb(float x) { return 20.f * std::log10(x + 1e-9f); }
inline float msCoef(float ms) { return std::exp(-1.f / (std::max(ms, 0.01f) * 0.001f * kSr)); }

// RBJ cookbook biquad, transposed direct form II, two channels of state.
struct Biquad {
    enum Type { LowPass, HighPass, Peak, LowShelf, HighShelf };
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    float z1[2] = {0, 0}, z2[2] = {0, 0};

    void set(Type type, float freq, float q, float gainDb) {
        freq = std::clamp(freq, 10.f, kSr * 0.45f);
        const float w = 2 * kPi * freq / kSr, c = std::cos(w), s = std::sin(w);
        const float alpha = s / (2 * std::max(q, 0.05f));
        const float A = std::pow(10.f, gainDb / 40.f), sq = 2 * std::sqrt(A) * alpha;
        float a0 = 1;
        switch (type) {
            case LowPass:
                b0 = (1 - c) / 2, b1 = 1 - c, b2 = (1 - c) / 2, a0 = 1 + alpha, a1 = -2 * c, a2 = 1 - alpha;
                break;
            case HighPass:
                b0 = (1 + c) / 2, b1 = -(1 + c), b2 = (1 + c) / 2, a0 = 1 + alpha, a1 = -2 * c, a2 = 1 - alpha;
                break;
            case Peak:
                b0 = 1 + alpha * A, b1 = -2 * c, b2 = 1 - alpha * A;
                a0 = 1 + alpha / A, a1 = -2 * c, a2 = 1 - alpha / A;
                break;
            case LowShelf:
                b0 = A * ((A + 1) - (A - 1) * c + sq), b1 = 2 * A * ((A - 1) - (A + 1) * c);
                b2 = A * ((A + 1) - (A - 1) * c - sq);
                a0 = (A + 1) + (A - 1) * c + sq, a1 = -2 * ((A - 1) + (A + 1) * c), a2 = (A + 1) + (A - 1) * c - sq;
                break;
            case HighShelf:
                b0 = A * ((A + 1) + (A - 1) * c + sq), b1 = -2 * A * ((A - 1) + (A + 1) * c);
                b2 = A * ((A + 1) + (A - 1) * c - sq);
                a0 = (A + 1) - (A - 1) * c + sq, a1 = 2 * ((A - 1) - (A + 1) * c), a2 = (A + 1) - (A - 1) * c - sq;
                break;
        }
        b0 /= a0, b1 /= a0, b2 /= a0, a1 /= a0, a2 /= a0;
    }
    void bypass() { b0 = 1, b1 = b2 = a1 = a2 = 0; }
    inline float tick(float x, int ch) {
        const float y = b0 * x + z1[ch];
        z1[ch] = b1 * x - a1 * y + z2[ch];
        z2[ch] = b2 * x - a2 * y;
        return y;
    }
};

struct OnePole {
    float a = 0, z[2] = {0, 0};
    void setLowPass(float freq) { a = std::exp(-2 * kPi * std::clamp(freq, 10.f, kSr * 0.45f) / kSr); }
    inline float lp(float x, int ch) { return z[ch] = x + (z[ch] - x) * a; }
    inline float hp(float x, int ch) { return x - lp(x, ch); }
};

struct Lfo {
    float phase = 0, inc = 0;
    void setRate(float hz) { inc = hz / kSr; }
    inline void advance() {
        phase += inc;
        if (phase >= 1) phase -= 1;
    }
    inline float sine(float offset) const { return std::sin(2 * kPi * (phase + offset)); }
};

// Delay line with linear interpolation, sized once at construction.
struct DelayLine {
    std::vector<float> buf;
    int w = 0;
    explicit DelayLine(int size) : buf((size_t)size, 0.f) {}
    inline void write(float x) {
        buf[(size_t)w] = x;
        if (++w >= (int)buf.size()) w = 0;
    }
    // Reads `delay` samples behind the write head; call before write() for the current sample.
    inline float read(float delay) const {
        const int size = (int)buf.size();
        delay = std::clamp(delay, 1.f, (float)size - 2);
        float pos = (float)w - delay;
        if (pos < 0) pos += (float)size;
        const int i = (int)pos;
        const float f = pos - (float)i;
        const int j = i + 1 >= size ? 0 : i + 1;
        return buf[(size_t)i] + (buf[(size_t)j] - buf[(size_t)i]) * f;
    }
};

// 0 hpFreq 1 lowGain 2 lowFreq 3 mid1Gain 4 mid1Freq 5 mid1Q 6 mid2Gain 7 mid2Freq 8 mid2Q
// 9 highGain 10 highFreq 11 lpFreq 12 mid3Gain 13 mid3Freq 14 mid3Q
class Eq : public Effect {
    Biquad band_[7];

public:
    void process(float* l, float* r, int n) override {
        if (changed()) {
            if (p(0) > 21) band_[0].set(Biquad::HighPass, p(0), 0.707f, 0); else band_[0].bypass();
            band_[1].set(Biquad::LowShelf, p(2), 0.707f, p(1));
            band_[2].set(Biquad::Peak, p(4), p(5), p(3));
            band_[3].set(Biquad::Peak, p(7), p(8), p(6));
            band_[4].set(Biquad::HighShelf, p(10), 0.707f, p(9));
            if (p(11) < 19900) band_[5].set(Biquad::LowPass, p(11), 0.707f, 0); else band_[5].bypass();
            band_[6].set(Biquad::Peak, p(13), p(14), p(12));
        }
        for (int i = 0; i < n; i++) {
            float a = l[i], b = r[i];
            for (auto& q : band_) a = q.tick(a, 0), b = q.tick(b, 1);
            l[i] = a, r[i] = b;
        }
    }
};

// 0 threshold 1 ratio 2 attack 3 release 4 knee 5 makeup 6 mix
class Compressor : public Effect {
    float env_ = 0;  // gain reduction in dB, <= 0

public:
    void process(float* l, float* r, int n) override {
        const float thr = p(0), slope = 1.f / std::max(p(1), 1.f) - 1.f, knee = std::max(p(4), 0.01f);
        const float atk = msCoef(p(2)), rel = msCoef(p(3)), makeup = p(5), mix = p(6) * 0.01f;
        for (int i = 0; i < n; i++) {
            const float over = linToDb(std::max(std::fabs(l[i]), std::fabs(r[i]))) - thr;
            float gr = 0;
            if (2 * over > knee) gr = slope * over;
            else if (2 * over > -knee) gr = slope * (over + knee / 2) * (over + knee / 2) / (2 * knee);
            const float c = gr < env_ ? atk : rel;
            env_ = gr + (env_ - gr) * c;
            const float g = 1 + (dbToLin(env_ + makeup) - 1) * mix;
            l[i] *= g, r[i] *= g;
        }
    }
};

// 0 ceiling 1 release 2 gain
class Limiter : public Effect {
    static constexpr int kLook = 240;  // 5 ms
    DelayLine dl_{kLook + 4}, dr_{kLook + 4};
    float env_ = 1;

public:
    void process(float* l, float* r, int n) override {
        const float ceil = dbToLin(p(0)), rel = msCoef(p(1)), gain = dbToLin(p(2));
        const float atk = std::exp(-1.f / (kLook / 5.f));
        for (int i = 0; i < n; i++) {
            const float a = l[i] * gain, b = r[i] * gain;
            const float peak = std::max(std::fabs(a), std::fabs(b));
            const float target = peak > ceil ? ceil / peak : 1.f;
            env_ = target + (env_ - target) * (target < env_ ? atk : rel);
            const float da = dl_.read(kLook), db = dr_.read(kLook);
            dl_.write(a), dr_.write(b);
            l[i] = std::clamp(da * env_, -ceil, ceil);
            r[i] = std::clamp(db * env_, -ceil, ceil);
        }
    }
};

// 0 threshold 1 attack 2 hold 3 release 4 range
class Gate : public Effect {
    float level_ = 0, gain_ = 0;
    int hold_ = 0;

public:
    void process(float* l, float* r, int n) override {
        const float thr = dbToLin(p(0)), atk = msCoef(p(1)), rel = msCoef(p(3)), floor = dbToLin(p(4));
        const int hold = (int)(p(2) * 0.001f * kSr);
        const float detRel = msCoef(20);
        for (int i = 0; i < n; i++) {
            const float in = std::max(std::fabs(l[i]), std::fabs(r[i]));
            level_ = in > level_ ? in : in + (level_ - in) * detRel;
            if (level_ > thr) hold_ = hold;
            else if (hold_ > 0) hold_--;
            const float target = (level_ > thr || hold_ > 0) ? 1.f : floor;
            gain_ = target + (gain_ - target) * (target > gain_ ? atk : rel);
            l[i] *= gain_, r[i] *= gain_;
        }
    }
};

// Schroeder/Moorer reverb: parallel damped combs into series allpasses.
// 0 size 1 decay 2 damping 3 predelay 4 lowcut 5 mix
class Reverb : public Effect {
    static constexpr int kCombs = 8, kAps = 4;
    struct Comb {
        std::vector<float> buf;
        int len = 1, i = 0;
        float fb = 0, filt = 0;
    };
    struct Ap {
        std::vector<float> buf;
        int len = 1, i = 0;
    };
    Comb comb_[2][kCombs];
    Ap ap_[2][kAps];
    DelayLine pre_{(int)(kSr * 0.21f)};
    Biquad lowcut_;
    float damp_ = 0.5f;

public:
    Reverb() {
        for (int c = 0; c < 2; c++) {
            for (auto& x : comb_[c]) x.buf.assign(4096, 0.f);
            for (auto& x : ap_[c]) x.buf.assign(1024, 0.f);
        }
    }
    void process(float* l, float* r, int n) override {
        if (changed()) {
            static const int combLen[kCombs] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
            static const int apLen[kAps] = {556, 441, 341, 225};
            const float scale = (kSr / 44100.f) * (0.6f + p(0) * 0.01f * 0.9f);
            const float decay = std::max(p(1), 0.1f);
            for (int c = 0; c < 2; c++) {
                for (int k = 0; k < kCombs; k++) {
                    Comb& x = comb_[c][k];
                    x.len = std::clamp((int)((combLen[k] + c * 23) * scale), 16, 4095);
                    x.i %= x.len;
                    x.fb = std::pow(10.f, -3.f * (float)x.len / (decay * kSr));
                }
                for (int k = 0; k < kAps; k++) {
                    Ap& x = ap_[c][k];
                    x.len = std::clamp((int)((apLen[k] + c * 23) * (kSr / 44100.f)), 8, 1023);
                    x.i %= x.len;
                }
            }
            damp_ = p(2) * 0.01f * 0.9f;
            lowcut_.set(Biquad::HighPass, p(4), 0.707f, 0);
        }
        const float pre = std::max(1.f, p(3) * 0.001f * kSr), mix = p(5) * 0.01f;
        for (int i = 0; i < n; i++) {
            const float in = pre_.read(pre);
            pre_.write((l[i] + r[i]) * 0.015f);
            float out[2];
            for (int c = 0; c < 2; c++) {
                float acc = 0;
                for (auto& x : comb_[c]) {
                    const float y = x.buf[(size_t)x.i];
                    x.filt = y + (x.filt - y) * damp_;
                    x.buf[(size_t)x.i] = in + x.filt * x.fb;
                    if (++x.i >= x.len) x.i = 0;
                    acc += y;
                }
                for (auto& x : ap_[c]) {
                    const float y = x.buf[(size_t)x.i];
                    x.buf[(size_t)x.i] = acc + y * 0.5f;
                    if (++x.i >= x.len) x.i = 0;
                    acc = y - acc;
                }
                out[c] = lowcut_.tick(acc, c);
            }
            l[i] = l[i] * (1 - mix) + out[0] * mix;
            r[i] = r[i] * (1 - mix) + out[1] * mix;
        }
    }
};

// 0 time 1 feedback 2 pingpong 3 lowcut 4 highcut 5 mix
class Delay : public Effect {
    DelayLine dl_{(int)(kSr * 2.1f)}, dr_{(int)(kSr * 2.1f)};
    OnePole lo_, hi_;
    float cur_ = 0;

public:
    void process(float* l, float* r, int n) override {
        if (changed()) lo_.setLowPass(p(3)), hi_.setLowPass(p(4));
        const float target = p(0) * 0.001f * kSr, fb = p(1) * 0.01f, mix = p(5) * 0.01f;
        const bool ping = p(2) > 0.5f;
        if (cur_ == 0) cur_ = target;
        for (int i = 0; i < n; i++) {
            cur_ += (target - cur_) * 0.0005f;  // glide so time changes do not click
            const float a = dl_.read(cur_), b = dr_.read(cur_);
            const float fa = hi_.lp(lo_.hp(a, 0), 0) * fb, fbk = hi_.lp(lo_.hp(b, 1), 1) * fb;
            if (ping) {
                dl_.write((l[i] + r[i]) * 0.5f + fbk);
                dr_.write(fa);
            } else {
                dl_.write(l[i] + fa);
                dr_.write(r[i] + fbk);
            }
            l[i] = l[i] * (1 - mix) + a * mix;
            r[i] = r[i] * (1 - mix) + b * mix;
        }
    }
};

// Chorus: 0 rate 1 depth 2 delay 3 mix.  Flanger: 0 rate 1 depth 2 feedback 3 mix.
class ModDelay : public Effect {
    const bool flanger_;
    DelayLine dl_{4096}, dr_{4096};
    Lfo lfo_;

public:
    explicit ModDelay(bool flanger) : flanger_(flanger) {}
    void process(float* l, float* r, int n) override {
        lfo_.setRate(p(0));
        const float depth = p(1) * 0.01f, mix = p(3) * 0.005f;
        const float fb = flanger_ ? p(2) * 0.01f : 0.f;
        const float base = (flanger_ ? 0.5f : p(2)) * 0.001f * kSr;
        const float swing = flanger_ ? depth * 4.f * 0.001f * kSr : depth * base * 0.6f;
        for (int i = 0; i < n; i++) {
            const float ma = flanger_ ? 0.5f + 0.5f * lfo_.sine(0) : lfo_.sine(0);
            const float mb = flanger_ ? 0.5f + 0.5f * lfo_.sine(0.25f) : lfo_.sine(0.25f);
            lfo_.advance();
            const float a = dl_.read(base + swing * ma), b = dr_.read(base + swing * mb);
            dl_.write(l[i] + a * fb), dr_.write(r[i] + b * fb);
            l[i] = l[i] * (1 - mix) + a * mix;
            r[i] = r[i] * (1 - mix) + b * mix;
        }
    }
};

// 0 rate 1 depth 2 feedback 3 center 4 mix
class Phaser : public Effect {
    static constexpr int kStages = 6;
    float z_[2][kStages] = {}, last_[2] = {0, 0};
    Lfo lfo_;

public:
    void process(float* l, float* r, int n) override {
        lfo_.setRate(p(0));
        const float depth = p(1) * 0.01f, fb = p(2) * 0.01f, center = p(3), mix = p(4) * 0.005f;
        float* io[2] = {l, r};
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < 2; c++) {
                const float f = std::min(center * std::pow(2.f, depth * 2.f * lfo_.sine(c * 0.25f)), kSr * 0.45f);
                const float t = std::tan(kPi * f / kSr), a = (t - 1) / (t + 1);
                float x = io[c][i] + last_[c] * fb;
                for (int s = 0; s < kStages; s++) {
                    const float y = a * x + z_[c][s];
                    z_[c][s] = x - a * y;
                    x = y;
                }
                last_[c] = x;
                io[c][i] = io[c][i] * (1 - mix) + x * mix;
            }
            lfo_.advance();
        }
    }
};

// State variable filter (trapezoidal). 0 type 1 cutoff 2 resonance
class Filter : public Effect {
    float ic1_[2] = {0, 0}, ic2_[2] = {0, 0};

public:
    void process(float* l, float* r, int n) override {
        const int type = (int)(p(0) + 0.5f);
        const float g = std::tan(kPi * std::clamp(p(1), 20.f, kSr * 0.45f) / kSr);
        const float k = 2.f - 1.96f * std::clamp(p(2) * 0.01f, 0.f, 1.f);
        const float a1 = 1 / (1 + g * (g + k)), a2 = g * a1, a3 = g * a2;
        float* io[2] = {l, r};
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < n; i++) {
                const float x = io[c][i], v3 = x - ic2_[c];
                const float v1 = a1 * ic1_[c] + a2 * v3, v2 = ic2_[c] + a2 * ic1_[c] + a3 * v3;
                ic1_[c] = 2 * v1 - ic1_[c], ic2_[c] = 2 * v2 - ic2_[c];
                io[c][i] = type == 0 ? v2 : type == 1 ? x - k * v1 - v2 : type == 2 ? v1 : x - k * v1;
            }
        }
    }
};

// 0 type 1 drive 2 tone 3 output 4 mix
class Distortion : public Effect {
    OnePole tone_;

public:
    void process(float* l, float* r, int n) override {
        if (changed()) tone_.setLowPass(p(2));
        const int type = (int)(p(0) + 0.5f);
        const float drive = dbToLin(p(1)), out = dbToLin(p(3)), mix = p(4) * 0.01f;
        float* io[2] = {l, r};
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < n; i++) {
                const float x = io[c][i] * drive;
                float y;
                if (type == 0) y = std::tanh(x);
                else if (type == 1) y = std::clamp(x, -1.f, 1.f);
                else y = x >= 0 ? 1 - std::exp(-2 * x) : -0.7f * (1 - std::exp(3 * x));
                y = tone_.lp(y, c) * out;
                io[c][i] = io[c][i] * (1 - mix) + y * mix;
            }
        }
    }
};

// 0 width
class Width : public Effect {
public:
    void process(float* l, float* r, int n) override {
        const float w = p(0) * 0.01f;
        for (int i = 0; i < n; i++) {
            const float m = (l[i] + r[i]) * 0.5f, s = (l[i] - r[i]) * 0.5f * w;
            l[i] = m + s, r[i] = m - s;
        }
    }
};

// Splits off the band above `freq` and turns it down while it is loud.
// 0 freq 1 threshold 2 range
class DeEsser : public Effect {
    Biquad split_;
    float env_ = 0;

public:
    void process(float* l, float* r, int n) override {
        if (changed()) split_.set(Biquad::HighPass, p(0), 0.707f, 0);
        const float thr = p(1), range = p(2), atk = msCoef(0.5f), rel = msCoef(60);
        for (int i = 0; i < n; i++) {
            const float ha = split_.tick(l[i], 0), hb = split_.tick(r[i], 1);
            const float in = std::max(std::fabs(ha), std::fabs(hb));
            env_ = in + (env_ - in) * (in > env_ ? atk : rel);
            const float over = linToDb(env_) - thr;
            const float g = over > 0 ? dbToLin(-std::min(range, over * 0.75f)) : 1.f;
            l[i] += ha * (g - 1), r[i] += hb * (g - 1);
        }
    }
};

}  // namespace

std::shared_ptr<Effect> createEffect(const std::string& type) {
    if (type == "eq") return std::make_shared<Eq>();
    if (type == "comp") return std::make_shared<Compressor>();
    if (type == "limiter") return std::make_shared<Limiter>();
    if (type == "gate") return std::make_shared<Gate>();
    if (type == "reverb") return std::make_shared<Reverb>();
    if (type == "delay") return std::make_shared<Delay>();
    if (type == "chorus") return std::make_shared<ModDelay>(false);
    if (type == "flanger") return std::make_shared<ModDelay>(true);
    if (type == "phaser") return std::make_shared<Phaser>();
    if (type == "filter") return std::make_shared<Filter>();
    if (type == "dist") return std::make_shared<Distortion>();
    if (type == "width") return std::make_shared<Width>();
    if (type == "deesser") return std::make_shared<DeEsser>();
    return nullptr;
}

}  // namespace vd
