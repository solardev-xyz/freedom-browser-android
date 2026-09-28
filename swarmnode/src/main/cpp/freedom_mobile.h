/*
 * freedom-mobile-ffi's own C surface: what only the crate linking ant
 * (`ant.h`) and freedom-ipfs (`freedom_ipfs.h`) together can do.
 */
#ifndef FREEDOM_MOBILE_H
#define FREEDOM_MOBILE_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Install the process's one `tracing` subscriber with both nodes' layers:
 * ant's log output (logcat tag "ant-ffi" on Android, stderr elsewhere)
 * and freedom-ipfs's retrieval-progress recorder, which feeds
 * freedom_ipfs_node_progress_snapshot_json.
 *
 * Call it before ant_init* and freedom_ipfs_node_new*. Each node otherwise
 * installs its own layer as the global subscriber, the first one wins, and
 * with ant first the IPFS progress snapshot stays empty for good.
 *
 * Idempotent and thread-safe. Returns true when the installed subscriber
 * is this one (now or from an earlier call), false when something else had
 * already claimed the slot; nothing changes then.
 */
bool freedom_mobile_init_logging(void);

#ifdef __cplusplus
}
#endif

#endif /* FREEDOM_MOBILE_H */
