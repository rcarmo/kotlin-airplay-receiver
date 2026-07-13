/**
 *  Copyright (C) 2011-2012  Juho Vähä-Herttua
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 */

#include <stdlib.h>
#include <string.h>
#include <assert.h>
#include <math.h>

#include "raop_buffer.h"
#include "raop_rtp.h"

#include <stdint.h>
#include <sha512.h>
#include "crypto/crypto.h"
#include "aes.h"
#include "compat.h"
#include "fdk-aac/libAACdec/include/aacdecoder_lib.h"
#include "fdk-aac/libFDK/include/clz.h"
#include "fdk-aac/libSYS/include/FDK_audio.h"
#include "stream.h"
#include "alac/alac_decoder_wrapper.h"

#define RAOP_BUFFER_LENGTH 64

typedef struct {
	/* Packet available */
	int available;

	/* RTP header */
	unsigned char flags;
	unsigned char type;
	unsigned short seqnum;
	unsigned int timestamp;
	unsigned int ssrc;

	/* Memory size */
	int audio_buffer_size;
	/* Decoded length */
	int audio_buffer_len;
	void *audio_buffer;
} raop_buffer_entry_t;

struct raop_buffer_s {
    logger_t *logger;
	/* Key and IV used for decryption */
	unsigned char aeskey[RAOP_AESKEY_LEN];
	unsigned char aesiv[RAOP_AESIV_LEN];
    AES_CTX aes_ctx_audio;

    HANDLE_AACDECODER phandle;
    alac_decoder_wrapper_t *alac_decoder;
    raop_audio_config_t audio_config;
    int pcm_pkt_size;
    int alac_decode_errors;
    int alac_decode_success_logged;

	/* First and last seqnum */
	int is_empty;
	// Playing sequence number
	unsigned short first_seqnum;
	// Received serial number
	unsigned short last_seqnum;

	/* RTP buffer entries */
	raop_buffer_entry_t entries[RAOP_BUFFER_LENGTH];

	/* Buffer of all audio buffers */
	int buffer_size;
	void *buffer;
};

static int fdk_flags = 0;

/* period size 480 samples */
#define N_SAMPLE 480

HANDLE_AACDECODER
create_fdk_aac_decoder(logger_t *logger)
{
    int ret = 0;
    UINT nrOfLayers = 1;
	HANDLE_AACDECODER phandle = aacDecoder_Open(TT_MP4_RAW, nrOfLayers);
    if (phandle == NULL) {
        logger_log(logger, LOGGER_DEBUG, "aacDecoder open faild!\n");
        return NULL;
    }
    /* ASC config binary data */
	UCHAR eld_conf[] = { 0xF8, 0xE8, 0x50, 0x00 };
	UCHAR *conf[] = { eld_conf };
	static UINT conf_len = sizeof(eld_conf);
    ret = aacDecoder_ConfigRaw(phandle, conf, &conf_len);
    if (ret != AAC_DEC_OK) {
        logger_log(logger, LOGGER_DEBUG, "Unable to set configRaw\n");
        return NULL;
    }
    CStreamInfo *aac_stream_info = aacDecoder_GetStreamInfo(phandle);
    if (aac_stream_info == NULL) {
        logger_log(logger, LOGGER_DEBUG, "aacDecoder_GetStreamInfo failed!\n");
        return NULL;
    }
    logger_log(logger, LOGGER_DEBUG, "> stream info: channel = %d\tsample_rate = %d\tframe_size = %d\taot = %d\tbitrate = %d\n",   \
            aac_stream_info->channelConfig, aac_stream_info->aacSampleRate,
           aac_stream_info->aacSamplesPerFrame, aac_stream_info->aot, aac_stream_info->bitRate);
    return phandle;
}

static int
raop_buffer_set_audio_buffer_size(raop_buffer_t *raop_buffer, int audio_buffer_size)
{
    void *new_buffer;

    if (!raop_buffer || audio_buffer_size <= 0) {
        return -1;
    }
    if (raop_buffer->buffer && raop_buffer->buffer_size == audio_buffer_size * RAOP_BUFFER_LENGTH) {
        for (int i = 0; i < RAOP_BUFFER_LENGTH; i++) {
            raop_buffer_entry_t *entry = &raop_buffer->entries[i];
            entry->audio_buffer_size = audio_buffer_size;
            entry->audio_buffer = (char *)raop_buffer->buffer + i * audio_buffer_size;
        }
        return 0;
    }

    new_buffer = realloc(raop_buffer->buffer, audio_buffer_size * RAOP_BUFFER_LENGTH);
    if (!new_buffer) {
        return -1;
    }
    raop_buffer->buffer = new_buffer;
    raop_buffer->buffer_size = audio_buffer_size * RAOP_BUFFER_LENGTH;
    for (int i = 0; i < RAOP_BUFFER_LENGTH; i++) {
        raop_buffer_entry_t *entry = &raop_buffer->entries[i];
        entry->audio_buffer_size = audio_buffer_size;
        entry->audio_buffer_len = 0;
        entry->audio_buffer = (char *)raop_buffer->buffer + i * audio_buffer_size;
    }
    return 0;
}

