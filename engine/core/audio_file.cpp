#include "audio_file.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace vd {

AudioFile::~AudioFile() {
    if (map_) munmap(map_, mapLen_);
}

std::shared_ptr<AudioFile> AudioFile::open(const std::string& path) {
    int fd = ::open(path.c_str(), O_RDONLY);
    if (fd < 0) return nullptr;
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size < 44) {
        ::close(fd);
        return nullptr;
    }
    void* map = mmap(nullptr, (size_t)st.st_size, PROT_READ, MAP_SHARED, fd, 0);
    ::close(fd);
    if (map == MAP_FAILED) return nullptr;

    auto file = std::make_shared<AudioFile>();
    file->map_ = map;
    file->mapLen_ = (size_t)st.st_size;
    const uint8_t* b = (const uint8_t*)map;
    const size_t size = (size_t)st.st_size;
    if (memcmp(b, "RIFF", 4) != 0 || memcmp(b + 8, "WAVE", 4) != 0) return nullptr;

    uint16_t format = 0, channels = 0, bits = 0;
    size_t at = 12;
    while (at + 8 <= size) {
        uint32_t len;
        memcpy(&len, b + at + 4, 4);
        const size_t body = at + 8;
        if (memcmp(b + at, "fmt ", 4) == 0 && body + 16 <= size) {
            memcpy(&format, b + body, 2);
            memcpy(&channels, b + body + 2, 2);
            memcpy(&bits, b + body + 14, 2);
        } else if (memcmp(b + at, "data", 4) == 0) {
            if (format != 3 || bits != 32 || channels < 1 || channels > 2 || body % 4 != 0) return nullptr;
            // The header length field is stale if a recording was cut short; trust the file size.
            size_t bytes = std::min<size_t>(len ? len : size - body, size - body);
            file->data = (const float*)(b + body);
            file->channels = channels;
            file->frames = (int64_t)(bytes / (4 * channels));
            return file;
        }
        at = body + len + (len & 1);
    }
    return nullptr;
}

bool WavWriter::open(const std::string& path, int channels, int bits, int rate) {
    close();
    f_ = fopen(path.c_str(), "wb");
    if (!f_) return false;
    channels_ = channels;
    bits_ = bits;
    frames_ = 0;
    uint8_t h[44] = {};
    memcpy(h, "RIFF", 4);
    memcpy(h + 8, "WAVEfmt ", 8);
    uint32_t u32 = 16;
    memcpy(h + 16, &u32, 4);
    uint16_t u16 = bits == 32 ? 3 : 1;
    memcpy(h + 20, &u16, 2);
    u16 = (uint16_t)channels;
    memcpy(h + 22, &u16, 2);
    u32 = (uint32_t)rate;
    memcpy(h + 24, &u32, 4);
    u32 = (uint32_t)(rate * channels * bits / 8);
    memcpy(h + 28, &u32, 4);
    u16 = (uint16_t)(channels * bits / 8);
    memcpy(h + 32, &u16, 2);
    u16 = (uint16_t)bits;
    memcpy(h + 34, &u16, 2);
    memcpy(h + 36, "data", 4);
    fwrite(h, 1, 44, f_);
    return true;
}

void WavWriter::write(const float* x, int64_t frames) {
    if (!f_ || frames <= 0) return;
    const size_t n = (size_t)(frames * channels_);
    if (bits_ == 32) {
        fwrite(x, 4, n, f_);
    } else {
        const int bytes = bits_ / 8;
        scratch_.resize(n * bytes);
        const float scale = bits_ == 16 ? 32767.f : 8388607.f;
        for (size_t i = 0; i < n; i++) {
            // Triangular dither of one LSB hides quantisation distortion.
            rng_ = rng_ * 1664525u + 1013904223u;
            float d = (float)(rng_ >> 8) / 16777216.f;
            rng_ = rng_ * 1664525u + 1013904223u;
            d -= (float)(rng_ >> 8) / 16777216.f;
            float v = std::clamp(x[i], -1.f, 1.f) * scale + d;
            int32_t s = (int32_t)std::lrintf(std::clamp(v, -scale, scale));
            for (int k = 0; k < bytes; k++) scratch_[i * bytes + k] = (uint8_t)(s >> (8 * k));
        }
        fwrite(scratch_.data(), 1, scratch_.size(), f_);
    }
    frames_ += frames;
}

