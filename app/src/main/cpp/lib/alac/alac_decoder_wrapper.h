/*
 * Thin C ABI around Apple's C++ ALACDecoder so the C RAOP buffer can use it.
 */

#ifndef ALAC_DECODER_WRAPPER_H
#define ALAC_DECODER_WRAPPER_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct alac_decoder_wrapper_s alac_decoder_wrapper_t;

alac_decoder_wrapper_t *alac_decoder_wrapper_create(uint32_t sample_rate,
                                                    uint32_t bit_depth,
                                                    uint32_t channels,
                                                    uint32_t frames_per_packet);

void alac_decoder_wrapper_destroy(alac_decoder_wrapper_t *decoder);

int alac_decoder_wrapper_decode(alac_decoder_wrapper_t *decoder,
                                const uint8_t *input,
                                size_t input_size,
                                uint8_t *output,
                                size_t output_size,
                                uint32_t *output_frames,
                                size_t *output_bytes,
                                uint32_t *consumed_offset);

#ifdef __cplusplus
}
#endif

#endif
