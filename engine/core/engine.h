// The mixing engine. Platform code owns the audio device and calls render(); everything
// else talks to it from a control thread. The project arrives as a text snapshot (see
// sync()) which is swapped in without blocking the audio thread.
#pragma once
#include <atomic>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "audio_file.h"
#include "effects.h"
#include "instruments.h"

namespace vd {

class Engine {
public:
    Engine();
    ~Engine();

    // ---- control thread ----
    //
    // Snapshot format, one record per line, positions in frames, gains linear:
    //   P framesPerBeat beatsPerBar loop loopStart loopEnd masterGain metronome
    //   T id type(0 audio,1 sampler,2 drums) gain pan audible
    //   C start offset frames gain fadeIn fadeOut path   audio clip of the last track, fades in frames
    //   N start length pitch velocity              note of the last track
    //   E id type enabled count p0 p1 ...          effect of the last track, in chain order
    //   S root tune attackMs releaseMs gain path   sampler of the last track ("-" = built-in)
    //   D pad gain pan tune path                   drum pad of the last track ("-" = built-in)
    void sync(const std::string& snapshot);

    void play();
    void pause();
    void seek(int64_t frame);
    int64_t position() const;
    bool playing() const { return playing_.load(); }

    void noteOn(int64_t track, int pitch, int velocity);
    void noteOff(int64_t track, int pitch);

    // Writes master L, master R, then one peak per track in snapshot order. Returns the count.
    int meters(float* out, int max);

    bool startRecording(const std::string& path);
    // Returns recorded frames and the timeline frame at which the first sample was captured.
    int64_t stopRecording(int64_t* startFrame);
    std::vector<float> recordingPeaks(int from);
    float inputLevel() { return inputPeak_.exchange(0.f); }

    // Offline bounce of [start, end) to a WAV file. The device must not call render() meanwhile.
    bool renderToFile(const std::string& path, int64_t start, int64_t end, int bits);
    float renderProgress() const { return renderProgress_.load(); }
    void cancelRender() { renderCancel_.store(true); }

    // ---- audio threads ----
    void render(float* interleavedStereo, int frames);
    void pushInput(const float* mono, int frames);

    static constexpr int kPeakFrames = 512;

private:
    static constexpr int kMaxBlock = 1024;

    struct TrackRt {  // survives snapshots
        std::atomic<float> peak{0.f};
        float gainL = 0, gainR = 0;
        bool primed = false;
        Instrument instrument;
    };
    struct Clip {
        int64_t start, offset, frames, fadeIn, fadeOut;
        float gain;
        std::shared_ptr<AudioFile> file;
    };
    struct MidiEvent {
        int64_t frame;
        uint8_t pitch, velocity;
        bool on;
    };
    struct Track {
        int64_t id = 0;
        int type = 0;
        float gain = 1, pan = 0;
        bool audible = true;
        std::vector<Clip> clips;
        std::vector<MidiEvent> events;
        std::vector<std::shared_ptr<Effect>> effects;
        InstrumentConfig instrument;
        std::shared_ptr<TrackRt> rt;
    };
    struct Snapshot {
        double framesPerBeat = 24000;
        int beatsPerBar = 4;
        bool loop = false, metronome = false;
        int64_t loopStart = 0, loopEnd = 0;
        float master = 1;
        std::vector<Track> tracks;
    };
    struct LiveNote {
        int64_t track;
        uint8_t pitch, velocity;
        bool on;
    };

    void renderChunk(float* out, int n, bool playing);
    void renderTrack(Track& t, int n, bool playing);
    void pushLive(const LiveNote& note);
    void allNotesOff();
    void recordLoop();

    // Snapshot hand-over. The audio thread only ever try_locks.
    std::mutex swapMutex_;
    Snapshot* pending_ = nullptr;
    std::vector<Snapshot*> retired_;
    Snapshot* current_;

    // Control-side registries keep DSP state alive across snapshots.
    std::map<int64_t, std::shared_ptr<TrackRt>> trackRt_;
    std::map<int64_t, std::pair<std::string, std::shared_ptr<Effect>>> effects_;
    std::map<std::string, std::weak_ptr<AudioFile>> files_;
    std::map<std::string, std::weak_ptr<Sample>> samples_;
    std::shared_ptr<Sample> builtinTone_, builtinDrums_[kPads];
    std::mutex meterMutex_;
    std::vector<std::shared_ptr<TrackRt>> meterOrder_;

    std::atomic<bool> playing_{false};
    std::atomic<int64_t> pos_{0}, seekRequest_{-1};
    bool wasPlaying_ = false, offline_ = false;
    std::atomic<float> masterPeak_[2];

    std::mutex liveMutex_;
    LiveNote live_[256];
    std::atomic<uint32_t> liveWrite_{0}, liveRead_{0};

    float bufL_[kMaxBlock], bufR_[kMaxBlock], mixL_[kMaxBlock], mixR_[kMaxBlock];
    float clickPhase_ = 0, clickFreq_ = 0, clickEnv_ = 0;

    // Recording: the input thread fills a ring, a writer thread drains it to disk.
    std::vector<float> ring_;
    std::atomic<uint32_t> ringWrite_{0}, ringRead_{0};
    std::atomic<bool> recording_{false};
    std::atomic<int64_t> recordStart_{-1};
    std::atomic<float> inputPeak_{0.f};
    std::thread recordThread_;
    WavWriter recordFile_;
    std::mutex peakMutex_;
    std::vector<float> recordPeaks_;

    std::atomic<float> renderProgress_{0.f};
    std::atomic<bool> renderCancel_{false};
};

}  // namespace vd
