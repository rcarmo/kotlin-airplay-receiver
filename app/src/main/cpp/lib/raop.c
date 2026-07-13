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
#include <stdio.h>
#include <string.h>
#include <assert.h>

#include "raop.h"
#include "raop_rtp.h"
#include "raop_rtp.h"
#include "pairing.h"
#include "httpd.h"

#include "global.h"
#include "fairplay.h"
#include "netutils.h"
#include "logger.h"
#include "compat.h"
#include "raop_rtp_mirror.h"
#include <android/log.h>

#define MAX_ACTIVE_SESSIONS 16

struct raop_s {
	/* Callbacks for audio and video */
	raop_callbacks_t callbacks;

	/* Logger instance */
	logger_t *logger;

	/* Pairing, HTTP daemon and RSA key */
	pairing_t *pairing;
	httpd_t *httpd;
	raop_rtp_t *active_rtps[MAX_ACTIVE_SESSIONS];
	raop_rtp_mirror_t *active_mirrors[MAX_ACTIVE_SESSIONS];

    unsigned short port;
};

struct raop_conn_s {
	raop_t *raop;
	raop_rtp_t *raop_rtp;
	raop_rtp_mirror_t *raop_rtp_mirror;
	fairplay_t *fairplay;
	pairing_session_t *pairing;

	unsigned char *local;
	int locallen;

	unsigned char *remote;
	int remotelen;

	int setup;
	int audio_started;
	int mirror_started;
	int stream_stopped_notified;
};
typedef struct raop_conn_s raop_conn_t;

static int raop_has_active_rtp(raop_t *raop, raop_rtp_t *rtp);
static int raop_has_active_mirror(raop_t *raop, raop_rtp_mirror_t *mirror);
static void raop_add_active_rtp(raop_t *raop, raop_rtp_t *rtp);
static void raop_add_active_mirror(raop_t *raop, raop_rtp_mirror_t *mirror);
static void raop_remove_active_rtp(raop_t *raop, raop_rtp_t *rtp);
static void raop_remove_active_mirror(raop_t *raop, raop_rtp_mirror_t *mirror);
static void conn_notify_stream_status(raop_conn_t *conn, const char *status);
static void raop_address_to_string(const unsigned char *address, int address_len, char *out, int out_len);

#include "raop_handlers.h"

static int
raop_has_active_rtp(raop_t *raop, raop_rtp_t *rtp)
{
	for (int i = 0; i < MAX_ACTIVE_SESSIONS; i++) {
		if (raop->active_rtps[i] == rtp) {
			return 1;
		}
	}
	return 0;
}

static int
raop_has_active_mirror(raop_t *raop, raop_rtp_mirror_t *mirror)
{
	for (int i = 0; i < MAX_ACTIVE_SESSIONS; i++) {
		if (raop->active_mirrors[i] == mirror) {
			return 1;
		}
	}
	return 0;
}

static void
raop_add_active_rtp(raop_t *raop, raop_rtp_t *rtp)
{
	if (!rtp || raop_has_active_rtp(raop, rtp)) {
		return;
	}
	for (int i = 0; i < MAX_ACTIVE_SESSIONS; i++) {
		if (!raop->active_rtps[i]) {
			raop->active_rtps[i] = rtp;
			return;
		}
	}
}

static void
raop_add_active_mirror(raop_t *raop, raop_rtp_mirror_t *mirror)
{
	if (!mirror || raop_has_active_mirror(raop, mirror)) {
		return;
	}
	for (int i = 0; i < MAX_ACTIVE_SESSIONS; i++) {
		if (!raop->active_mirrors[i]) {
			raop->active_mirrors[i] = mirror;
			return;
		}
	}
}

static void
raop_remove_active_rtp(raop_t *raop, raop_rtp_t *rtp)
{
	for (int i = 0; i < MAX_ACTIVE_SESSIONS; i++) {
		if (raop->active_rtps[i] == rtp) {
			raop->active_rtps[i] = NULL;
		}
	}
}

static void
raop_remove_active_mirror(raop_t *raop, raop_rtp_mirror_t *mirror)
{
	for (int i = 0; i < MAX_ACTIVE_SESSIONS; i++) {
		if (raop->active_mirrors[i] == mirror) {
			raop->active_mirrors[i] = NULL;
		}
	}
}

static void
conn_notify_stream_status(raop_conn_t *conn, const char *status)
{
	if (!conn || !status) {
		return;
	}
	logger_log(conn->raop->logger, LOGGER_DEBUG, "%s", status);
}

