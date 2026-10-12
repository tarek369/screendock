/*
 * android_event_channel.c — see android_event_channel.h.
 *
 * The reverse-HTTP request is built with UxPlay's http_response helpers (same
 * technique as lib/fcup_request.h) and the body is a binary plist:
 *   { type: "touchStart"|"touchMove"|"touchEnd", total: 2,
 *     touching: [ { ID: 1, xNorm: x, yNorm: y } ] }
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "android_event_channel.h"

#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <errno.h>
#include <stdbool.h>
#include <sys/socket.h>
#include <sys/select.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <android/log.h>

#include <plist/plist.h>

#include "http_response.h"
#include "compat.h"

#define TAG "AirplayNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define SEND_TIMEOUT_SEC 2   /* a stalled sender must not wedge the touch/UI thread */

struct event_channel_s {
    int listen_fd;
    int conn_fd;                 /* sender's connection, -1 until accepted */
    int reversed;                /* 1 after the PTTH upgrade completed */
    int cseq;                    /* RTSP sequence number for our requests */
    pthread_mutex_t mu;          /* guards conn_fd, reversed, cseq + every send.
                                  * Only the event thread ever close()s conn_fd (under
                                  * mu); senders merely shutdown() a broken socket so
                                  * the thread notices — avoids fd-reuse races. */
    volatile int running;
    pthread_t thread;
};

