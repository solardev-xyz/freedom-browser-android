/*
 * freedom-mobile-ffi feature `tor`: an embedded Arti (Tor) client behind a
 * loopback SOCKS5 listener that only ever connects to `.onion` hosts. A
 * CONNECT to an IP literal or a clearnet name is refused (SOCKS reply 0x02),
 * so a misrouted request never leaves through a Tor exit. src/tor.rs.
 *
 * One client per process. Strings returned as `char *` are freed with
 * freedom_tor_string_free.
 */
#ifndef FREEDOM_TOR_H
#define FREEDOM_TOR_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Start the client if it isn't running and return the port of its SOCKS5
 * listener on 127.0.0.1 (socks_port 0 = any free port). state_dir and
 * cache_dir are Arti's state (guards) and directory cache. Bootstrap runs
 * in the background; poll freedom_tor_status_json. Returns the running
 * listener's port if already started. On failure returns 0 and sets
 * *err_out (when non-null) to a message.
 */
uint16_t freedom_tor_start(const char *state_dir, const char *cache_dir,
                           uint16_t socks_port, char **err_out);

/* Stop the client: closes the listener and every stream. No-op if stopped. */
void freedom_tor_stop(void);

/*
 * {"state":"stopped"} or {"state":"bootstrapping"|"running","port":N,
 * "progress":0..1,"summary":"...","blocked":"..."|null,"error":"..."|null}.
 * "running" = ready for traffic. Never null.
 */
char *freedom_tor_status_json(void);

/* The linked Arti version, e.g. "0.46.0". Static; do not free. */
const char *freedom_tor_version(void);

void freedom_tor_string_free(char *s);

#ifdef __cplusplus
}
#endif

#endif /* FREEDOM_TOR_H */