void WavWriter::close() {
    if (!f_) return;
    uint32_t dataBytes = (uint32_t)(frames_ * channels_ * bits_ / 8);
    uint32_t riff = dataBytes + 36;
    fseek(f_, 4, SEEK_SET);
    fwrite(&riff, 4, 1, f_);
    fseek(f_, 40, SEEK_SET);
    fwrite(&dataBytes, 4, 1, f_);
    fclose(f_);
    f_ = nullptr;
}

std::vector<float> computePeaks(const std::string& path, int framesPerPeak) {
    std::vector<float> peaks;
    auto file = AudioFile::open(path);
    if (!file || framesPerPeak < 1) return peaks;
    peaks.reserve((size_t)(file->frames / framesPerPeak + 1));
    const int ch = file->channels;
    for (int64_t at = 0; at < file->frames; at += framesPerPeak) {
        const int64_t end = std::min(file->frames, at + framesPerPeak);
        float peak = 0;
        for (int64_t i = at * ch; i < end * ch; i++) peak = std::max(peak, std::fabs(file->data[i]));
        peaks.push_back(peak);
    }
    return peaks;
}

// ---- time-stretch and pitch-shift ----

int64_t stretchFile(const std::string& src, const std::string& dst, double speed, double semitones) {
    auto in = AudioFile::open(src);
    if (!in || in->frames < 1 || speed < 0.05 || speed > 20) return 0;
    const int ch = in->channels;
    const int64_t frames = in->frames;
    const double ratio = std::pow(2.0, semitones / 12.0);  // resampling step that produces the pitch
    const double alpha = ratio / speed;                    // length change WSOLA has to deliver first

    // 1. WSOLA: overlap-add Hann frames, each taken where it best continues the previous one.
    std::vector<float> mid;
    int64_t midFrames = frames;
    const int N = 2048, Hs = N / 2, S = 480;
    if (std::fabs(alpha - 1.0) < 1e-4 || frames < N * 2) {
        mid.assign(in->data, in->data + frames * ch);
    } else {
        std::vector<float> mono((size_t)frames);
        for (int64_t i = 0; i < frames; i++) {
            float v = 0;
            for (int c = 0; c < ch; c++) v += in->data[i * ch + c];
            mono[(size_t)i] = v;
        }
        std::vector<float> window(N);
        for (int i = 0; i < N; i++) window[i] = 0.5f - 0.5f * std::cos(6.2831853f * (float)i / (float)N);
        midFrames = (int64_t)((double)frames * alpha);
        mid.assign((size_t)((midFrames + N) * ch), 0.f);
        int64_t prev = 0;
        for (int64_t o = 0; o < midFrames; o += Hs) {
            const int64_t ideal = (int64_t)((double)o / alpha);
            int64_t best = ideal;
            const int64_t target = prev + Hs;
            if (o > 0 && target + Hs < frames) {
                float bestScore = -1e30f;
                for (int d = -S; d <= S; d += 2) {
                    const int64_t at = ideal + d;
                    if (at < 0 || at + Hs >= frames) continue;
                    float score = 0;
                    for (int k = 0; k < Hs; k += 4) score += mono[(size_t)(at + k)] * mono[(size_t)(target + k)];
                    if (score > bestScore) bestScore = score, best = at;
                }
            }
            best = std::clamp<int64_t>(best, 0, frames - 1);
            for (int k = 0; k < N; k++) {
                const int64_t s = best + k;
                if (s >= frames) break;
                // The first frame has nothing to overlap with, so its rising half stays at full level.
                const float w = (o == 0 && k < Hs) ? 1.f : window[k];
                for (int c = 0; c < ch; c++) mid[(size_t)((o + k) * ch + c)] += in->data[s * ch + c] * w;
            }
            prev = best;
        }
    }

    // 2. Resample by the pitch ratio with a windowed sinc, low-passed when shifting up.
    WavWriter out;
    if (!out.open(dst, ch, 32)) return 0;
    if (std::fabs(ratio - 1.0) < 1e-6) {
        out.write(mid.data(), midFrames);
    } else {
        const int half = 16, phases = 256;
        const double cutoff = std::min(1.0, 1.0 / ratio);
        std::vector<float> table((size_t)((phases + 1) * half * 2));
        for (int p = 0; p <= phases; p++) {
            for (int t = 0; t < half * 2; t++) {
                const double x = (double)(t - half + 1) - (double)p / phases;
                const double sinc = std::fabs(x) < 1e-9 ? 1.0 : std::sin(3.14159265358979 * x * cutoff) / (3.14159265358979 * x * cutoff);
                const double win = 0.5 + 0.5 * std::cos(3.14159265358979 * x / half);
                table[(size_t)(p * half * 2 + t)] = (float)(sinc * cutoff * (std::fabs(x) < half ? win : 0.0));
            }
        }
        const int64_t outFrames = (int64_t)((double)midFrames / ratio);
        std::vector<float> block((size_t)(4096 * ch));
        for (int64_t at = 0; at < outFrames; at += 4096) {
            const int n = (int)std::min<int64_t>(4096, outFrames - at);
            for (int i = 0; i < n; i++) {
                const double pos = (double)(at + i) * ratio;
                const int64_t base = (int64_t)pos;
                const float* taps = &table[(size_t)((int)((pos - (double)base) * phases) * half * 2)];
                for (int c = 0; c < ch; c++) {
                    float acc = 0;
                    for (int t = 0; t < half * 2; t++) {
                        const int64_t s = base + t - half + 1;
                        if (s >= 0 && s < midFrames) acc += mid[(size_t)(s * ch + c)] * taps[t];
                    }
                    block[(size_t)(i * ch + c)] = acc;
                }
            }
            out.write(block.data(), n);
        }
    }
    const int64_t written = out.frames();
    out.close();
    return written;
}

