/*
 * android_dnssd_shim.c — Android mDNS shim.
 *
 * UxPlay's discovery layer is split: lib/dnssd.c allocates a public dnssd_t and
 * delegates networking + TXT construction to a "backend" via dnssd_private_init.
 * The upstream mdnsd backend (lib/mdnsd/dnssd_mdnsd.c) does BOTH the TXT-record
 * construction AND the multicast networking on UDP/5353.
 *
 * On Android we don't want the native networking: Android's NsdManager does mDNS
 * for us (battery-aware, handles MulticastLock integration in Kotlin). So we
 * drop the whole mdnsd backend and provide this shim which:
 *   - builds the exact TXT records upstream would (reusing dnssdint.h constants),
 *   - exposes them as raw DNS TXT bytes via dnssd_get_{raop,airplay}_txt,
 *   - performs NO networking. The register/unregister calls are inert.
 *
 * The bridge exposes a parsed Map<String,String> of TXT records to Kotlin via
 * NativeBridge.nativeGet{Raop,Airplay}TxtRecords; NsdServiceManager (M1) applies
 * them through NsdServiceInfo.setAttribute.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include "dnssd.h"
#include "dnssdint.h"
#include "utils.h"
#include "global.h"

#define TXT_MAX 512

/* In-memory TXT record (raw DNS format: a sequence of length-prefixed
 * "key=value" entries, each entry prefixed by its 1-byte length). */
typedef struct {
    unsigned char bytes[TXT_MAX];
    int length;
} android_txt_t;

/* Private backend state hung off dnssd_t->dnssd_private. */
typedef struct {
    android_txt_t raop_txt;
    android_txt_t airplay_txt;

    char raop_name[256];     /* "<hexmac>@<name>._raop._tcp.local" */
    char airplay_name[256];  /* "<name>._airplay._tcp.local"       */

    unsigned short raop_port;
    unsigned short airplay_port;
    int raop_registered;
    int airplay_registered;
} android_dnssd_private_t;

/* Append one "key=value" entry (length-prefixed) to a TXT record. Mirrors
 * mdnsd_txt_add's encoding so the bytes match what a real mDNS responder emits. */
static int txt_add(android_txt_t *txt, const char *key, const char *value)
{
    int klen = (int) strlen(key);
    int vlen = (int) strlen(value);
    /* entry = [1-byte len][key][=][value]; len counts only key=value bytes */
    int entry_len = klen + 1 + vlen;
    if (entry_len > 255) entry_len = 255;   /* DNS TXT entry cap */
    if (txt->length + 1 + entry_len > TXT_MAX) return -1;

    txt->bytes[txt->length++] = (unsigned char) entry_len;
    memcpy(txt->bytes + txt->length, key, (size_t) klen);
    txt->length += klen;
    txt->bytes[txt->length++] = '=';
    memcpy(txt->bytes + txt->length, value, (size_t) (entry_len - klen - 1));
    txt->length += (entry_len - klen - 1);
    return 0;
}

static int build_raop_txt(dnssd_t *dnssd_public)
{
    android_dnssd_private_t *p = (android_dnssd_private_t *) dnssd_public->dnssd_private;
    char features[22];
    const char *pw = "false";
    const char *sf = RAOP_SF;

    switch (dnssd_public->pin_pw) {
        case 1:  pw = "true"; sf = "0x8c"; break;
        case 2:
        case 3:  pw = "true"; sf = "0x84"; break;
        default: break;
    }
    snprintf(features, sizeof(features), "0x%X,0x%X",
             dnssd_public->features1, dnssd_public->features2);

    p->raop_txt.length = 0;
    if (txt_add(&p->raop_txt, "ch",      RAOP_CH)      ||
        txt_add(&p->raop_txt, "cn",      RAOP_CN)      ||
        txt_add(&p->raop_txt, "da",      RAOP_DA)      ||
        txt_add(&p->raop_txt, "et",      RAOP_ET)      ||
        txt_add(&p->raop_txt, "vv",      RAOP_VV)      ||
        txt_add(&p->raop_txt, "ft",      features)     ||
        txt_add(&p->raop_txt, "am",      GLOBAL_MODEL) ||
        txt_add(&p->raop_txt, "md",      RAOP_MD)      ||
        txt_add(&p->raop_txt, "rhd",     RAOP_RHD)     ||
        txt_add(&p->raop_txt, "pw",      pw)           ||
        txt_add(&p->raop_txt, "sr",      RAOP_SR)      ||
        txt_add(&p->raop_txt, "ss",      RAOP_SS)      ||
        txt_add(&p->raop_txt, "sv",      RAOP_SV)      ||
        txt_add(&p->raop_txt, "tp",      RAOP_TP)      ||
        txt_add(&p->raop_txt, "txtvers", RAOP_TXTVERS) ||
        txt_add(&p->raop_txt, "sf",      sf)           ||
        txt_add(&p->raop_txt, "vs",      RAOP_VS)      ||
        txt_add(&p->raop_txt, "vn",      RAOP_VN)      ||
        txt_add(&p->raop_txt, "pk",      dnssd_public->pk ? dnssd_public->pk : "")) {
        return -1;
    }
    return 0;
}

