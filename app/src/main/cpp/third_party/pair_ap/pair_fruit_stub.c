/* pair.c's dispatch table references the legacy "fruit" (Apple TV) pairing,
 * which ScreenDock does not use (UxPlay implements legacy pairing itself).
 * An empty definition keeps the table in sync with enum pair_type without
 * pulling in libplist/SRP-SHA1 code. SPDX-License-Identifier: GPL-3.0-or-later */
#include "pair-internal.h"

struct pair_definition pair_client_fruit;
