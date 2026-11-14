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