static int build_airplay_txt(dnssd_t *dnssd_public)
{
    android_dnssd_private_t *p = (android_dnssd_private_t *) dnssd_public->dnssd_private;
    char device_id[3 * MAX_HWADDR_LEN];
    char features[22];

    if (utils_hwaddr_airplay(device_id, sizeof(device_id),
                             dnssd_public->hw_addr, dnssd_public->hw_addr_len) < 0) {
        return -1;
    }
    snprintf(features, sizeof(features), "0x%X,0x%X",
             dnssd_public->features1, dnssd_public->features2);

    p->airplay_txt.length = 0;
    if (txt_add(&p->airplay_txt, "deviceid", device_id)                   ||
        txt_add(&p->airplay_txt, "features", features)                    ||
        txt_add(&p->airplay_txt, "pw", dnssd_public->pin_pw ? "true" : "false") ||
        txt_add(&p->airplay_txt, "flags", "0x4")                          ||
        txt_add(&p->airplay_txt, "model", GLOBAL_MODEL)                   ||
        txt_add(&p->airplay_txt, "pk", dnssd_public->pk ? dnssd_public->pk : "") ||
        txt_add(&p->airplay_txt, "pi", AIRPLAY_PI)                        ||
        txt_add(&p->airplay_txt, "srcvers", AIRPLAY_SRCVERS)              ||
        txt_add(&p->airplay_txt, "vv", AIRPLAY_VV)) {
        return -1;
    }
    return 0;
}

static int prepare_names(android_dnssd_private_t *p, dnssd_t *pub)
{
    char hw[2 * MAX_HWADDR_LEN + 1];
    if (utils_hwaddr_raop(hw, sizeof(hw), pub->hw_addr, pub->hw_addr_len) < 0) return -1;
    if (snprintf(p->raop_name, sizeof(p->raop_name), "%s@%s._raop._tcp.local", hw, pub->name)
            >= (int) sizeof(p->raop_name)) return -1;
    if (snprintf(p->airplay_name, sizeof(p->airplay_name), "%s._airplay._tcp.local", pub->name)
            >= (int) sizeof(p->airplay_name)) return -1;
    return 0;
}

/* ---- dnssd.h facade implementation (no networking) -------------------- */

void *dnssd_private_init(dnssd_t *dnssd_public, int *error)
{
    if (error) *error = DNSSD_ERROR_NOERROR;
    android_dnssd_private_t *p = (android_dnssd_private_t *) calloc(1, sizeof(*p));
    if (!p) { if (error) *error = DNSSD_ERROR_OUTOFMEM; return NULL; }
    if (prepare_names(p, dnssd_public)) {
        free(p);
        if (error) *error = DNSSD_ERROR_HWADDRLEN;
        return NULL;
    }
    return p;
}

void dnssd_private_destroy(void *dnssd_private)
{
    free(dnssd_private);
}

int dnssd_register_raop(dnssd_t *dnssd_public, unsigned short port)
{
    android_dnssd_private_t *p = (android_dnssd_private_t *) dnssd_public->dnssd_private;
    if (build_raop_txt(dnssd_public)) return -1;
    p->raop_port = port;
    p->raop_registered = 1;
    return 0;   /* actual registration happens in Kotlin via NsdManager (M1) */
}

int dnssd_register_airplay(dnssd_t *dnssd_public, unsigned short port)
{
    android_dnssd_private_t *p = (android_dnssd_private_t *) dnssd_public->dnssd_private;
    if (build_airplay_txt(dnssd_public)) return -1;
    p->airplay_port = port;
    p->airplay_registered = 1;
    return 0;
}

void dnssd_unregister_raop(dnssd_t *dnssd_public)
{
    android_dnssd_private_t *p = (android_dnssd_private_t *) dnssd_public->dnssd_private;
    p->raop_registered = 0;
}

void dnssd_unregister_airplay(dnssd_t *dnssd_public)
{
    android_dnssd_private_t *p = (android_dnssd_private_t *) dnssd_public->dnssd_private;
    p->airplay_registered = 0;
}

const char *dnssd_get_raop_txt(dnssd_t *dnssd_public, int *length)
{
    android_dnssd_private_t *p = (android_dnssd_private_t *) dnssd_public->dnssd_private;
    *length = p->raop_txt.length;
    return (const char *) p->raop_txt.bytes;
}

const char *dnssd_get_airplay_txt(dnssd_t *dnssd_public, int *length)
{
    android_dnssd_private_t *p = (android_dnssd_private_t *) dnssd_public->dnssd_private;
    *length = p->airplay_txt.length;
    return (const char *) p->airplay_txt.bytes;
}

/* Bridge helper (not part of UxPlay's dnssd.h): returns the RAOP instance name
 * "<hexmac>@<name>" so NsdServiceManager can register the right service name.
 * Exported for native_bridge.cpp only. */
const char *android_dnssd_get_raop_name(dnssd_t *dnssd_public)
{
    android_dnssd_private_t *p = (android_dnssd_private_t *) dnssd_public->dnssd_private;
    return p->raop_name;   /* full "<mac>@<name>._raop._tcp.local" */
}

void dnssd_error_text(int *dnssd_error, const char *appname)
{
    (void) appname;
    (void) dnssd_error;
    /* No-op: this shim never does socket work. */
}
