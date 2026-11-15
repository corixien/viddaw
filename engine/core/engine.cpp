#include "engine.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <set>
#include <sstream>

#if defined(__aarch64__)
#elif defined(__x86_64__) || defined(__i386__)
#include <xmmintrin.h>
#endif

namespace vd {
namespace {

constexpr int kFade = 96;  // shortest fade at clip edges, removes clicks at cuts
constexpr uint32_t kRingSize = 1u << 18;

// Subnormal floats in filter tails cost orders of magnitude more CPU; flush them to zero.
inline void flushDenormals() {
#if defined(__aarch64__)
    uint64_t fpcr;
    __asm__ __volatile__("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1u << 24);
    __asm__ __volatile__("msr fpcr, %0" : : "r"(fpcr));
#elif defined(__x86_64__) || defined(__i386__)
    _mm_setcsr(_mm_getcsr() | 0x8040);
#endif
}

inline void raisePeak(std::atomic<float>& peak, float v) {
    if (v > peak.load(std::memory_order_relaxed)) peak.store(v, std::memory_order_relaxed);
}

}  // namespace

Engine::Engine() : current_(new Snapshot()), ring_(kRingSize, 0.f) {
    retired_.reserve(64);
    masterPeak_[0] = 0.f, masterPeak_[1] = 0.f;
    builtinTone_ = builtinTone();
    for (int i = 0; i < kPads; i++) builtinDrums_[i] = builtinDrum(i);
}

Engine::~Engine() {
    int64_t unused;
    stopRecording(&unused);
    delete current_;
    delete pending_;
    for (auto* s : retired_) delete s;
}

void Engine::sync(const std::string& text) {
    auto snap = std::make_unique<Snapshot>();
    std::set<int64_t> liveTracks, liveEffects;
    std::vector<std::shared_ptr<TrackRt>> order;

    auto file = [&](const std::string& path) {
        auto it = files_.find(path);
        if (it != files_.end())
            if (auto f = it->second.lock()) return f;
        auto f = AudioFile::open(path);
        if (f) files_[path] = f;
        return f;
    };
    auto sample = [&](const std::string& path) {
        auto it = samples_.find(path);
        if (it != samples_.end())
            if (auto s = it->second.lock()) return s;
        auto s = loadSample(path);
        if (s) samples_[path] = s;
        return s;
    };
    auto restOfLine = [](std::istringstream& in) {
        std::string s;
        std::getline(in >> std::ws, s);
        while (!s.empty() && (s.back() == '\r' || s.back() == '\n')) s.pop_back();
        return s;
    };

    std::istringstream all(text);
    std::string line;
    Track* track = nullptr;
    while (std::getline(all, line)) {
        if (line.empty()) continue;
        std::istringstream in(line);
        char tag;
        in >> tag;
        if (tag == 'P') {
            int loop, metro;
            in >> snap->framesPerBeat >> snap->beatsPerBar >> loop >> snap->loopStart >> snap->loopEnd >> snap->master >> metro;
            snap->loop = loop != 0, snap->metronome = metro != 0;
            snap->framesPerBeat = std::max(snap->framesPerBeat, 100.0);
            snap->beatsPerBar = std::max(snap->beatsPerBar, 1);
        } else if (tag == 'T') {
            snap->tracks.emplace_back();
            track = &snap->tracks.back();
            int audible;
            in >> track->id >> track->type >> track->gain >> track->pan >> audible;
            track->audible = audible != 0;
            auto& rt = trackRt_[track->id];
            if (!rt) rt = std::make_shared<TrackRt>();
            track->rt = rt;
            liveTracks.insert(track->id);
            order.push_back(rt);
            if (track->type == 1) {
                track->instrument.sample = builtinTone_;
            } else if (track->type == 2) {
                track->instrument.drums = true;
                for (int i = 0; i < kPads; i++) track->instrument.pads[i].sample = builtinDrums_[i];
            }
        } else if (!track) {
            continue;
        } else if (tag == 'C') {
            Clip c{};
            in >> c.start >> c.offset >> c.frames >> c.gain >> c.fadeIn >> c.fadeOut;
            c.fadeIn = std::max<int64_t>(c.fadeIn, kFade), c.fadeOut = std::max<int64_t>(c.fadeOut, kFade);
            c.file = file(restOfLine(in));
            if (c.file && c.frames > 0) track->clips.push_back(std::move(c));
        } else if (tag == 'N') {
            int64_t start, length;
            int pitch, velocity;
            in >> start >> length >> pitch >> velocity;
            track->events.push_back({start, (uint8_t)pitch, (uint8_t)velocity, true});
            track->events.push_back({start + std::max<int64_t>(length, 1), (uint8_t)pitch, 0, false});
        } else if (tag == 'E') {
            int64_t id;
            std::string type;
            int enabled, count;
            in >> id >> type >> enabled >> count;
            auto& slot = effects_[id];
            if (!slot.second || slot.first != type) slot = {type, createEffect(type)};
            if (!slot.second) continue;
            for (int i = 0; i < count && i < Effect::kMaxParams; i++) {
                float v;
                in >> v;
                slot.second->params[i].store(v);
            }
            slot.second->enabled.store(enabled != 0);
            track->effects.push_back(slot.second);
            liveEffects.insert(id);
        } else if (tag == 'S') {
            auto& ic = track->instrument;
            in >> ic.root >> ic.tune >> ic.attackMs >> ic.releaseMs >> ic.gain;
            const std::string path = restOfLine(in);
            if (path != "-")
                if (auto s = sample(path)) ic.sample = s;
        } else if (tag == 'D') {
            int pad;
            in >> pad;
            if (pad < 0 || pad >= kPads) continue;
            auto& p = track->instrument.pads[pad];
            in >> p.gain >> p.pan >> p.tune;
            const std::string path = restOfLine(in);
            if (path != "-")
                if (auto s = sample(path)) p.sample = s;
        }
    }
    for (auto& t : snap->tracks)
        std::stable_sort(t.events.begin(), t.events.end(), [](const MidiEvent& a, const MidiEvent& b) {
            return a.frame != b.frame ? a.frame < b.frame : a.on < b.on;
        });

    std::erase_if(trackRt_, [&](const auto& e) { return !liveTracks.count(e.first); });
    std::erase_if(effects_, [&](const auto& e) { return !liveEffects.count(e.first); });
    std::erase_if(files_, [](const auto& e) { return e.second.expired(); });
    std::erase_if(samples_, [](const auto& e) { return e.second.expired(); });
    {
        std::lock_guard<std::mutex> lock(meterMutex_);
        meterOrder_ = std::move(order);
    }

    // Hand over; snapshots the audio thread no longer uses are freed here, off the audio thread.
    std::vector<Snapshot*> garbage;
    {
        std::lock_guard<std::mutex> lock(swapMutex_);
        if (pending_) garbage.push_back(pending_);
        pending_ = snap.release();
        garbage.insert(garbage.end(), retired_.begin(), retired_.end());
        retired_.clear();
    }
    for (auto* s : garbage) delete s;
}

void Engine::play() { playing_.store(true); }
void Engine::pause() { playing_.store(false); }
void Engine::seek(int64_t frame) { seekRequest_.store(std::max<int64_t>(frame, 0)); }

int64_t Engine::position() const {
    const int64_t s = seekRequest_.load();
    return s >= 0 ? s : pos_.load();
}

void Engine::pushLive(const LiveNote& note) {
    std::lock_guard<std::mutex> lock(liveMutex_);
    const uint32_t w = liveWrite_.load();
    if (w - liveRead_.load() >= 256) return;
    live_[w & 255] = note;
    liveWrite_.store(w + 1);
}

void Engine::noteOn(int64_t track, int pitch, int velocity) {
    pushLive({track, (uint8_t)std::clamp(pitch, 0, 127), (uint8_t)std::clamp(velocity, 1, 127), true});
}

void Engine::noteOff(int64_t track, int pitch) {
    pushLive({track, (uint8_t)std::clamp(pitch, 0, 127), 0, false});
}

int Engine::meters(float* out, int max) {
    if (max < 2) return 0;
    out[0] = masterPeak_[0].exchange(0.f);
    out[1] = masterPeak_[1].exchange(0.f);
    std::lock_guard<std::mutex> lock(meterMutex_);
    int n = 2;
    for (auto& rt : meterOrder_) {
        if (n >= max) break;
        out[n++] = rt->peak.exchange(0.f);
    }
    return n;
}

void Engine::allNotesOff() {
    for (auto& t : current_->tracks) t.rt->instrument.allOff();
}

void Engine::render(float* out, int frames) {
    flushDenormals();
    if (swapMutex_.try_lock()) {
        if (pending_ && retired_.size() < retired_.capacity()) {
            retired_.push_back(current_);
            current_ = pending_;
            pending_ = nullptr;
        }
        swapMutex_.unlock();
    }
    const int64_t seek = seekRequest_.exchange(-1);
    if (seek >= 0) {
        pos_.store(seek);
        allNotesOff();
    }
    const bool playing = playing_.load();
    if (wasPlaying_ && !playing) allNotesOff();
    wasPlaying_ = playing;

    uint32_t r = liveRead_.load();
    const uint32_t w = liveWrite_.load();
    for (; r != w; r++) {
        const LiveNote& note = live_[r & 255];
        for (auto& t : current_->tracks) {
            if (t.id != note.track || t.type == 0) continue;
            if (note.on) t.rt->instrument.noteOn(t.instrument, note.pitch, note.velocity, 0);
            else t.rt->instrument.noteOff(note.pitch, 0);
        }
    }
    liveRead_.store(r);

    const Snapshot& s = *current_;
    const bool loop = playing && !offline_ && s.loop && s.loopEnd > s.loopStart;
    int done = 0;
    while (done < frames) {
        int n = std::min(frames - done, kMaxBlock);
        int64_t pos = pos_.load(std::memory_order_relaxed);
        if (loop && pos < s.loopEnd) n = (int)std::min<int64_t>(n, s.loopEnd - pos);
        renderChunk(out + done * 2, n, playing);
        if (playing) {
            pos += n;
            if (loop && pos == s.loopEnd) {
                pos = s.loopStart;
                allNotesOff();
            }
            pos_.store(pos, std::memory_order_relaxed);
        }
        done += n;
    }
}

void Engine::renderTrack(Track& t, int n, bool playing) {
    std::memset(bufL_, 0, sizeof(float) * (size_t)n);
    std::memset(bufR_, 0, sizeof(float) * (size_t)n);
    const int64_t pos = pos_.load(std::memory_order_relaxed);
    TrackRt& rt = *t.rt;

    if (playing) {
        for (const Clip& c : t.clips) {
            const int64_t a = std::max(pos, c.start), b = std::min(pos + n, c.start + c.frames);
            const AudioFile& f = *c.file;
            for (int64_t frame = a; frame < b; frame++) {
                const int64_t k = frame - c.start, src = c.offset + k;
                if (src >= f.frames) break;
                if (src < 0) continue;
                const float fade = std::min(1.f, std::min((float)(k + 1) / (float)c.fadeIn, (float)(c.frames - k) / (float)c.fadeOut)) * c.gain;
                const int i = (int)(frame - pos);
                if (f.channels == 1) {
                    const float v = f.data[src] * fade;
                    bufL_[i] += v, bufR_[i] += v;
                } else {
                    bufL_[i] += f.data[src * 2] * fade, bufR_[i] += f.data[src * 2 + 1] * fade;
                }
            }
        }
        if (t.type != 0) {
            auto it = std::lower_bound(t.events.begin(), t.events.end(), pos,
                                       [](const MidiEvent& e, int64_t frame) { return e.frame < frame; });
            for (; it != t.events.end() && it->frame < pos + n; ++it) {
                const int delay = (int)(it->frame - pos);
                if (it->on) rt.instrument.noteOn(t.instrument, it->pitch, it->velocity, delay);
                else rt.instrument.noteOff(it->pitch, delay);
            }
        }
    }
    if (t.type != 0) rt.instrument.render(t.instrument, bufL_, bufR_, n);

    for (auto& fx : t.effects)
        if (fx->enabled.load(std::memory_order_relaxed)) fx->process(bufL_, bufR_, n);

    const float gain = t.audible ? t.gain : 0.f;
    const float targetL = gain * std::min(1.f, 1.f - t.pan), targetR = gain * std::min(1.f, 1.f + t.pan);
    if (!rt.primed) rt.gainL = targetL, rt.gainR = targetR, rt.primed = true;
    const float stepL = (targetL - rt.gainL) / (float)n, stepR = (targetR - rt.gainR) / (float)n;
    float peak = 0;
    for (int i = 0; i < n; i++) {
        rt.gainL += stepL, rt.gainR += stepR;
        const float l = bufL_[i] * rt.gainL, r = bufR_[i] * rt.gainR;
        mixL_[i] += l, mixR_[i] += r;
        peak = std::max(peak, std::max(std::fabs(l), std::fabs(r)));
    }
    rt.gainL = targetL, rt.gainR = targetR;
    raisePeak(rt.peak, peak);
}

void Engine::renderChunk(float* out, int n, bool playing) {
    Snapshot& s = *current_;
    std::memset(mixL_, 0, sizeof(float) * (size_t)n);
    std::memset(mixR_, 0, sizeof(float) * (size_t)n);
    for (auto& t : s.tracks) renderTrack(t, n, playing);

    if (!offline_ && (clickEnv_ > 1e-3f || (playing && s.metronome))) {
        const int64_t pos = pos_.load(std::memory_order_relaxed);
        int64_t beat = (int64_t)std::ceil((double)pos / s.framesPerBeat - 1e-9);
        int64_t next = playing && s.metronome ? (int64_t)std::llround((double)beat * s.framesPerBeat) : INT64_MAX;
        const float decay = std::exp(-1.f / (0.018f * kSampleRate));
        for (int i = 0; i < n; i++) {
            if (pos + i == next) {
                clickEnv_ = 1, clickPhase_ = 0;
                clickFreq_ = beat % s.beatsPerBar == 0 ? 1760.f : 1175.f;
                next = (int64_t)std::llround((double)(++beat) * s.framesPerBeat);
            }
            if (clickEnv_ > 1e-3f) {
                const float v = std::sin(clickPhase_) * clickEnv_ * 0.3f;
                clickPhase_ += 6.2831853f * clickFreq_ / kSampleRate;
                clickEnv_ *= decay;
                mixL_[i] += v, mixR_[i] += v;
            }
        }
    }

    float peakL = 0, peakR = 0;
    for (int i = 0; i < n; i++) {
        const float l = mixL_[i] * s.master, r = mixR_[i] * s.master;
        peakL = std::max(peakL, std::fabs(l)), peakR = std::max(peakR, std::fabs(r));
        out[i * 2] = std::clamp(l, -1.f, 1.f), out[i * 2 + 1] = std::clamp(r, -1.f, 1.f);
    }
    raisePeak(masterPeak_[0], peakL);
    raisePeak(masterPeak_[1], peakR);
}

// ---- recording ----

bool Engine::startRecording(const std::string& path) {
    if (recording_.load() || recordThread_.joinable()) return false;
    if (!recordFile_.open(path, 1, 32)) return false;
    ringWrite_.store(0), ringRead_.store(0);
    recordStart_.store(-1);
    {
        std::lock_guard<std::mutex> lock(peakMutex_);
        recordPeaks_.clear();
    }
    recording_.store(true);
    recordThread_ = std::thread([this] { recordLoop(); });
    return true;
}

void Engine::pushInput(const float* mono, int frames) {
    float peak = 0;
    for (int i = 0; i < frames; i++) peak = std::max(peak, std::fabs(mono[i]));
    raisePeak(inputPeak_, peak);
    if (!recording_.load(std::memory_order_relaxed)) return;
    if (recordStart_.load(std::memory_order_relaxed) < 0) recordStart_.store(position());
    uint32_t w = ringWrite_.load(std::memory_order_relaxed);
    const uint32_t space = kRingSize - (w - ringRead_.load(std::memory_order_acquire));
    const uint32_t n = std::min<uint32_t>((uint32_t)frames, space);
    for (uint32_t i = 0; i < n; i++) ring_[(w + i) & (kRingSize - 1)] = mono[i];
    ringWrite_.store(w + n, std::memory_order_release);
}

void Engine::recordLoop() {
    std::vector<float> chunk(8192);
    float peak = 0;
    int count = 0;
    while (true) {
        const bool active = recording_.load();
        uint32_t r = ringRead_.load(std::memory_order_relaxed);
        const uint32_t avail = ringWrite_.load(std::memory_order_acquire) - r;
        if (avail == 0) {
            if (!active) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(15));
            continue;
        }
        const uint32_t n = std::min<uint32_t>(avail, (uint32_t)chunk.size());
        for (uint32_t i = 0; i < n; i++) chunk[i] = ring_[(r + i) & (kRingSize - 1)];
        ringRead_.store(r + n, std::memory_order_release);
        recordFile_.write(chunk.data(), n);
        std::lock_guard<std::mutex> lock(peakMutex_);
        for (uint32_t i = 0; i < n; i++) {
            peak = std::max(peak, std::fabs(chunk[i]));
            if (++count == kPeakFrames) {
                recordPeaks_.push_back(peak);
                peak = 0, count = 0;
            }
        }
    }
}

int64_t Engine::stopRecording(int64_t* startFrame) {
    *startFrame = 0;
    if (!recordThread_.joinable()) return 0;
    recording_.store(false);
    recordThread_.join();
    const int64_t frames = recordFile_.frames();
    recordFile_.close();
    *startFrame = std::max<int64_t>(recordStart_.load(), 0);
    return frames;
}

std::vector<float> Engine::recordingPeaks(int from) {
    std::lock_guard<std::mutex> lock(peakMutex_);
    if (from < 0 || from >= (int)recordPeaks_.size()) return {};
    return std::vector<float>(recordPeaks_.begin() + from, recordPeaks_.end());
}

// ---- offline render ----

bool Engine::renderToFile(const std::string& path, int64_t start, int64_t end, int bits) {
    WavWriter out;
    if (end <= start || !out.open(path, 2, bits)) return false;
    const int64_t savedPos = position();
    renderCancel_.store(false);
    renderProgress_.store(0.f);
    offline_ = true;
    seekRequest_.store(start);
    playing_.store(true);

    float buf[512 * 2];
    for (int64_t at = start; at < end && !renderCancel_.load(); at += 512) {
        const int n = (int)std::min<int64_t>(512, end - at);
        render(buf, n);
        out.write(buf, n);
        renderProgress_.store((float)(at - start) / (float)(end - start));
    }
    out.close();

    playing_.store(false);
    render(buf, 64);  // lets held notes release before the device resumes
    offline_ = false;
    seekRequest_.store(savedPos);
    const bool ok = !renderCancel_.load();
    renderProgress_.store(1.f);
    return ok;
}

}  // namespace vd
