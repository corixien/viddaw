// Sample-based instruments driven by MIDI notes: a pitched sampler and a 16-pad drum rack.
#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace vd {

struct Sample {
    std::vector<float> data;  // mono, kSampleRate
};

std::shared_ptr<Sample> loadSample(const std::string& path);
std::shared_ptr<Sample> builtinTone();        // default sampler sound, pitched at C4
std::shared_ptr<Sample> builtinDrum(int pad); // synthesised kit, pad 0..15

constexpr int kPads = 16;
constexpr int kFirstPadNote = 36;

// Immutable per snapshot; the audio thread reads it while rendering.
struct InstrumentConfig {
    bool drums = false;
    // Sampler
    std::shared_ptr<Sample> sample;
    int root = 60;
    float tune = 0, attackMs = 2, releaseMs = 200, gain = 1;
    // Drum rack
    struct Pad {
        std::shared_ptr<Sample> sample;
        float gain = 1, pan = 0, tune = 0;
    } pads[kPads];
};

// Holds the playing voices. Audio thread only.
class Instrument {
public:
    // `delay` is the offset in frames into the next rendered block.
    void noteOn(const InstrumentConfig& cfg, int pitch, int velocity, int delay);
    void noteOff(int pitch, int delay);
    void allOff();
    void render(const InstrumentConfig& cfg, float* l, float* r, int n);  // adds to l/r

private:
    struct Voice {
        const Sample* sample = nullptr;
        double pos = 0, rate = 1;
        float gainL = 0, gainR = 0, env = 0, attackInc = 1, releaseCoef = 0;
        int pitch = 0, pad = -1, delay = 0, offDelay = -1;
        bool active = false, releasing = false;
    };
    static constexpr int kVoices = 24;
    Voice voices_[kVoices];
    uint32_t age_[kVoices] = {};
    uint32_t clock_ = 0;
};

}  // namespace vd
