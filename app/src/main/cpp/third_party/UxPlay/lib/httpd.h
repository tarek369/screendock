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

#ifndef HTTPD_H
#define HTTPD_H

#include "logger.h"
#include "http_request.h"
#include "http_response.h"

typedef struct httpd_s httpd_t;

typedef enum connectype_type_e {
    CONNECTION_TYPE_UNKNOWN,
    CONNECTION_TYPE_RAOP,
    CONNECTION_TYPE_AIRPLAY,
    CONNECTION_TYPE_PTTH,
    CONNECTION_TYPE_HLS
} connection_type_t;

struct httpd_callbacks_s {
    void* opaque;
    void* (*conn_init)(void *opaque, unsigned char *local, int locallen, unsigned char *remote,
                       int remotelen, unsigned int zone_id);
    void  (*conn_request)(void *ptr, http_request_t *request, http_response_t **response);
    void  (*conn_destroy)(void *ptr);
    /* Optional per-connection transport encryption (AirPlay 2 HAP control channel,
     * Android port). All four are NULL for the legacy plaintext behaviour.
     *  conn_crypto_active: nonzero while bytes on this connection are encrypted.
     *  conn_decrypt: feed raw bytes; returns plaintext length (>=0, *out malloc'd or
     *                NULL when 0) or <0 on error (connection is dropped).
     *  conn_encrypt: returns ciphertext length (*out malloc'd) or <0 on error.
     *  conn_response_sent: called after each response was written, so a handler that
     *                just completed pairing can start encrypting AFTER its plaintext
     *                final response went out. */
    int   (*conn_crypto_active)(void *ptr);
    int   (*conn_decrypt)(void *ptr, const unsigned char *in, int in_len, unsigned char **out);
    int   (*conn_encrypt)(void *ptr, const unsigned char *in, int in_len, unsigned char **out);
    void  (*conn_response_sent)(void *ptr);
};
typedef struct httpd_callbacks_s httpd_callbacks_t;
bool httpd_nohold(httpd_t *httpd);
void httpd_remove_known_connections(httpd_t *httpd);
void httpd_remove_connections_by_type(httpd_t *httpd, connection_type_t type);

int httpd_set_connection_type (httpd_t *http, void *user_data, connection_type_t type);
int httpd_count_connection_type (httpd_t *http, connection_type_t type);
int httpd_get_connection_socket (httpd_t *httpd, void *user_data);
int httpd_get_connection_socket_by_type (httpd_t *httpd, connection_type_t type, int instance);
const char *httpd_get_connection_typename (connection_type_t type);
void *httpd_get_connection_by_type (httpd_t *httpd, connection_type_t type, int instance);
httpd_t *httpd_init(logger_t *logger, httpd_callbacks_t *callbacks, int  nohold);

int httpd_is_running(httpd_t *httpd);

int httpd_start(httpd_t *httpd, unsigned short *port);
void httpd_stop(httpd_t *httpd);

void httpd_destroy(httpd_t *httpd);


#endif
