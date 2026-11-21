#pragma once
#include <memory>
#include <cstdint>
namespace oboe {
enum class Result { OK, ErrorDisconnected };
enum class Direction { Output };
enum class PerformanceMode { LowLatency };
enum class SharingMode { Exclusive, Shared };
enum class AudioFormat { Float, I16 };
enum class Usage { Media, Game };
enum class SampleRateConversionQuality { None };
enum class ContentType { Music };
enum class AudioApi { AAudio, OpenSLES };
enum class DataCallbackResult { Continue, Stop };
struct ChannelCount { static constexpr int Stereo = 2; };
template <typename T> const char* convertToText(T) { return ""; }
template <typename T> struct ResultWithValue { explicit operator bool() const { return true; } T value() const { return T{}; } };
class AudioStream {
public:
  int32_t getSampleRate() const { return 48000; } int32_t getFramesPerBurst() const { return 96; }
  int32_t getBufferSizeInFrames() const { return 192; }
  AudioFormat getFormat() const { return AudioFormat::Float; }
  ResultWithValue<int32_t> setBufferSizeInFrames(int32_t) { return {}; }
  SharingMode getSharingMode() const { return SharingMode::Exclusive; } AudioApi getAudioApi() const { return AudioApi::AAudio; }
  Result requestStart() { return Result::OK; } Result stop() { return Result::OK; } Result close() { return Result::OK; }
  ResultWithValue<double> calculateLatencyMillis() { return {}; } ResultWithValue<int32_t> getXRunCount() { return {}; }
};
class AudioStreamDataCallback { public: virtual ~AudioStreamDataCallback() = default;
  virtual DataCallbackResult onAudioReady(AudioStream*, void*, int32_t) = 0; };
class AudioStreamErrorCallback { public: virtual ~AudioStreamErrorCallback() = default;
  virtual void onErrorAfterClose(AudioStream*, Result) {} };
class AudioStreamBuilder { public:
  AudioStreamBuilder* setDirection(Direction) { return this; } AudioStreamBuilder* setPerformanceMode(PerformanceMode) { return this; }
  AudioStreamBuilder* setSharingMode(SharingMode) { return this; } AudioStreamBuilder* setFormat(AudioFormat) { return this; }
  AudioStreamBuilder* setChannelCount(int) { return this; } AudioStreamBuilder* setUsage(Usage) { return this; }
  AudioStreamBuilder* setContentType(ContentType) { return this; }
  AudioStreamBuilder* setSampleRate(int32_t) { return this; } AudioStreamBuilder* setFormatConversionAllowed(bool) { return this; }
  AudioStreamBuilder* setSampleRateConversionQuality(SampleRateConversionQuality) { return this; }
  AudioStreamBuilder* setDataCallback(AudioStreamDataCallback*) { return this; } AudioStreamBuilder* setErrorCallback(AudioStreamErrorCallback*) { return this; }
  Result openStream(std::shared_ptr<AudioStream>& s) { s = std::make_shared<AudioStream>(); return Result::OK; } };
class LatencyTuner { public: explicit LatencyTuner(AudioStream&) {} Result tune() { return Result::OK; } };
}