int
raop_buffer_configure_audio(raop_buffer_t *raop_buffer, const raop_audio_config_t *config)
{
    unsigned int output_bytes;

    if (!raop_buffer || !config) {
        return -1;
    }

    output_bytes = raop_audio_config_output_bytes(config);
    if (output_bytes == 0) {
        logger_log(raop_buffer->logger, LOGGER_ERR, "Invalid audio config: codec=%d sr=%u depth=%u channels=%u spf=%u",
                   config->codec, config->sample_rate, config->bit_depth, config->channels, config->frames_per_packet);
        return -1;
    }

    raop_buffer_flush(raop_buffer, -1);
    if (raop_buffer_set_audio_buffer_size(raop_buffer, (int)output_bytes) < 0) {
        logger_log(raop_buffer->logger, LOGGER_ERR, "Unable to resize audio buffer to %u bytes", output_bytes);
        return -1;
    }

    if (config->codec == RAOP_AUDIO_CODEC_ALAC) {
        if (raop_buffer->phandle) {
            aacDecoder_Close(raop_buffer->phandle);
            raop_buffer->phandle = NULL;
        }
        if (raop_buffer->alac_decoder) {
            alac_decoder_wrapper_destroy(raop_buffer->alac_decoder);
            raop_buffer->alac_decoder = NULL;
        }
        raop_buffer->alac_decoder = alac_decoder_wrapper_create(config->sample_rate,
                                                                config->bit_depth,
                                                                config->channels,
                                                                config->frames_per_packet);
        if (!raop_buffer->alac_decoder) {
            logger_log(raop_buffer->logger, LOGGER_ERR, "ALAC decoder init failed, spf=%u, %u/%u/%u",
                       config->frames_per_packet, config->sample_rate, config->bit_depth, config->channels);
            return -1;
        }
        raop_buffer->audio_config = *config;
        raop_buffer->pcm_pkt_size = (int)output_bytes;
        raop_buffer->alac_decode_errors = 0;
        raop_buffer->alac_decode_success_logged = 0;
        logger_log(raop_buffer->logger, LOGGER_INFO, "ALAC decoder initialized, spf=%u, %u/%u/%u",
                   config->frames_per_packet, config->sample_rate, config->bit_depth, config->channels);
        return 0;
    }

    if (config->codec == RAOP_AUDIO_CODEC_AAC_ELD) {
        if (raop_buffer->alac_decoder) {
            alac_decoder_wrapper_destroy(raop_buffer->alac_decoder);
            raop_buffer->alac_decoder = NULL;
        }
        if (!raop_buffer->phandle) {
            raop_buffer->phandle = create_fdk_aac_decoder(raop_buffer->logger);
            if (!raop_buffer->phandle) {
                return -1;
            }
        }
        raop_buffer->audio_config = *config;
        raop_buffer->pcm_pkt_size = (int)output_bytes;
        logger_log(raop_buffer->logger, LOGGER_INFO, "AAC-ELD decoder initialized, spf=%u, %u/%u/%u",
                   config->frames_per_packet, config->sample_rate, config->bit_depth, config->channels);
        return 0;
    }

    logger_log(raop_buffer->logger, LOGGER_ERR, "Unsupported audio codec config: %d", config->codec);
    return -1;
}

void
raop_buffer_init_key_iv(raop_buffer_t *raop_buffer,
                     const unsigned char *aeskey,
                     const unsigned char *aesiv,
                     const unsigned char *ecdh_secret)
{

    // Initialization key
    unsigned char eaeskey[64];
    memcpy(eaeskey, aeskey, 16);
    sha512_context ctx;
    sha512_init(&ctx);
    sha512_update(&ctx, eaeskey, 16);
    sha512_update(&ctx, ecdh_secret, 32);
    sha512_final(&ctx, eaeskey);
    memcpy(raop_buffer->aeskey, eaeskey, 16);
    memcpy(raop_buffer->aesiv, aesiv, RAOP_AESIV_LEN);
    AES_set_key(&raop_buffer->aes_ctx_audio, raop_buffer->aeskey, raop_buffer->aesiv, AES_MODE_128);
    AES_convert_key(&raop_buffer->aes_ctx_audio);
#ifdef DUMP_AUDIO
    if (file_keyiv != NULL) {
        fwrite(raop_buffer->aeskey, 16, 1, file_keyiv);
        fwrite(raop_buffer->aesiv, 16, 1, file_keyiv);
        fclose(file_keyiv);
    }
#endif
}