static void
raop_address_to_string(const unsigned char *address, int address_len, char *out, int out_len)
{
	if (!address || !out || out_len <= 0) {
		return;
	}
	if (address_len == 4) {
		snprintf(out, out_len, "%u.%u.%u.%u", address[0], address[1], address[2], address[3]);
	} else if (address_len == 16) {
		snprintf(out, out_len,
		         "%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x",
		         address[0], address[1], address[2], address[3],
		         address[4], address[5], address[6], address[7],
		         address[8], address[9], address[10], address[11],
		         address[12], address[13], address[14], address[15]);
	} else {
		snprintf(out, out_len, "unknown");
	}
}

static void
conn_notify_stream_stopped(raop_conn_t *conn)
{
	if (!conn || conn->stream_stopped_notified) {
		return;
	}
	conn->stream_stopped_notified = 1;
	if (conn->raop->callbacks.stream_stopped) {
		conn->raop->callbacks.stream_stopped(conn->raop->callbacks.cls);
	}
}

static int
request_get_stream_types(http_request_t *request, int *has_audio, int *has_mirror)
{
	const char *data;
	int datalen;
	plist_t root_node = NULL;
	plist_t streams_note;
	uint32_t stream_count;
	int has_stream_type = 0;

	*has_audio = 0;
	*has_mirror = 0;
	data = http_request_get_data(request, &datalen);
	if (!data || datalen <= 0) {
		return 0;
	}
	plist_from_bin(data, datalen, &root_node);
	if (!root_node) {
		return 0;
	}
	streams_note = plist_dict_get_item(root_node, "streams");
	stream_count = streams_note ? plist_array_get_size(streams_note) : 0;
	for (uint32_t i = 0; i < stream_count; i++) {
		uint64_t stream_type = 0;
		plist_t stream_note = plist_array_get_item(streams_note, i);
		plist_t type_note = stream_note ? plist_dict_get_item(stream_note, "type") : NULL;
		if (!type_note) {
			continue;
		}
		plist_get_uint_val(type_note, &stream_type);
		if (stream_type == 96) {
			*has_audio = 1;
			has_stream_type = 1;
		} else if (stream_type == 110) {
			*has_mirror = 1;
			has_stream_type = 1;
		}
	}
	plist_free(root_node);
	return has_stream_type;
}

static void
raop_destroy_active_sessions(raop_t *raop)
{
	if (!raop) {
		return;
	}
	for (int i = 0; i < MAX_ACTIVE_SESSIONS; i++) {
		if (raop->active_rtps[i]) {
			raop_rtp_destroy(raop->active_rtps[i]);
			raop->active_rtps[i] = NULL;
		}
		if (raop->active_mirrors[i]) {
			raop_rtp_mirror_destroy(raop->active_mirrors[i]);
			raop->active_mirrors[i] = NULL;
		}
	}
}

static void *
conn_init(void *opaque, unsigned char *local, int locallen, unsigned char *remote, int remotelen)
{
	raop_t *raop = opaque;
	raop_conn_t *conn;
	char remote_id[64] = {0};

	assert(raop);
	raop_address_to_string(remote, remotelen, remote_id, sizeof(remote_id));
	if (raop->callbacks.sender_should_connect &&
	    !raop->callbacks.sender_should_connect(raop->callbacks.cls, remote_id, remote_id)) {
		logger_log(raop->logger, LOGGER_WARNING, "Rejected sender %s", remote_id);
		return NULL;
	}

	conn = calloc(1, sizeof(raop_conn_t));
	if (!conn) {
		return NULL;
	}
	conn->raop = raop;
	conn->raop_rtp = NULL;
	conn->fairplay = fairplay_init(raop->logger);
	//fairplay_init2();
	if (!conn->fairplay) {
		free(conn);
		return NULL;
	}
	conn->pairing = pairing_session_init(raop->pairing);
	if (!conn->pairing) {
		fairplay_destroy(conn->fairplay);
		free(conn);
		return NULL;
	}

	if (locallen == 4) {
		logger_log(conn->raop->logger, LOGGER_INFO,
		           "Local: %d.%d.%d.%d",
		           local[0], local[1], local[2], local[3]);
	} else if (locallen == 16) {
		logger_log(conn->raop->logger, LOGGER_INFO,
		           "Local: %02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x",
		           local[0], local[1], local[2], local[3], local[4], local[5], local[6], local[7],
		           local[8], local[9], local[10], local[11], local[12], local[13], local[14], local[15]);
	}
	if (remotelen == 4) {
		logger_log(conn->raop->logger, LOGGER_INFO,
		           "Remote: %d.%d.%d.%d",
		           remote[0], remote[1], remote[2], remote[3]);
	} else if (remotelen == 16) {
		logger_log(conn->raop->logger, LOGGER_INFO,
		           "Remote: %02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x",
		           remote[0], remote[1], remote[2], remote[3], remote[4], remote[5], remote[6], remote[7],
		           remote[8], remote[9], remote[10], remote[11], remote[12], remote[13], remote[14], remote[15]);
	}

	conn->local = malloc(locallen);
	assert(conn->local);
	memcpy(conn->local, local, locallen);

	conn->remote = malloc(remotelen);
	assert(conn->remote);
	memcpy(conn->remote, remote, remotelen);

	conn->locallen = locallen;
	conn->remotelen = remotelen;

	conn_notify_stream_status(conn, "Client connected");

	return conn;
}