static void *event_channel_thread(void *arg)
{
    event_channel_t *ch = (event_channel_t *) arg;
    while (ch->running) {
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(ch->listen_fd, &rfds);
        int maxfd = ch->listen_fd;
        int cfd;
        pthread_mutex_lock(&ch->mu);
        cfd = ch->conn_fd;
        pthread_mutex_unlock(&ch->mu);
        if (cfd >= 0) { FD_SET(cfd, &rfds); if (cfd > maxfd) maxfd = cfd; }

        struct timeval tv = { 1, 0 };
        int rc = select(maxfd + 1, &rfds, NULL, NULL, &tv);
        if (rc <= 0) continue;

        if (cfd >= 0 && FD_ISSET(cfd, &rfds)) {
            /* Drain whatever the sender pushed so its buffers never fill. */
            char buf[512];
            ssize_t n = recv(cfd, buf, sizeof(buf) - 1, 0);
            if (n <= 0) {
                LOGW("event channel: sender closed the connection");
                pthread_mutex_lock(&ch->mu);
                close(ch->conn_fd);
                ch->conn_fd = -1;
                ch->reversed = 0;
                pthread_mutex_unlock(&ch->mu);
            } else {
                buf[n] = '\0';
                LOGI("event channel: received %d bytes: %.200s", (int) n, buf);
                /* Senders open the event channel with POST /reverse and wait
                 * for the reverse-HTTP (PTTH/1.0) upgrade before they will
                 * accept our POST /event requests. */
                if (!ch->reversed && strstr(buf, "/reverse") != NULL) {
                    static const char upgrade[] =
                        "HTTP/1.1 101 Switching Protocols\r\n"
                        "Upgrade: PTTH/1.0\r\n"
                        "Connection: Upgrade\r\n\r\n";
                    pthread_mutex_lock(&ch->mu);
                    if (ch->conn_fd == cfd &&
                        send(cfd, upgrade, sizeof(upgrade) - 1, MSG_NOSIGNAL) > 0) {
                        ch->reversed = 1;
                        LOGI("event channel: PTTH upgrade complete (101)");
                    }
                    pthread_mutex_unlock(&ch->mu);
                }
            }
        }
        if (FD_ISSET(ch->listen_fd, &rfds)) {
            struct sockaddr_storage ss;
            socklen_t slen = sizeof(ss);
            int nfd = accept(ch->listen_fd, (struct sockaddr *) &ss, &slen);
            if (nfd >= 0) {
                int one = 1;
                setsockopt(nfd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
                struct timeval snd = { SEND_TIMEOUT_SEC, 0 };
                setsockopt(nfd, SOL_SOCKET, SO_SNDTIMEO, &snd, sizeof(snd));
                char ip[INET6_ADDRSTRLEN] = { 0 };
                if (ss.ss_family == AF_INET6) {
                    inet_ntop(AF_INET6, &((struct sockaddr_in6 *) &ss)->sin6_addr, ip, sizeof(ip));
                } else {
                    inet_ntop(AF_INET, &((struct sockaddr_in *) &ss)->sin_addr, ip, sizeof(ip));
                }
                LOGI("event channel: sender connected from %s", ip);
                pthread_mutex_lock(&ch->mu);
                if (ch->conn_fd >= 0) close(ch->conn_fd);
                ch->conn_fd = nfd;
                ch->reversed = 0;
                ch->cseq = 0;
                pthread_mutex_unlock(&ch->mu);
            }
        }
    }
    return NULL;
}

event_channel_t *event_channel_start(unsigned short *port)
{
    event_channel_t *ch = calloc(1, sizeof(*ch));
    if (!ch) return NULL;
    ch->listen_fd = -1;
    ch->conn_fd = -1;
    pthread_mutex_init(&ch->mu, NULL);

    /* Dual-stack listener (IPv6 with V6ONLY=0): senders may reach us over
     * IPv6 link-local OR IPv4 — a v4-only bind gets ECONNREFUSED for the
     * v6 attempt and macOS aborts the whole mirroring session. */
    ch->listen_fd = socket(AF_INET6, SOCK_STREAM, 0);
    if (ch->listen_fd < 0) { LOGE("event channel: socket failed"); goto fail; }
    int v6only = 0;
    setsockopt(ch->listen_fd, IPPROTO_IPV6, IPV6_V6ONLY, &v6only, sizeof(v6only));
    struct sockaddr_in6 sa;
    memset(&sa, 0, sizeof(sa));
    sa.sin6_family = AF_INET6;
    sa.sin6_addr = in6addr_any;
    sa.sin6_port = htons(*port);
    if (bind(ch->listen_fd, (struct sockaddr *) &sa, sizeof(sa)) < 0 ||
        listen(ch->listen_fd, 1) < 0) {
        LOGE("event channel: bind/listen failed errno=%d", errno);
        goto fail;
    }
    struct sockaddr_in6 actual;
    socklen_t alen = sizeof(actual);
    getsockname(ch->listen_fd, (struct sockaddr *) &actual, &alen);
    *port = ntohs(actual.sin6_port);

    ch->running = 1;
    if (pthread_create(&ch->thread, NULL, event_channel_thread, ch) != 0) {
        LOGE("event channel: pthread_create failed");
        goto fail;
    }
    return ch;

fail:
    if (ch->listen_fd >= 0) close(ch->listen_fd);
    pthread_mutex_destroy(&ch->mu);
    free(ch);
    return NULL;
}

static const char *phase_to_type(int phase)
{
    switch (phase) {
        case 0: return "touchStart";
        case 1: return "touchMove";
        default: return "touchEnd";
    }
}

int event_channel_send_touch(event_channel_t *ch, int phase, double x, double y,
                             const char *session_uuid)
{
    if (!ch) return -1;
    pthread_mutex_lock(&ch->mu);
    int fd = ch->conn_fd;
    if (fd < 0) {
        pthread_mutex_unlock(&ch->mu);
        return -1;
    }

    /* Build the touch plist body (XML — the format macOS accepts for /event
     * posts, same as UxPlay's proven FCUP exchange). */
    plist_t root = plist_new_dict();
    plist_dict_set_item(root, "type", plist_new_string(phase_to_type(phase)));
    plist_dict_set_item(root, "total", plist_new_uint(2));
    plist_t touching = plist_new_array();
    plist_t point = plist_new_dict();
    plist_dict_set_item(point, "ID", plist_new_uint(1));
    plist_dict_set_item(point, "xNorm", plist_new_real(x));
    plist_dict_set_item(point, "yNorm", plist_new_real(y));
    plist_array_append_item(touching, point);
    plist_dict_set_item(root, "touching", touching);

    char *body = NULL;
    uint32_t bodylen = 0;
    plist_to_xml(root, &body, &bodylen);
    plist_free(root);
    if (!body || bodylen == 0) {
        pthread_mutex_unlock(&ch->mu);
        return -1;
    }

    /* Exact clone of the fcup_request() framing that macOS senders are known
     * to accept on a reverse event channel. */
    http_response_t *req = http_response_create();
    http_response_reverse_request_init(req, "POST", "/event", "HTTP/1.1");
    char cseq[16];
    snprintf(cseq, sizeof(cseq), "%d", ++ch->cseq);
    http_response_add_header(req, "CSeq", cseq);
    if (session_uuid && session_uuid[0]) {
        http_response_add_header(req, "X-Apple-Session-ID", session_uuid);
    }
    http_response_add_header(req, "Content-Type", "text/x-apple-plist+xml");
    http_response_finish(req, body, (int) bodylen);
    free(body);

    int reqlen = 0;
    const char *data = http_response_get_data(req, &reqlen);
    int rc = 0;
    if (!data || reqlen <= 0 || send(fd, data, reqlen, MSG_NOSIGNAL) != reqlen) {
        LOGW("event channel: send failed errno=%d", errno);
        /* Connection is unhealthy. Do NOT close(fd) here: the event thread owns
         * the fd and would later close a recycled descriptor. shutdown() makes
         * its recv() return 0, and it closes + clears conn_fd under the lock. */
        shutdown(fd, SHUT_RDWR);
        rc = -1;
    } else {
        if (ch->cseq <= 2) {
            /* One-shot wire dump for protocol debugging (headers + body). */
            char dump[1024];
            int n = reqlen < (int) sizeof(dump) - 1 ? reqlen : (int) sizeof(dump) - 1;
            memcpy(dump, data, n);
            dump[n] = '\0';
            LOGI("event channel: request #%d (%d bytes):\n%.900s", ch->cseq, reqlen, dump);
        } else {
            LOGI("event channel: touch phase=%d (%.3f, %.3f) sent %d bytes",
                 phase, x, y, reqlen);
        }
    }
    http_response_destroy(req);
    pthread_mutex_unlock(&ch->mu);
    return rc;
}

void event_channel_stop(event_channel_t **chp)
{
    if (!chp || !*chp) return;
    event_channel_t *ch = *chp;
    ch->running = 0;
    if (ch->listen_fd >= 0) shutdown(ch->listen_fd, SHUT_RDWR);
    pthread_join(ch->thread, NULL);          /* thread exits within its 1 s select timeout */
    if (ch->listen_fd >= 0) close(ch->listen_fd);
    pthread_mutex_lock(&ch->mu);
    if (ch->conn_fd >= 0) close(ch->conn_fd);
    ch->conn_fd = -1;
    pthread_mutex_unlock(&ch->mu);
    pthread_mutex_destroy(&ch->mu);
    free(ch);
    *chp = NULL;
}
