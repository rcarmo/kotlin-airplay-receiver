/*
 * Audio codec configuration shared between RTSP SETUP, RTP, and the
 * per-packet decode buffer.
 */

#ifndef RAOP_AUDIO_H
#define RAOP_AUDIO_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RAOP_AUDIO_FORMAT_ALAC_44100_STEREO 0x40000ULL
#define RAOP_AUDIO_FORMAT_AAC_ELD_44100_STEREO 0x1000000ULL
#define RAOP_COMPRESSION_TYPE_ALAC 2ULL
#define RAOP_COMPRESSION_TYPE_AAC_ELD 8ULL

#define RAOP_ALAC_DEFAULT_SAMPLE_RATE 44100U
#define RAOP_ALAC_DEFAULT_BIT_DEPTH 16U
#define RAOP_ALAC_DEFAULT_CHANNELS 2U
#define RAOP_ALAC_DEFAULT_FRAMES_PER_PACKET 352U

#define RAOP_AAC_ELD_SAMPLE_RATE 44100U
#define RAOP_AAC_ELD_BIT_DEPTH 16U
#define RAOP_AAC_ELD_CHANNELS 2U
#define RAOP_AAC_ELD_FRAMES_PER_PACKET 480U

typedef enum {
    RAOP_AUDIO_CODEC_AAC_ELD = 0,
    RAOP_AUDIO_CODEC_ALAC = 1
} raop_audio_codec_t;

typedef struct {
    raop_audio_codec_t codec;
    uint64_t compression_type;
    uint64_t audio_format;
    unsigned int sample_rate;
    unsigned int bit_depth;
    unsigned int channels;
    unsigned int frames_per_packet;
} raop_audio_config_t;

static inline raop_audio_config_t
raop_audio_config_default_aac_eld(void)
{
    raop_audio_config_t config;
    config.codec = RAOP_AUDIO_CODEC_AAC_ELD;
    config.compression_type = RAOP_COMPRESSION_TYPE_AAC_ELD;
    config.audio_format = RAOP_AUDIO_FORMAT_AAC_ELD_44100_STEREO;
    config.sample_rate = RAOP_AAC_ELD_SAMPLE_RATE;
    config.bit_depth = RAOP_AAC_ELD_BIT_DEPTH;
    config.channels = RAOP_AAC_ELD_CHANNELS;
    config.frames_per_packet = RAOP_AAC_ELD_FRAMES_PER_PACKET;
    return config;
}

static inline raop_audio_config_t
raop_audio_config_default_alac(void)
{
    raop_audio_config_t config;
    config.codec = RAOP_AUDIO_CODEC_ALAC;
    config.compression_type = RAOP_COMPRESSION_TYPE_ALAC;
    config.audio_format = RAOP_AUDIO_FORMAT_ALAC_44100_STEREO;
    config.sample_rate = RAOP_ALAC_DEFAULT_SAMPLE_RATE;
    config.bit_depth = RAOP_ALAC_DEFAULT_BIT_DEPTH;
    config.channels = RAOP_ALAC_DEFAULT_CHANNELS;
    config.frames_per_packet = RAOP_ALAC_DEFAULT_FRAMES_PER_PACKET;
    return config;
}

static inline unsigned int
raop_audio_config_output_bytes(const raop_audio_config_t *config)
{
    if (!config || config->channels == 0 || config->bit_depth == 0 || config->frames_per_packet == 0) {
        return 0;
    }
    return config->frames_per_packet * config->channels * (config->bit_depth / 8U);
}

#ifdef __cplusplus
}
#endif

#endif