static void
conn_request(void *ptr, http_request_t *request, http_response_t **response)
{
	raop_conn_t *conn = ptr;
    logger_log(conn->raop->logger, LOGGER_DEBUG, "conn_request");
	const char *method;
	const char *url;
	const char *cseq;

	char *response_data = NULL;
	int response_datalen = 0;

	method = http_request_get_method(request);
	url = http_request_get_url(request);
	cseq = http_request_get_header(request, "CSeq");
	if (!method || !cseq) {
		return;
	}

	*response = http_response_init("RTSP/1.0", 200, "OK");

	http_response_add_header(*response, "CSeq", cseq);
	//http_response_add_header(*response, "Apple-Jack-Status", "connected; type=analog");
	http_response_add_header(*response, "Server", "AirTunes/220.68");

	logger_log(conn->raop->logger, LOGGER_DEBUG, "Handling request %s with URL %s", method, url);
	raop_handler_t handler = NULL;
	if (!strcmp(method, "GET") && !strcmp(url, "/info")) {
		handler = &raop_handler_info;
	} else if (!strcmp(method, "POST") && !strcmp(url, "/pair-setup")) {
		handler = &raop_handler_pairsetup;
	} else if (!strcmp(method, "POST") && !strcmp(url, "/pair-verify")) {
		handler = &raop_handler_pairverify;
	} else if (!strcmp(method, "POST") && !strcmp(url, "/fp-setup")) {
		handler = &raop_handler_fpsetup;
	} else if (!strcmp(method, "OPTIONS")) {
		handler = &raop_handler_options;
	} else if (!strcmp(method, "SETUP")) {
		handler = &raop_handler_setup;
	} else if (!strcmp(method, "GET_PARAMETER")) {
		handler = &raop_handler_get_parameter;
	} else if (!strcmp(method, "SET_PARAMETER")) {
		handler = &raop_handler_set_parameter;
	} else if (!strcmp(method, "POST") && !strcmp(url, "/feedback")) {
		handler = &raop_handler_feedback;
	} else if (!strcmp(method, "RECORD")) {
		handler = &raop_handler_record;
	} else if (!strcmp(method, "FLUSH")) {
		const char *rtpinfo;
		int next_seq = -1;

		rtpinfo = http_request_get_header(request, "RTP-Info");
		if (rtpinfo) {
			logger_log(conn->raop->logger, LOGGER_INFO, "Flush with RTP-Info: %s", rtpinfo);
			if (!strncmp(rtpinfo, "seq=", 4)) {
				next_seq = strtol(rtpinfo+4, NULL, 10);
			}
		}
		if (conn->raop_rtp) {
			raop_rtp_flush(conn->raop_rtp, next_seq);
		} else {
			logger_log(conn->raop->logger, LOGGER_WARNING, "RAOP not initialized at FLUSH");
		}
	} else if (!strcmp(method, "TEARDOWN")) {
		conn_notify_stream_status(conn, "Stream teardown received");
		http_response_add_header(*response, "Connection", "close");
		int teardown_audio = 0;
		int teardown_mirror = 0;
		int has_teardown_stream_type = request_get_stream_types(request, &teardown_audio, &teardown_mirror);
		if (!has_teardown_stream_type) {
			teardown_audio = 1;
			teardown_mirror = 1;
		}
		int had_audio_stream = teardown_audio && conn->audio_started && conn->raop_rtp;
		int had_mirror_stream = teardown_mirror && conn->mirror_started && conn->raop_rtp_mirror;
		if (teardown_audio && conn->raop_rtp) {
			raop_remove_active_rtp(conn->raop, conn->raop_rtp);
			/* Destroy our RTP session */
			raop_rtp_destroy(conn->raop_rtp);
			conn->raop_rtp = NULL;
			conn->audio_started = 0;
		}
		if (teardown_audio && conn->raop_rtp_mirror && !conn->mirror_started) {
			raop_remove_active_mirror(conn->raop, conn->raop_rtp_mirror);
			raop_rtp_mirror_destroy(conn->raop_rtp_mirror);
			conn->raop_rtp_mirror = NULL;
		}
		if (teardown_mirror && conn->raop_rtp_mirror) {
			raop_remove_active_mirror(conn->raop, conn->raop_rtp_mirror);
			/* Destroy our mirror session */
			raop_rtp_mirror_destroy(conn->raop_rtp_mirror);
			conn->raop_rtp_mirror = NULL;
			conn->mirror_started = 0;
		}
		if (had_mirror_stream) {
			conn_notify_stream_stopped(conn);
		} else if (had_audio_stream) {
			conn_notify_stream_status(conn, "Audio stream teardown handled");
		}
	}
	if (handler != NULL) {
		handler(conn, request, *response, &response_data, &response_datalen);
	}
	http_response_finish(*response, response_data, response_datalen);
	if (response_data) {
		free(response_data);
		response_data = NULL;
		response_datalen = 0;
	}
}

