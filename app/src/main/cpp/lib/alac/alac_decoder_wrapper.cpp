#include "alac_decoder_wrapper.h"

#include <new>
#include <string.h>

#include "ALACBitUtilities.h"
#include "ALACDecoder.h"

struct alac_decoder_wrapper_s {
    ALACDecoder *decoder;
    uint32_t sample_rate;
    uint32_t bit_depth;
    uint32_t channels;
    uint32_t frames_per_packet;
};

static void
write_be16(uint8_t *buffer, uint16_t value)
{
    buffer[0] = static_cast<uint8_t>((value >> 8) & 0xff);
    buffer[1] = static_cast<uint8_t>(value & 0xff);
}

static void
write_be32(uint8_t *buffer, uint32_t value)
{
    buffer[0] = static_cast<uint8_t>((value >> 24) & 0xff);
    buffer[1] = static_cast<uint8_t>((value >> 16) & 0xff);
    buffer[2] = static_cast<uint8_t>((value >> 8) & 0xff);
    buffer[3] = static_cast<uint8_t>(value & 0xff);
}

static void
build_alac_magic_cookie(uint8_t cookie[24],
                        uint32_t sample_rate,
                        uint32_t bit_depth,
                        uint32_t channels,
                        uint32_t frames_per_packet)
{
    memset(cookie, 0, 24);
    write_be32(cookie + 0, frames_per_packet);
    cookie[4] = 0;                                  /* compatibleVersion */
    cookie[5] = static_cast<uint8_t>(bit_depth);
    cookie[6] = 40;                                 /* pb */
    cookie[7] = 10;                                 /* mb */
    cookie[8] = 14;                                 /* kb */
    cookie[9] = static_cast<uint8_t>(channels);
    write_be16(cookie + 10, 255);                   /* maxRun */
    write_be32(cookie + 12, 0);                     /* maxFrameBytes unknown */
    write_be32(cookie + 16, 0);                     /* avgBitRate unknown */
    write_be32(cookie + 20, sample_rate);
}

alac_decoder_wrapper_t *
alac_decoder_wrapper_create(uint32_t sample_rate,
                            uint32_t bit_depth,
                            uint32_t channels,
                            uint32_t frames_per_packet)
{
    if (sample_rate == 0 || bit_depth == 0 || channels == 0 || frames_per_packet == 0) {
        return nullptr;
    }

    alac_decoder_wrapper_t *wrapper = new (std::nothrow) alac_decoder_wrapper_t();
    if (!wrapper) {
        return nullptr;
    }

    wrapper->decoder = new (std::nothrow) ALACDecoder();
    if (!wrapper->decoder) {
        delete wrapper;
        return nullptr;
    }

    uint8_t cookie[24];
    build_alac_magic_cookie(cookie, sample_rate, bit_depth, channels, frames_per_packet);
    int32_t status = wrapper->decoder->Init(cookie, sizeof(cookie));
    if (status != ALAC_noErr) {
        delete wrapper->decoder;
        delete wrapper;
        return nullptr;
    }

    wrapper->sample_rate = sample_rate;
    wrapper->bit_depth = bit_depth;
    wrapper->channels = channels;
    wrapper->frames_per_packet = frames_per_packet;
    return wrapper;
}

void
alac_decoder_wrapper_destroy(alac_decoder_wrapper_t *decoder)
{
    if (!decoder) {
        return;
    }
    delete decoder->decoder;
    delete decoder;
}

static int
decode_at_offset(alac_decoder_wrapper_t *decoder,
                 const uint8_t *input,
                 size_t input_size,
                 uint32_t offset,
                 uint8_t *output,
                 size_t output_size,
                 uint32_t *output_frames,
                 size_t *output_bytes)
{
    if (offset >= input_size) {
        return kALAC_ParamError;
    }

    const uint32_t bytes_per_sample = decoder->bit_depth / 8U;
    const size_t needed = static_cast<size_t>(decoder->frames_per_packet) *
                          decoder->channels *
                          bytes_per_sample;
    if (output_size < needed) {
        return kALAC_ParamError;
    }

    BitBuffer bits;
    BitBufferInit(&bits,
                  const_cast<uint8_t *>(input + offset),
                  static_cast<uint32_t>(input_size - offset));

    uint32_t decoded_frames = 0;
    int32_t status = decoder->decoder->Decode(&bits,
                                              output,
                                              decoder->frames_per_packet,
                                              decoder->channels,
                                              &decoded_frames);
    if (status != ALAC_noErr || decoded_frames == 0) {
        return status == ALAC_noErr ? kALAC_ParamError : status;
    }

    if (output_frames) {
        *output_frames = decoded_frames;
    }
    if (output_bytes) {
        *output_bytes = static_cast<size_t>(decoded_frames) *
                        decoder->channels *
                        bytes_per_sample;
    }
    return ALAC_noErr;
}

int
alac_decoder_wrapper_decode(alac_decoder_wrapper_t *decoder,
                            const uint8_t *input,
                            size_t input_size,
                            uint8_t *output,
                            size_t output_size,
                            uint32_t *output_frames,
                            size_t *output_bytes,
                            uint32_t *consumed_offset)
{
    if (!decoder || !decoder->decoder || !input || input_size == 0 || !output) {
        return kALAC_ParamError;
    }

    uint32_t offsets[3];
    uint32_t offset_count = 0;
    if (input_size > 1 && input[0] == 0x00) {
        offsets[offset_count++] = 1;
    }
    offsets[offset_count++] = 0;
    if (input_size > 4) {
        offsets[offset_count++] = 4;
    }

    int first_status = kALAC_ParamError;
    for (uint32_t i = 0; i < offset_count; i++) {
        int status = decode_at_offset(decoder,
                                      input,
                                      input_size,
                                      offsets[i],
                                      output,
                                      output_size,
                                      output_frames,
                                      output_bytes);
        if (status == ALAC_noErr) {
            if (consumed_offset) {
                *consumed_offset = offsets[i];
            }
            return ALAC_noErr;
        }
        if (i == 0) {
            first_status = status;
        }
    }
    return first_status;
}
