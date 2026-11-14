// Audio file access: memory-mapped float32 WAV reading, WAV writing, peak extraction
// and an import path that converts PCM16 at any rate to the engine format.
#pragma once
#include <cstdint>
#include <cstdio>
#include <memory>
#include <string>
#include <vector>

namespace vd {

constexpr int kSampleRate = 48000;

// A float32 WAV mapped read-only. Every audio asset of a project is stored in this format
// so the audio thread can read it without decoding.
struct AudioFile {
    const float* data = nullptr;
    int64_t frames = 0;
    int channels = 0;

    ~AudioFile();
    static std::shared_ptr<AudioFile> open(const std::string& path);

private:
    void* map_ = nullptr;
    size_t mapLen_ = 0;
};

// bits: 32 = float, 16 or 24 = integer PCM.
class WavWriter {
public:
    ~WavWriter() { close(); }
    bool open(const std::string& path, int channels, int bits, int rate = kSampleRate);
    void write(const float* interleaved, int64_t frames);
    int64_t frames() const { return frames_; }
    void close();

private:
    FILE* f_ = nullptr;
    int channels_ = 0, bits_ = 0;
    int64_t frames_ = 0;
    uint32_t rng_ = 22222;
    std::vector<uint8_t> scratch_;
};

// Max absolute value per bucket of framesPerPeak frames, channels mixed.
std::vector<float> computePeaks(const std::string& path, int framesPerPeak);

// Streaming PCM16 -> float32 WAV at kSampleRate using a windowed-sinc resampler.
class Importer {
public:
    bool begin(const std::string& path, int srcRate, int channels);
    void write(const int16_t* interleaved, int64_t frames);
    int64_t end();

private:
    void drain(bool flush);
    WavWriter out_;
    int ch_ = 0, srcCh_ = 0, srcRate_ = 0, half_ = 0;
    double pos_ = 0, step_ = 1, cutoff_ = 1;
    std::vector<std::vector<float>> in_;
    std::vector<float> table_, block_;
};

}  // namespace vd