raop_buffer_t *
raop_buffer_init(logger_t *logger,
                 const unsigned char *aeskey,
                 const unsigned char *aesiv,
                 const unsigned char *ecdh_secret)
{
	raop_buffer_t *raop_buffer;
	raop_audio_config_t default_config;
	unsigned int audio_buffer_size;
	assert(aeskey);
    assert(aesiv);
    assert(ecdh_secret);
	raop_buffer = calloc(1, sizeof(raop_buffer_t));
	if (!raop_buffer) {
		return NULL;
	}
    raop_buffer->logger = logger;

	/* Allocate the output audio buffers */
	default_config = raop_audio_config_default_aac_eld();
    audio_buffer_size = raop_audio_config_output_bytes(&default_config);
    raop_buffer->audio_config = default_config;
    raop_buffer->pcm_pkt_size = (int)audio_buffer_size;
    if (raop_buffer_set_audio_buffer_size(raop_buffer, (int)audio_buffer_size) < 0) {
        free(raop_buffer);
        return NULL;
    }
    raop_buffer->phandle = create_fdk_aac_decoder(logger);
    if (!raop_buffer->phandle) {
		free(raop_buffer->buffer);
		free(raop_buffer);
		return NULL;
	}
    raop_buffer_init_key_iv(raop_buffer, aeskey, aesiv, ecdh_secret);
	/* Mark buffer as empty */
	raop_buffer->is_empty = 1;

	return raop_buffer;
}

void
raop_buffer_destroy(raop_buffer_t *raop_buffer)
{
	if (raop_buffer) {
	    if (raop_buffer->phandle) {
            aacDecoder_Close(raop_buffer->phandle);
        }
        if (raop_buffer->alac_decoder) {
            alac_decoder_wrapper_destroy(raop_buffer->alac_decoder);
        }
		free(raop_buffer->buffer);
		free(raop_buffer);
	}
#ifdef DUMP_AUDIO
    if (file_aac != NULL) {
        fclose(file_aac);
    }
    if (file_source != NULL) {
        fclose(file_source);
    }
    if (file_pcm != NULL) {
        fclose(file_pcm);
    }
#endif

}

static short
seqnum_cmp(unsigned short s1, unsigned short s2)
{
	return (s1 - s2);
}

short dithered_vol(int sample, int v) {
    int out = sample * v;
/*    if (v < 65536) {
        out = (out + rand_a) - rand_b;
    }*/
    return (short) (out >> 16);
}

int
stuff_buffer(short* input, short* output, int vol) {
    int i;
    int i2;
    int l;
    int stuffsamp = 480;
    int l2 = 0;
    int j = 0;
    for (i = 0; i < stuffsamp; i++) {
        i2 = j + 1;
        l = l2 + 1;
        output[j] = dithered_vol(input[l2], vol);
        j = i2 + 1;
        l2 = l + 1;
        output[i2] = dithered_vol(input[l], vol);
    }
    return 480;
}

//#define DUMP_AUDIO

#ifdef DUMP_AUDIO
static FILE* file_aac = NULL;
static FILE* file_source = NULL;
static FILE* file_keyiv = NULL;
static FILE* file_pcm = NULL;
#endif


