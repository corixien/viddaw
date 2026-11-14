// Insert effects. Parameters are plain floats in native units, written by the control
// thread and read by the audio thread. Indices must match EffectSpecs in the Kotlin model.
#pragma once
#include <atomic>
#include <cmath>
#include <memory>
#include <string>

namespace vd {

class Effect {
public:
    static constexpr int kMaxParams = 16;
    std::atomic<float> params[kMaxParams];
    std::atomic<bool> enabled{true};

    Effect() {
        for (auto& p : params) p.store(0.f);
        for (auto& l : last_) l = NAN;
    }
    virtual ~Effect() = default;
    virtual void process(float* l, float* r, int n) = 0;

protected:
    float p(int i) const { return params[i].load(std::memory_order_relaxed); }
    // True when a parameter changed since the previous call, so coefficients need updating.
    bool changed() {
        bool any = false;
        for (int i = 0; i < kMaxParams; i++) {
            const float v = p(i);
            if (v != last_[i]) {
                last_[i] = v;
                any = true;
            }
        }
        return any;
    }

private:
    float last_[kMaxParams];
};

// Returns nullptr for an unknown type.
std::shared_ptr<Effect> createEffect(const std::string& type);

inline float dbToLin(float db) { return std::pow(10.f, db * 0.05f); }

}  // namespace vd