// ---- Importer ----

namespace {
constexpr int kPhases = 512;
constexpr int kBaseHalf = 16;
}  // namespace

bool Importer::begin(const std::string& path, int srcRate, int channels) {
    if (srcRate < 4000 || channels < 1) return false;
    srcCh_ = channels;
    ch_ = std::min(channels, 2);
    srcRate_ = srcRate;
    step_ = (double)srcRate / kSampleRate;
    // When downsampling, lower the cutoff and widen the kernel to keep aliasing out.
    cutoff_ = std::min(1.0, 1.0 / step_) * 0.96;
    half_ = (int)std::ceil(kBaseHalf / cutoff_);
    pos_ = 0;
    in_.assign(ch_, std::vector<float>(half_ - 1, 0.f));  // leading silence centres the kernel
    table_.resize((size_t)(kPhases + 1) * (2 * half_));
    for (int p = 0; p <= kPhases; p++) {
        const double frac = (double)p / kPhases;
        double sum = 0;
        float* row = &table_[(size_t)p * 2 * half_];
        for (int t = 0; t < 2 * half_; t++) {
            const double x = (t - half_ + 1) - frac;
            const double w = 0.5 + 0.5 * std::cos(M_PI * x / half_);  // Hann window
            const double s = std::fabs(x) < 1e-9 ? 1.0 : std::sin(M_PI * x * cutoff_) / (M_PI * x * cutoff_);
            row[t] = (float)(s * w * cutoff_);
            sum += row[t];
        }
        for (int t = 0; t < 2 * half_; t++) row[t] = (float)(row[t] / sum);
    }
    return out_.open(path, ch_, 32);
}

void Importer::write(const int16_t* x, int64_t frames) {
    const int srcCh = srcCh_;
    for (int c = 0; c < ch_; c++) {
        auto& v = in_[c];
        const size_t base = v.size();
        v.resize(base + (size_t)frames);
        for (int64_t i = 0; i < frames; i++) v[base + i] = x[i * srcCh + c] / 32768.f;
    }
    drain(false);
}

void Importer::drain(bool flush) {
    if (flush)
        for (auto& v : in_) v.resize(v.size() + half_, 0.f);
    const int64_t avail = (int64_t)in_[0].size();
    block_.clear();
    while (true) {
        const int64_t i0 = (int64_t)std::floor(pos_);
        if (i0 + 2 * half_ > avail) break;
        const double frac = pos_ - i0;
        const double ph = frac * kPhases;
        const int p = (int)ph;
        const float mix = (float)(ph - p);
        const float* a = &table_[(size_t)p * 2 * half_];
        const float* b = a + 2 * half_;
        for (int c = 0; c < ch_; c++) {
            const float* s = &in_[c][(size_t)i0];
            float acc = 0;
            for (int t = 0; t < 2 * half_; t++) acc += s[t] * (a[t] + (b[t] - a[t]) * mix);
            block_.push_back(acc);
        }
        pos_ += step_;
    }
    if (!block_.empty()) out_.write(block_.data(), (int64_t)(block_.size() / ch_));
    const int64_t drop = (int64_t)std::floor(pos_);
    if (drop > 0) {
        for (auto& v : in_) v.erase(v.begin(), v.begin() + std::min<int64_t>(drop, (int64_t)v.size()));
        pos_ -= drop;
    }
}

int64_t Importer::end() {
    drain(true);
    const int64_t frames = out_.frames();
    out_.close();
    return frames;
}

}  // namespace vd