int
raop_buffer_queue(raop_buffer_t *raop_buffer, unsigned char *data, unsigned short datalen, raop_callbacks_t *callbacks)
{
    assert(raop_buffer);
    int encryptedlen;
    raop_buffer_entry_t *entry;
#ifdef DUMP_AUDIO
    if (file_aac == NULL) {
        file_aac = fopen("/sdcard/audio.aac", "wb");
        file_source = fopen("/sdcard/audio.source", "wb");
        file_keyiv = fopen("/sdcard/audio.keyiv", "wb");
        file_pcm = fopen("/sdcard/audio.pcm", "wb");
    }
#endif

    /* Check packet data length is valid */
    if (datalen < 12 || datalen > RAOP_PACKET_LEN) {
        return -1;
    }
    unsigned short seqnum = (data[2] << 8) | data[3];
    if (datalen == 16 && data[12] == 0x0 && data[13] == 0x68 && data[14] == 0x34 && data[15] == 0x0) {
        return 0;
    }
    int payloadsize = datalen - 12;
#ifdef DUMP_AUDIO
    // Undecrypted file
    if (file_source != NULL) {
        fwrite(&data[12], payloadsize, 1, file_source);
    }
#endif
    //logger_log(raop_buffer->logger, LOGGER_DEBUG, "seqnum = %d payloadsize = %d", seqnum, payloadsize);


	if (!raop_buffer->is_empty && seqnum_cmp(seqnum, raop_buffer->first_seqnum) < 0) {
		return 0;
	}
	/* Check that there is always space in the buffer, otherwise flush */
	if (seqnum_cmp(seqnum, raop_buffer->first_seqnum+RAOP_BUFFER_LENGTH) >= 0) {
		raop_buffer_flush(raop_buffer, seqnum);
	}
	entry = &raop_buffer->entries[seqnum % RAOP_BUFFER_LENGTH];
	if (entry->available && seqnum_cmp(entry->seqnum, seqnum) == 0) {
		/* Packet resend, we can safely ignore */
		return 0;
	}
    entry->flags = data[0];
    entry->type = data[1];
    entry->seqnum = seqnum;
    // The 4th byte starts with pts
    entry->timestamp = (data[4] << 24) | (data[5] << 16) |
                       (data[6] << 8) | data[7];
    entry->ssrc = (data[8] << 24) | (data[9] << 16) |
                  (data[10] << 8) | data[11];
    entry->available = 1;

    encryptedlen = payloadsize/16*16;
    unsigned char packetbuf[payloadsize];
    memset(packetbuf, 0, payloadsize);
	// Need to be initialized internally
    AES_CTX aes_ctx_audio = raop_buffer->aes_ctx_audio;
    AES_cbc_decrypt(&aes_ctx_audio, &data[12], packetbuf, encryptedlen);
    memcpy(packetbuf+encryptedlen, &data[12+encryptedlen], payloadsize-encryptedlen);
#ifdef DUMP_AUDIO
    // Decrypted file
    if (file_aac != NULL) {
        fwrite(packetbuf, payloadsize, 1, file_aac);
    }
#endif
    if (raop_buffer->audio_config.codec == RAOP_AUDIO_CODEC_ALAC) {
        size_t decoded_bytes = 0;
        uint32_t decoded_frames = 0;
        uint32_t consumed_offset = 0;
        int ret = alac_decoder_wrapper_decode(raop_buffer->alac_decoder,
                                              packetbuf,
                                              (size_t)payloadsize,
                                              (uint8_t *)entry->audio_buffer,
                                              (size_t)entry->audio_buffer_size,
                                              &decoded_frames,
                                              &decoded_bytes,
                                              &consumed_offset);
        if (ret == 0 && decoded_bytes > 0) {
            entry->audio_buffer_len = (int)decoded_bytes;
            if (!raop_buffer->alac_decode_success_logged) {
                logger_log(raop_buffer->logger, LOGGER_INFO,
                           "ALAC first frame decoded: payload=%d decoded=%d frames=%u offset=%u pts=%u",
                           payloadsize, entry->audio_buffer_len, decoded_frames, consumed_offset, entry->timestamp);
                raop_buffer->alac_decode_success_logged = 1;
            }
        } else {
            entry->audio_buffer_len = entry->audio_buffer_size;
            memset(entry->audio_buffer, 0, entry->audio_buffer_len);
            if (raop_buffer->alac_decode_errors < 20) {
                logger_log(raop_buffer->logger, LOGGER_ERR,
                           "ALAC decode error %d: payload=%d pts=%u first_bytes=%02x %02x %02x %02x",
                           ret,
                           payloadsize,
                           entry->timestamp,
                           payloadsize > 0 ? packetbuf[0] : 0,
                           payloadsize > 1 ? packetbuf[1] : 0,
                           payloadsize > 2 ? packetbuf[2] : 0,
                           payloadsize > 3 ? packetbuf[3] : 0);
            }
            raop_buffer->alac_decode_errors++;
        }
    } else {
        // AAC-ELD decoding pcm
        int ret = 0;
        UINT pkt_size = payloadsize;
        UINT valid_size = payloadsize;
        UCHAR *input_buf[1] = {packetbuf};
        if (!raop_buffer->phandle) {
            raop_buffer->phandle = create_fdk_aac_decoder(raop_buffer->logger);
        }
        if (!raop_buffer->phandle) {
            return -1;
        }
        ret = aacDecoder_Fill(raop_buffer->phandle, input_buf, &pkt_size, &valid_size);
        if (ret != AAC_DEC_OK) {
            logger_log(raop_buffer->logger, LOGGER_ERR, "aacDecoder_Fill error : %x", ret);
        }
        ret = aacDecoder_DecodeFrame(raop_buffer->phandle, entry->audio_buffer, raop_buffer->pcm_pkt_size, fdk_flags);
        entry->audio_buffer_len = raop_buffer->pcm_pkt_size;
        if (ret != AAC_DEC_OK) {
            logger_log(raop_buffer->logger, LOGGER_ERR, "aacDecoder_DecodeFrame error : 0x%x", ret);
        }
    }
#ifdef DUMP_AUDIO
    if (file_pcm != NULL) {
        fwrite(entry->audio_buffer, entry->audio_buffer_len, 1, file_pcm);
    }
#endif

	/* Update the raop_buffer seqnums */
	if (raop_buffer->is_empty) {
		raop_buffer->first_seqnum = seqnum;
		raop_buffer->last_seqnum = seqnum;
		raop_buffer->is_empty = 0;
	}
	if (seqnum_cmp(seqnum, raop_buffer->last_seqnum) > 0) {
		raop_buffer->last_seqnum = seqnum;
	}

    return 1;
}