static void
conn_destroy(void *ptr)
{
	raop_conn_t *conn = ptr;

	if (conn->raop_rtp || conn->raop_rtp_mirror) {
		logger_log(conn->raop->logger, LOGGER_INFO,
		           "Control connection closed while media session is active; keeping media session alive");
	}
	if (conn->raop_rtp && !raop_has_active_rtp(conn->raop, conn->raop_rtp)) {
		raop_rtp_destroy(conn->raop_rtp);
	}
	if (conn->raop_rtp_mirror && !raop_has_active_mirror(conn->raop, conn->raop_rtp_mirror)) {
		raop_rtp_mirror_destroy(conn->raop_rtp_mirror);
	}
	conn->raop_rtp = NULL;
	conn->raop_rtp_mirror = NULL;
	free(conn->local);
	free(conn->remote);
	pairing_session_destroy(conn->pairing);
	fairplay_destroy(conn->fairplay);
	free(conn);
}

raop_t *
raop_init(int max_clients, raop_callbacks_t *callbacks)
{
	raop_t *raop;
	pairing_t *pairing;
	httpd_t *httpd;
	httpd_callbacks_t httpd_cbs;

	assert(callbacks);
	assert(max_clients > 0);
	assert(max_clients < 100);

	/* Initialize the network */
	if (netutils_init() < 0) {
		return NULL;
	}

	/* Validate the callbacks structure */
	if (!callbacks->audio_process) {
		return NULL;
	}

	/* Allocate the raop_t structure */
	raop = calloc(1, sizeof(raop_t));
	if (!raop) {
		return NULL;
	}

	/* Initialize the logger */
	raop->logger = logger_init();
	pairing = pairing_init_generate();
	if (!pairing) {
		free(raop);
		return NULL;
	}

	/* Set HTTP callbacks to our handlers */
	memset(&httpd_cbs, 0, sizeof(httpd_cbs));
	httpd_cbs.opaque = raop;
	httpd_cbs.conn_init = &conn_init;
	httpd_cbs.conn_request = &conn_request;
	httpd_cbs.conn_destroy = &conn_destroy;

	/* Initialize the http daemon */
	httpd = httpd_init(raop->logger, &httpd_cbs, max_clients);
	if (!httpd) {
		pairing_destroy(pairing);
		free(raop);
		return NULL;
	}
	/* Copy callbacks structure */
	memcpy(&raop->callbacks, callbacks, sizeof(raop_callbacks_t));
	raop->pairing = pairing;
	raop->httpd = httpd;
	return raop;
}

void
raop_destroy(raop_t *raop)
{
	if (raop) {
		raop_stop(raop);
		raop_destroy_active_sessions(raop);

		pairing_destroy(raop->pairing);
		httpd_destroy(raop->httpd);
		logger_destroy(raop->logger);
		free(raop);

		/* Cleanup the network */
		netutils_cleanup();
	}
}

int
raop_is_running(raop_t *raop)
{
	assert(raop);

	return httpd_is_running(raop->httpd);
}

void
raop_set_log_level(raop_t *raop, int level)
{
	assert(raop);

	logger_set_level(raop->logger, level);
}

void
raop_set_port(raop_t *raop, unsigned short port)
{
    assert(raop);
    raop->port = port;
}

unsigned short
raop_get_port(raop_t *raop)
{
    assert(raop);
    return raop->port;
}

void *
raop_get_callback_cls(raop_t *raop)
{
    assert(raop);
    return raop->callbacks.cls;
}

void
raop_set_log_callback(raop_t *raop, raop_log_callback_t callback, void *cls)
{
	assert(raop);

	logger_set_callback(raop->logger, callback, cls);
}

int
raop_start(raop_t *raop, unsigned short *port)
{
	assert(raop);
	assert(port);
	return httpd_start(raop->httpd, port);
}

void
raop_stop(raop_t *raop)
{
	assert(raop);
	httpd_stop(raop->httpd);
}