const void *
raop_buffer_dequeue(raop_buffer_t *raop_buffer, int *length, unsigned int* pts, int no_resend)
{
	short buflen;
	raop_buffer_entry_t *entry;

	/* Calculate number of entries in the current buffer */
	buflen = seqnum_cmp(raop_buffer->last_seqnum, raop_buffer->first_seqnum) + 1;

	/* Cannot dequeue from empty buffer */
	if (raop_buffer->is_empty || buflen <= 0) {
		return NULL;
	}

	/* Get the first buffer entry for inspection */
	entry = &raop_buffer->entries[raop_buffer->first_seqnum % RAOP_BUFFER_LENGTH];
	if (no_resend) {
		/* If we do no resends, always return the first entry */
	} else if (!entry->available) {
		/* Check how much we have space left in the buffer */
		if (buflen < RAOP_BUFFER_LENGTH) {
			/* Return nothing and hope resend gets on time */
			return NULL;
		}
		/* Risk of buffer overrun, return empty buffer */
	}

	/* Update buffer and validate entry */
	raop_buffer->first_seqnum += 1;
	if (!entry->available) {
		/* Return an empty audio buffer to skip audio */
		*length = entry->audio_buffer_size;
		memset(entry->audio_buffer, 0, *length);
		return entry->audio_buffer;
	}
	entry->available = 0;

	/* Return entry audio buffer */
	*length = entry->audio_buffer_len;
	*pts = entry->timestamp;
	entry->audio_buffer_len = 0;
	return entry->audio_buffer;
}

void
raop_buffer_handle_resends(raop_buffer_t *raop_buffer, raop_resend_cb_t resend_cb, void *opaque)
{
	raop_buffer_entry_t *entry;

	assert(raop_buffer);
	assert(resend_cb);

	if (seqnum_cmp(raop_buffer->first_seqnum, raop_buffer->last_seqnum) < 0) {
		int seqnum, count;

		for (seqnum=raop_buffer->first_seqnum; seqnum_cmp(seqnum, raop_buffer->last_seqnum)<0; seqnum++) {
			entry = &raop_buffer->entries[seqnum % RAOP_BUFFER_LENGTH];
			if (entry->available) {
				break;
			}
		}
		if (seqnum_cmp(seqnum, raop_buffer->first_seqnum) == 0) {
			return;
		}
		count = seqnum_cmp(seqnum, raop_buffer->first_seqnum);
		resend_cb(opaque, raop_buffer->first_seqnum, count);
	}
}

void
raop_buffer_flush(raop_buffer_t *raop_buffer, int next_seq)
{
	int i;
	assert(raop_buffer);
	for (i=0; i<RAOP_BUFFER_LENGTH; i++) {
		raop_buffer->entries[i].available = 0;
		raop_buffer->entries[i].audio_buffer_len = 0;
	}
	if (next_seq < 0 || next_seq > 0xffff) {
		raop_buffer->is_empty = 1;
	} else {
		raop_buffer->first_seqnum = next_seq;
		raop_buffer->last_seqnum = next_seq-1;
	}
}
