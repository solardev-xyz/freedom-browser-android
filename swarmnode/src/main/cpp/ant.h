/*
 * ant.h — hand-written C interface for the iOS download smoke test.
 *
 * Companion to `crates/ant-ffi/src/lib.rs`. Kept hand-written (not
 * cbindgen-generated) for the PLAN.md § 9 smoke test so that the
 * surface area is obvious from reading the header. The proper mobile
 * artefact replaces this with a UniFFI-generated `.udl` (§ 7).
 *
 * Memory model
 * ------------
 *   * `AntHandle*`          owned by ant, freed by `ant_shutdown`.
 *   * `unsigned char*` body owned by ant, freed by `ant_free_buffer`.
 *   * `char*`          msg  owned by ant, freed by `ant_free_string`.
 *
 * None of the pointers may be passed to `free(3)` directly.
 *
 * The one pointer that travels the other way is the JSON-RPC response
 * body an `ant_chain_transport` callback returns: the host `malloc`s
 * it, ant takes ownership and releases it with `free(3)`. See
 * `ant_set_chain_transport`.
 */

#ifndef ANT_FFI_H
#define ANT_FFI_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct AntHandle AntHandle;
typedef struct AntStream AntStream;

/*
 * POD mirror of the Rust `AntProgress` struct. Field order must match
 * `crates/ant-ffi/src/lib.rs`; Swift treats this as a fixed-layout C
 * struct.
 *
 *   in_progress     1 while ant_download is active; 0 otherwise.
 *   bytes_done      bytes joined so far (chunk wire bytes).
 *   total_bytes     expected file body size, 0 until the data root
 *                   chunk has been fetched.
 *   chunks_done     chunks delivered to the joiner (cache + network).
 *   total_chunks    estimated chunk count, 0 until the root span is
 *                   known.
 *   elapsed_ms      ms since the node accepted the request.
 *   peers_used      distinct peers that served a chunk so far.
 *   in_flight       chunks currently dispatched (proxy for load).
 *   cache_hits      chunk deliveries served from the local cache.
 */
typedef struct AntProgress {
  uint8_t  in_progress;
  uint64_t bytes_done;
  uint64_t total_bytes;
  uint64_t chunks_done;
  uint64_t total_chunks;
  uint64_t elapsed_ms;
  uint32_t peers_used;
  uint32_t in_flight;
  uint64_t cache_hits;
} AntProgress;

/*
 * Spin up an embedded light-node against Swarm mainnet. `data_dir`
 * points at a writable directory (the iOS Application Support sandbox
 * works). Identity + peer snapshot + in-memory chunk cache live there.
 *
 * On success returns a non-NULL handle. On failure returns NULL and
 * writes an allocated error string to *out_err (caller frees with
 * ant_free_string). Pass NULL for out_err to opt out of error
 * reporting.
 */
AntHandle *ant_init(const char *data_dir, char **out_err);

/*
 * Like ant_init, with embedder options. `source_root` (nullable) names
 * the directory the host stages upload sources under (e.g. the iOS
 * app's Application Support/antdrive/imports). The upload manager then
 * records each job's source path *relative* to that root and, when the
 * OS relocates the app container (iOS assigns a new container UUID on
 * updates/reinstalls), re-anchors the stale absolute path as
 * `source_root/relative` — guarded by a size+mtime match — so persisted
 * uploads keep resuming and self-healing across the move. Applied
 * before persisted jobs are rehydrated, which is why it is an init
 * option rather than a post-init call. Pass NULL for source_root to
 * behave exactly like ant_init.
 */
AntHandle *ant_init_with_options(const char *data_dir,
                                 const char *source_root,
                                 char **out_err);

/*
 * Like ant_init_with_options, but the *host* owns the account key.
 * `identity_json` carries the identity document (the shape
 * ant_identity_generate returns) and the library neither reads nor
 * writes `identity.json` in the data dir — so on iOS the key can live
 * in the Keychain (optionally Secure-Enclave-wrapped) instead of the
 * app container. This is the `KeyProvider` backend PLAN.md §5.10 plans
 * for mobile. `source_root` behaves exactly as above (pass NULL to
 * disable the rebase).
 *
 * Because the host can hand over a *different* account than last time
 * (a restore-from-backup-key flow), every init re-scopes the data dir's
 * account-owned state — postage batches, the chequebook association and
 * both SWAP ledgers — to the account in `identity_json`: another
 * account's copy is parked under `<data_dir>/accounts/<its address>/`
 * (never deleted) and this account's parked copy, if any, is swapped
 * back in. Stamps signed over someone else's batch and cheques drawn on
 * someone else's chequebook are rejected by every peer, so the swap has
 * to happen before the node loop starts rather than at first use.
 *
 * On success returns a non-NULL handle. On failure returns NULL and
 * writes an allocated error string to *out_err (free with
 * ant_free_string).
 */
AntHandle *ant_init_with_identity(const char *data_dir,
                                  const char *source_root,
                                  const char *identity_json,
                                  char **out_err);

/*
 * Mint a fresh node identity without starting a node, so a host that
 * keeps the key itself can create one on first run and hand it back to
 * ant_init_with_identity.
 *
 * Returns an allocated JSON document
 *   {"signing_key","overlay_nonce","libp2p_keypair"}
 * — all hex, and all secret: `signing_key` *is* the account. Free with
 * ant_free_string. On failure returns NULL and writes an allocated
 * message into *out_err.
 */
char *ant_identity_generate(char **out_err);

/*
 * Rebuild a node identity from a backed-up account key (64 hex chars, a
 * leading `0x` and surrounding whitespace are tolerated) — the "restore
 * my account" path when the host's copy is gone but the user still has
 * the key from ant_account_export_key. Returns the same JSON document
 * as ant_identity_generate, with the overlay nonce derived from the
 * account address so the restore is reproducible across devices.
 * Malformed or out-of-range keys are rejected with an error rather than
 * failing later at node startup.
 */
char *ant_identity_from_key(const char *signing_key_hex, char **out_err);

/*
 * Download a Swarm reference. Accepted forms:
 *
 *   64-hex          single-chunk or multi-chunk /bytes tree
 *   bytes://<hex>   explicit bytes-tree form
 *   bzz://<hex>     mantaray manifest, resolves `website-index-document`
 *   bzz://<hex>/p   mantaray manifest with explicit path
 *
 * On success returns a heap-allocated body buffer and writes its
 * length into *out_len; free the buffer with ant_free_buffer. On
 * failure returns NULL, writes an allocated NUL-terminated error
 * string into *out_err, and sets *out_len to 0 if out_len is non-NULL.
 * Thread-safe: multiple threads may call ant_download concurrently
 * against the same handle; the embedded node serialises dispatch
 * through its control-command channel.
 */
unsigned char *ant_download(AntHandle *handle,
                            const char *reference,
                            size_t *out_len,
                            char **out_err);

/*
 * Number of connected peers whose connection answers pings (libp2p
 * connections, including peers still in the BZZ handshake). A connection
 * that stops answering (a socket reaped during an OS suspension, an
 * expired NAT mapping) stops being counted within ~20 s of its last pong
 * and is closed ~10 s later; after the process was frozen, a connection
 * that doesn't answer within ~12 s of the node running again is
 * uncounted. Cheap — reads the last-published status snapshot without
 * blocking. Returns -1 if `handle` is NULL.
 */
int ant_peer_count(const AntHandle *handle);

/*
 * Prompt the running node to recover after an OS suspension (e.g. an iOS
 * background reap): re-warm the dial queue and re-dial bootstrap + known
 * peers, WITHOUT a full ant_shutdown / ant_init. Cheap and idempotent —
 * safe to call on every foreground transition.
 *
 * After a long suspension the in-process node's libp2p connections can be
 * half-open (sockets reaped, no FIN seen). The node notices that on its
 * own: connections that stop answering pings are closed ~20-30 s after
 * it runs again, and a streak of retrievals failing on the link makes it
 * do what this call does (at most once a minute). This call skips that
 * wait: it re-opens live sockets to the bootnodes in parallel at once, so
 * retrieval has working routes on foreground instead of after a failed
 * retrieval. It recovers the swarm only — if the gateway's localhost
 * listener was also torn down, rebind it separately with
 * ant_stop_gateway + ant_start_gateway.
 *
 * Returns 0 on success, -1 if `handle` is NULL, and -2 if the node loop
 * didn't ack (already shut down) — in which case an allocated error string
 * is written into *out_err (free with ant_free_string). `out_err` may be
 * NULL to opt out of error reporting.
 */
int ant_resume(const AntHandle *handle, char **out_err);

/*
 * Bee's swap-enable: switch SWAP settlement on or off for the running
 * node, for downloads (issue #121) and uploads (issue #127) alike. On
 * (the default), the node pays peers with SWAP cheques from its
 * chequebook once the debt to a peer reaches half its payment threshold,
 * after the free pseudosettle refresh, as bee does: one balance per
 * peer, cheques worth units x exchange + deduction at bee's oracle rate
 * (up to ~0.75 xBZZ per GB), never more than the chequebook holds. That
 * lifts downloads past the free tier's ~5-6 Mbit/s. Off keeps downloads
 * and uploads on the free tier even with a funded chequebook.
 *
 * Payments also need a chequebook with funds the node has read from the
 * chain: they start after the first settlement setup that has an RPC
 * (ant_start_gateway's chain init, a storage call,
 * ant_deploy_chequebook), not at ant_init. The switch is not persisted;
 * set it after every ant_init.
 *
 * Returns 0 on success, -1 if `handle` is NULL, and -2 if the node loop
 * didn't ack (already shut down) — in which case an allocated error
 * string is written into *out_err (free with ant_free_string).
 */
int ant_set_swap_enabled(const AntHandle *handle, bool enabled, char **out_err);

/*
 * The node's SWAP settlement state as a JSON object — what the gateway's
 * GET /v0/settlement/swap and GET /node's "settlement" report, for a host
 * that doesn't run the gateway:
 *   {"supported":bool,"swap_switch":true,"swap_enabled":bool,
 *    "paying":bool,"chequebook":"0x…"|null,"persisted":false}
 * supported: this build pays peers with SWAP cheques once a funded
 * chequebook backs settlement (the `chain` feature). swap_switch: the
 * switch can be changed at runtime (ant_set_swap_enabled). swap_enabled:
 * bee's swap-enable as the node runs it now, for downloads and uploads
 * alike. paying: cheques are being paid right now (switch on, chequebook
 * funds read from the chain and not spent), otherwise the free
 * pseudosettle tier. persisted: always false, the switch resets at
 * ant_init. Never blocks on the network. Free with ant_free_string.
 */
char *ant_swap_status(const AntHandle *handle, char **out_err);

/*
 * Confirm the outstanding liability of `chequebook` ("0x…" hex) after
 * its cheque figures were lost, and let the node pay cheques from it
 * again, for downloads and uploads alike.
 * When the node's outbound cheque ledger (pushsync_outbound.json) is
 * found unparseable it is moved aside and a ".lost" marker is left;
 * while that marker names a loss, the node pays no cheques (downloads
 * or uploads; both stay on the free pseudosettle tier) from any
 * chequebook that used the file (logged at warn, and /chequebook/balance
 * reports chequeLedgerLost). Only this call (or antd
 * --confirm-cheque-liability) clears it, never time or a restart.
 * Confirming accepts that peers paid before the loss hold cheques the
 * node can't see: they may refuse new cheques, and disconnect the node,
 * until its restarted cumulatives pass what they hold. A running node
 * picks it up at its next chequebook-funds read (within a minute). A
 * marker that can't be parsed is replaced by one that confirms only this
 * chequebook (every other one stays lost), so it never has to be deleted
 * by hand. A chequebook deployed after the loss, and one whose ledger was
 * open when the file was lost, aren't affected and need no confirmation.
 *
 * Returns 0 once confirmed, 1 when there was nothing to confirm (no loss
 * on record, or this chequebook already confirmed), -1 if `handle` is
 * NULL or `chequebook` is malformed, -2 if the marker can't be read (an
 * I/O error, not a parse error) or written; on -1/-2 an allocated error string is written into *out_err
 * (free with ant_free_string).
 */
int ant_confirm_cheque_liability(const AntHandle *handle, const char *chequebook, char **out_err);

/*
 * System-suspend the upload subsystem — call when the app is moving to
 * the background or the device just went offline. Every in-flight
 * upload is paused with the "resumes automatically" marker (a job the
 * user paused is left alone) and running securing passes stop at their
 * next opportunity. BLOCKS until the paused upload drivers have drained
 * their in-flight pushes and written their resume checkpoint (bounded
 * node-side at ~5 s) — wrap the call in a beginBackgroundTask and treat
 * its return as "upload state is safely on disk". Idempotent: safe to
 * call repeatedly, in any order with ant_wake, and with nothing
 * uploading.
 *
 * Returns 0 on success, -1 if `handle` is NULL, and -2 if the node loop
 * didn't ack (already shut down) — in which case an allocated error
 * string is written into *out_err (free with ant_free_string).
 */
int ant_suspend(const AntHandle *handle, char **out_err);

/*
 * Undo ant_suspend — call on foreground / network-restored transitions.
 * Restarts only the uploads the suspend paused (a user pause stays
 * paused) and re-queues the securing pass for any completed-but-
 * unverified upload, exactly like a fresh launch does. Cheap and
 * idempotent; safe without a prior suspend. Pair with ant_resume, which
 * re-warms the peer connections after the same suspension — the two
 * recover different halves of the node.
 *
 * Return values match ant_suspend.
 */
int ant_wake(const AntHandle *handle, char **out_err);

/*
 * Snapshot the running node's `agent` string from the live status
 * channel (currently `ant-ffi/<crate-version>`). Useful for displaying
 * the version of the embedded library actually running, independent
 * of the host bundle's version metadata.
 *
 * Caller takes ownership of the returned UTF-8 C string and must free
 * it with `ant_free_string`. Returns NULL if `handle` is NULL.
 */
char *ant_agent_string(const AntHandle *handle);

/*
 * Sample the current download progress. Fills *out and returns 0 on
 * success; returns -1 if `handle` or `out` is NULL. Safe to call at
 * any cadence (the implementation only takes a short mutex).
 *
 * When no download has started yet, `out->in_progress == 0` and every
 * other field is 0. The `in_progress` flag drops back to 0 once
 * ant_download returns (success or failure).
 */
int ant_download_progress(const AntHandle *handle, AntProgress *out);

/*
 * Request cancellation of the in-flight ant_download on `handle`.
 * Checked cooperatively on the next ack loop iteration (typically
 * within ~150 ms). The racing ant_download call returns NULL with
 * the error string "download canceled". No-op when no download is
 * running. Returns 0 on success, -1 if `handle` is NULL.
 */
int ant_cancel_download(const AntHandle *handle);

/*
 * List the entries of a mantaray manifest. `reference` is a
 * `bzz://<64-hex>` (or bare hex) string. Returns a heap-allocated
 * NUL-terminated JSON document of the form
 *   {"entries":[{"path":"...","reference":"...","size":N,
 *               "content_type":"..."},...]}
 * (size and content_type are omitted when not known). Free with
 * ant_free_string. On failure returns NULL with an allocated error in
 * *out_err.
 */
char *ant_list_bzz(AntHandle *handle,
                   const char *reference,
                   char **out_err);

/*
 * Open a streaming session for a single file inside a bzz manifest.
 * Sends a head-only request to learn `total_bytes` + Content-Type
 * (written into *out_total_bytes / *out_content_type — pass NULL to
 * skip). Returns an opaque AntStream* on success; null + *out_err on
 * failure. The caller frees *out_content_type with ant_free_string
 * (when it was non-NULL on entry and non-NULL on return).
 *
 * The stream borrows the AntHandle; closing the handle while a stream
 * is open is undefined behaviour. Always call ant_stream_close before
 * ant_shutdown.
 */
AntStream *ant_stream_open(AntHandle *handle,
                           const char *reference,
                           uint64_t *out_total_bytes,
                           char **out_content_type,
                           char **out_err);

/*
 * Read `len` bytes starting at `offset` into `dst`. Blocks until the
 * range is delivered (or the per-read timeout fires). Returns the
 * number of bytes written on success (<= len; truncated near EOF), or
 * -1 on failure with an allocated error in *out_err.
 *
 * Safe to call from any thread, but callers should serialise reads on
 * the same stream - concurrent reads issue independent range requests
 * to the node and the pattern stops being efficient.
 */
ssize_t ant_stream_read(AntStream *stream,
                        uint64_t offset,
                        size_t len,
                        unsigned char *dst,
                        char **out_err);

/*
 * Pull a contiguous byte range and deliver each joiner-produced chunk
 * to `on_chunk` as soon as it arrives. Unlike ant_stream_read, which
 * blocks until the entire `len` window has buffered, this drives one
 * StreamBzz request through to its terminal StreamDone and invokes
 * the callback for every BytesChunk along the way - matching the
 * shape of AVAssetResourceLoaderDelegate's dataRequest.
 *
 * The callback returns 0 to keep pulling, non-zero to ask the FFI to
 * stop early (e.g. AVPlayer canceled the dataRequest). The FFI then
 * drains the remaining acks for clean shutdown.
 *
 * Returns the total bytes delivered, or -1 on error (with *out_err
 * set if non-null).
 */
typedef int (*AntStreamChunkCb)(void *ctx, const unsigned char *data, size_t len);

int64_t ant_stream_pull(AntStream *stream,
                        uint64_t offset,
                        uint64_t len,
                        AntStreamChunkCb on_chunk,
                        void *ctx,
                        char **out_err);

/*
 * Sample stream-level progress. `bytes_done` is the cumulative bytes
 * AVPlayer has consumed so far for this file; `total_bytes` is what
 * ant_stream_open reported. The chunk / peer / in-flight counters
 * reflect the most recent range request's `Progress` ack. Returns 0 on
 * success, -1 if either pointer is null.
 */
int ant_stream_progress(const AntStream *stream, AntProgress *out);

/*
 * Close a streaming session and free its handle. Null is a no-op.
 */
void ant_stream_close(AntStream *stream);

/* =========================================================================
 * AntDrive: uploads, storage plan, account
 *
 * Each function returns a heap-allocated NUL-terminated string (JSON for
 * the structured calls, a plain id / hex string otherwise), owned by ant
 * and freed with ant_free_string. On failure they return NULL and write
 * an allocated error string into *out_err (also freed with
 * ant_free_string). Pass NULL for out_err to opt out of error reporting.
 * =========================================================================
 */

/*
 * Start an upload job for the file at `path`. `batch_id` selects the
 * storage plan to stamp against (NULL = node default); `name` /
 * `content_type` are optional manifest metadata (NULL to let the daemon
 * infer). Returns the new job id (16 hex chars).
 */
char *ant_upload_start(const AntHandle *handle,
                       const char *path,
                       const char *batch_id,
                       const char *name,
                       const char *content_type,
                       char **out_err);

/*
 * Snapshot every upload job as a JSON document:
 *   {"jobs":[{"job_id","source_path","source_size","name","content_type",
 *             "raw","status","bytes_pushed","chunks_pushed","chunks_total",
 *             "created_at_unix","last_update_unix","last_error",
 *             "reference"},...]}
 * (optional fields are omitted when unknown).
 */
char *ant_upload_list(const AntHandle *handle, char **out_err);

/*
 * Snapshot one upload job by id (or unique 8-hex-char prefix) as a JSON
 * object with the same shape as a `jobs[]` element above.
 */
char *ant_upload_status(const AntHandle *handle,
                        const char *job_id,
                        char **out_err);

/*
 * Pause / resume / cancel an upload job. Each returns the updated job
 * JSON object.
 */
char *ant_upload_pause(const AntHandle *handle, const char *job_id, char **out_err);
char *ant_upload_resume(const AntHandle *handle, const char *job_id, char **out_err);
char *ant_upload_cancel(const AntHandle *handle, const char *job_id, char **out_err);

/*
 * Snapshot the local storage plan (postage issuer) as a JSON object:
 *   {"enabled":bool,"batch_id","batch_depth","bucket_depth","immutable",
 *    "bucket_count","bucket_capacity","total_capacity_chunks",
 *    "issued_chunks","bucket_fill_min","bucket_fill_max",
 *    "remaining_total_chunks","worst_case_remaining_chunks"}
 * `enabled=false` means no plan is connected yet.
 */
char *ant_storage_status(const AntHandle *handle, char **out_err);

/*
 * Outbound-settlement status as a JSON object:
 *   {"enabled":bool,"chequebook":"0x…"|null}
 * `enabled=true` once a chequebook is deployed, which is what lets
 * uploads actually propagate (bee charges the uploader per pushed chunk
 * and freezes out a node that can't pay). A persisted chequebook that a
 * chain check (gateway start, buy, deploy) found unusable — not
 * registered with the factory, or issued by another key — reports
 * `enabled=false`: settlement is switched off for it. Builds without the `chain`
 * feature always report {"enabled":false,"chequebook":null}.
 */
char *ant_storage_settlement_status(const AntHandle *handle, char **out_err);

/*
 * Settlement-deposit status as a JSON object:
 *   {"enabled":bool,"chequebook":"0x…"|null,"deposit_plur","deposit_bzz",
 *    "target_plur","target_bzz","shortfall_plur","shortfall_bzz",
 *    "needs_top_up":bool,"xdai_required","xdai_required_display",
 *    "xdai_to_send","xdai_to_send_display","sufficient_funds":bool}
 * ant_storage_settlement_status says whether a chequebook is deployed;
 * this says whether it actually backs the cheques it signs. A chequebook
 * at deposit 0 publishes fine until the peers' payment tolerance runs
 * out, then collapses into pushsync timeouts — so the Storage tab reads
 * this to detect that and offer ant_storage_settlement_topup.
 * `enabled=false` (zeroed, needs_top_up=false) when this account has no
 * chequebook yet (buying/connecting a plan deploys one, funded), or when
 * this process's chain check disqualified its chequebook (settlement is
 * off for it, as ant_storage_settlement_status reports). Reads
 * chain (a few light eth_calls) — for an explicit refresh, not every
 * status poll. Requires the `chain` cargo feature.
 */
char *ant_storage_settlement_deposit(const AntHandle *handle,
                                     const char *gnosis_rpc,
                                     char **out_err);

/*
 * Fund this account's chequebook up to the settlement deposit target
 * (0.001 xBZZ), funding ONLY with xDAI: the node swaps the xBZZ
 * shortfall on-chain if it doesn't already hold it, then transfers the
 * deposit to the chequebook. The explicit top-up path — a chequebook's
 * deposit is only read at deploy time, so an already-deployed one can be
 * funded no other way. Idempotent (a chequebook at the target is a
 * no-op); errors when this account has no chequebook yet, when its
 * chequebook fails the chain checks (factory registration, issuer())
 * this call runs before spending (and again right before the transfer)
 * — it never funds that one — or when those checks can't be read, or a
 * chequebook deployed moments ago isn't visible to the RPC yet (retry;
 * nothing spent). On success the node re-reads the chequebook's funds
 * over `gnosis_rpc` and pays cheques from them at once (ant_swap_status
 * paying, when the switch is on), and keeps re-reading them every
 * minute, even after an ant_init that reloaded the chequebook without an
 * RPC. Returns the
 * refreshed ant_storage_settlement_deposit JSON. SUBMITS REAL
 * TRANSACTIONS AND SPENDS REAL FUNDS, and BLOCKS until they confirm.
 * Requires the `chain` cargo feature.
 */
char *ant_storage_settlement_topup(const AntHandle *handle,
                                   const char *gnosis_rpc,
                                   char **out_err);

/*
 * Like ant_storage_settlement_topup, but deposits `amount_plur` more (a
 * decimal PLUR string, 1 xBZZ = 10^16 PLUR) whatever the deposit's
 * target: how a host tops up browsing credit beyond the node's default
 * deposit. The same product flow and guards: the chain checks run before
 * anything is spent, and the node swaps xDAI only for the xBZZ the wallet
 * lacks. Errors on an amount that isn't a positive integer. Mirrors the
 * gateway's POST /v0/settlement/deposit?amount=. Returns the refreshed
 * ant_storage_settlement_deposit JSON. SUBMITS REAL TRANSACTIONS AND
 * SPENDS REAL FUNDS, and BLOCKS until they confirm. Requires the `chain`
 * cargo feature.
 */
char *ant_storage_settlement_topup_amount(const AntHandle *handle,
                                          const char *gnosis_rpc,
                                          const char *amount_plur,
                                          char **out_err);

/*
 * Deep read-back propagation check for an uploaded reference. Resolves
 * the manifest to its data root, enumerates the file's chunk tree
 * (fetching every interior node network-only, which proves the skeleton
 * is retrievable), then checks the real data leaves network-only. All
 * fetches bypass local caches so our own store-then-push copy can't mask
 * a failed push. Returns JSON:
 *   {"reference","retrievable":bool,"total_chunks":int,"leaf_chunks":int,
 *    "intermediate_chunks":int,"checked_chunks":int,
 *    "retrievable_chunks":int,"sampled_leaves":int,"sources":int,
 *    "error"?:string}
 * `retrievable` is true iff the root and every interior node were
 * fetched and every checked leaf came back; `sources` is the minimum
 * distinct-route count across the probed subset (a replication floor).
 * `reference` accepts a bare/0x 64-hex address, a `bytes://` ref, or a
 * `bzz://<ref>/<path>` URL (path ignored). `samples == 0` checks *every*
 * leaf (a full verification, internally bounded for very large files);
 * a non-zero value spot-checks that many evenly-spread leaves. `probes`
 * is clamped to 1..=8; pass 0 to use a default.
 */
char *ant_storage_verify_propagation(const AntHandle *handle,
                                     const char *reference,
                                     uint8_t samples,
                                     uint8_t probes,
                                     char **out_err);

/*
 * Progress callback for ant_storage_verify_propagation_progress. Invoked
 * once per progress update with a borrowed, NUL-terminated JSON string of
 * the shape:
 *   {"phase":"resolving"|"enumerating"|"checking",
 *    "checked"?:int,"total"?:int}
 * The "checking" phase carries "checked"/"total" leaf counts so the UI can
 * draw a determinate bar; the earlier phases carry neither. The string is
 * valid only for the duration of the call — copy out anything you keep.
 * `ctx` is the opaque pointer passed through at call time.
 */
typedef void (*AntVerifyProgressCb)(const char *progress_json, void *ctx);

/*
 * Streaming variant of ant_storage_verify_propagation: identical result
 * JSON (returned), but `on_progress` (if non-null) is called with `ctx`
 * for each progress update as the check runs. The callback fires on the
 * calling thread, inline with the blocking call, so keep it cheap. Pass a
 * null `on_progress` to behave exactly like the non-streaming variant.
 */
char *ant_storage_verify_propagation_progress(const AntHandle *handle,
                                              const char *reference,
                                              uint8_t samples,
                                              uint8_t probes,
                                              AntVerifyProgressCb on_progress,
                                              void *ctx,
                                              char **out_err);

/*
 * Request cancellation of the in-flight propagation verification for this
 * handle. Cooperative: the node aborts at the next phase/leaf boundary and
 * the verify call returns a {"cancelled":true,...} body. No-op if nothing
 * is verifying. Safe to call from another thread while the verify call is
 * blocked.
 */
void ant_verify_cancel(const AntHandle *handle);

/*
 * "Push again" with streamed progress: re-push a completed job's missing
 * chunks on the same job (no new job), calling `on_progress` (with `ctx`)
 * for each progress update — reuses AntVerifyProgressCb; here the phases are
 * "checking" (a read-back round) and "repushing" (with "checked"/"total"
 * chunk counts). Returns the updated job JSON. A null `on_progress` runs
 * the heal silently. The callback fires on the calling thread inline with
 * the blocking call.
 */
char *ant_upload_repush_progress(const AntHandle *handle,
                                 const char *job_id,
                                 AntVerifyProgressCb on_progress,
                                 void *ctx,
                                 char **out_err);

/*
 * Account identity as a JSON object:
 *   {"eth_address","overlay","peer_id","agent"}
 */
char *ant_account_info(const AntHandle *handle, char **out_err);

/*
 * Export the account's raw secp256k1 signing key as 64 hex chars (no
 * 0x). Secret material — handle accordingly.
 */
char *ant_account_export_key(const AntHandle *handle, char **out_err);

/*
 * Connect a storage plan this account already owns on Gnosis, by id.
 * Reads the plan parameters from `gnosis_rpc`, verifies ownership,
 * registers it for stamping, and returns the refreshed
 * ant_storage_status JSON. Only functional when ant-ffi is built with
 * the `chain` cargo feature; otherwise returns NULL + an error.
 */
char *ant_storage_connect_batch(const AntHandle *handle,
                                const char *gnosis_rpc,
                                const char *batch_id,
                                char **out_err);

/*
 * Auto-discover and connect every funded storage plan this account owns
 * on Gnosis (an on-chain log scan — can take a while). Returns
 *   {"registered":[...ids],"status":<ant_storage_status object>}
 * Continues the scan saved in the data dir, so after the first call only
 * the blocks since are read. Requires the `chain` cargo feature.
 */
char *ant_storage_discover(const AntHandle *handle,
                           const char *gnosis_rpc,
                           char **out_err);

/*
 * ant_storage_discover, but reading the account's whole xBZZ transfer
 * history again instead of continuing the saved scan, and replacing it.
 * For a user's explicit "search again" when a plan they own is missing:
 * an RPC that once answered part of the history incompletely (a backend
 * far behind the head) leaves a hole the saved scan never revisits. As
 * slow as the first scan behind a range-capped RPC (minutes), so don't
 * call it at every start. Same return value; requires `chain`.
 */
char *ant_storage_discover_full(const AntHandle *handle,
                                const char *gnosis_rpc,
                                char **out_err);

/*
 * Deploy (or return the already-persisted) node-owned chequebook so the
 * publish-setup checklist's "chequebook deployed" step can complete.
 * Idempotent: if this device already deployed a chequebook it's returned
 * as-is (no redeploy — though one still short of its settlement deposit
 * is topped up from spare xBZZ); otherwise this signs an on-chain
 * factory.deploySimpleSwap (issuer = node EOA), funds it with the
 * 0.001 xBZZ settlement deposit so its cheques are actually backed,
 * persists the association, and returns the new address. SUBMITS REAL
 * ON-CHAIN TRANSACTIONS: spends gas (xDAI) plus the deposit (xBZZ, capped
 * to the wallet's balance) and BLOCKS until they confirm. Light-mode only (requires the `chain` cargo feature;
 * otherwise returns NULL + an error). Returns
 *   {"chequebookAddress":"0x<40hex>"}
 * (free with ant_free_string), or NULL with an error in *out_err. A
 * running in-process gateway picks the chequebook up at once:
 * /chequebook/address and /wallet report it with no gateway restart.
 * One the on-chain checks disqualify is cleared from them (and the call
 * returns NULL with the reason).
 */
char *ant_deploy_chequebook(const AntHandle *handle,
                            const char *gnosis_rpc,
                            char **out_err);

/*
 * Price a storage plan (no transaction). `depth` sets capacity
 * (2^depth chunks × 4 KiB); `days` sets how long it should last.
 * Returns a JSON object:
 *   {"depth","days","amount_per_chunk","total_cost_plur",
 *    "total_cost_bzz","settlement_deposit_plur","settlement_deposit_bzz",
 *    "capacity_bytes","account_bzz",
 *    "account_bzz_display","account_xdai","account_xdai_display",
 *    "needed_bzz","needed_bzz_display","xdai_required",
 *    "xdai_required_display","xdai_to_send","xdai_to_send_display",
 *    "sufficient_funds"}
 * For the xDAI-only flow, `xdai_to_send_display` is exactly how much more
 * xDAI to send; the node swaps it to xBZZ and buys the plan itself.
 * `settlement_deposit_*` is the one-time xBZZ deposit this purchase also
 * puts behind the node's chequebook so its cheques are backed (0 once it
 * is funded). It is part of the all-in figures (`needed_bzz`,
 * `xdai_required`, `xdai_to_send`, `sufficient_funds`), not of
 * `total_cost_*`, which stays the plan's own price.
 * Requires the `chain` cargo feature.
 */
char *ant_storage_quote(const AntHandle *handle,
                        const char *gnosis_rpc,
                        uint8_t depth,
                        uint64_t days,
                        char **out_err);

/*
 * Remaining lifetime of the connected storage plan as a JSON object:
 *   {"enabled":bool,"remaining_seconds":int,"expires_unix":int}
 * Reads the batch's on-chain remaining balance and the current postage
 * price and converts to wall-clock time. `enabled=false` (with zeroed
 * fields) when no plan is connected. One light RPC round-trip — intended
 * for an explicit refresh, not every status poll. Requires the `chain`
 * cargo feature.
 */
char *ant_storage_validity(const AntHandle *handle,
                           const char *gnosis_rpc,
                           char **out_err);

/*
 * Price a top-up (lifetime extension) of the CONNECTED storage plan:
 * what extending it by `days` costs at the current postage price and
 * whether the account's funds cover it. Returns the same JSON shape as
 * ant_storage_quote (`depth` echoes the connected plan's depth; the
 * cost scales with it because a top-up pays per chunk). Errors when no
 * plan is connected. No transaction is sent. Requires the `chain`
 * cargo feature.
 */
char *ant_storage_topup_quote(const AntHandle *handle,
                              const char *gnosis_rpc,
                              uint64_t days,
                              char **out_err);

/*
 * Top up (extend the lifetime of) the connected storage plan, funding
 * ONLY with xDAI: the node swaps the xBZZ shortfall on-chain if needed,
 * then runs approve + PostageStamp.topUp. `amount_per_chunk` is the
 * value from ant_storage_topup_quote so the charge matches the quote
 * the user approved. Returns the refreshed ant_storage_validity JSON
 * (the new expiry). SUBMITS REAL TRANSACTIONS AND SPENDS REAL FUNDS.
 * Requires the `chain` cargo feature.
 */
char *ant_storage_topup_xdai(const AntHandle *handle,
                             const char *gnosis_rpc,
                             const char *amount_per_chunk,
                             char **out_err);

/*
 * Buy and activate a storage plan on Gnosis (approve + createBatch, then
 * register it for stamping). `amount_per_chunk` is the value from
 * ant_storage_quote so the charge matches the approved quote; `immutable`
 * is 0 or 1. Returns the refreshed ant_storage_status JSON. SUBMITS REAL
 * TRANSACTIONS AND SPENDS REAL FUNDS. Requires the `chain` cargo feature.
 */
char *ant_storage_buy(const AntHandle *handle,
                      const char *gnosis_rpc,
                      uint8_t depth,
                      const char *amount_per_chunk,
                      int immutable,
                      char **out_err);

/*
 * Buy and activate a storage plan funding ONLY with xDAI: the node swaps
 * the xBZZ shortfall on-chain (deploying a tiny stateless swap helper the
 * first time) before approve + createBatch. `amount_per_chunk` is the
 * value from ant_storage_quote; `immutable` is 0 or 1. Returns the
 * refreshed ant_storage_status JSON. SUBMITS REAL TRANSACTIONS AND SPENDS
 * REAL FUNDS. Requires the `chain` cargo feature.
 */
char *ant_storage_buy_xdai(const AntHandle *handle,
                           const char *gnosis_rpc,
                           uint8_t depth,
                           const char *amount_per_chunk,
                           int immutable,
                           char **out_err);

/*
 * Free a buffer returned by ant_download. `len` must be the exact
 * length the call wrote into *out_len. Null pointer / zero length is
 * a no-op.
 */
void ant_free_buffer(unsigned char *ptr, size_t len);

/*
 * Free a NUL-terminated string returned via an `out_err` slot. Null is
 * a no-op.
 */
void ant_free_string(char *ptr);

/*
 * Start the in-process bee-shaped HTTP gateway on `api_addr` (pass NULL
 * or "" for the default 127.0.0.1:1633), serving the node this handle
 * owns. Lets an iOS app expose the same HTTP API `antd` serves
 * (/bzz, /bytes, /chunks, /feeds, /soc, /tags, status, plus bee-stubbed
 * /wallet, /stamps, /chequebook) without spawning a separate daemon
 * process.
 *
 * `light_mode` drives GET /node.beeMode: nonzero -> "light" (publish /
 * feed / SOC writes allowed), zero -> "ultra-light" (read-only).
 *
 * `gnosis_rpc` is the Gnosis JSON-RPC endpoint backing the on-chain
 * /wallet, /stamps, and /chequebook surfaces. Pass NULL or "" to disable
 * on-chain reads (those endpoints fall back to the bee zero-stub / 501).
 * When set together with light_mode, it enables real /wallet balances
 * and /stamps postage state (desktop `antd` parity). Honoured only when
 * the library is built with the `chain` feature; ignored otherwise.
 *
 * A `gnosis_rpc` also triggers, in the background, the chain-derived
 * startup work ant_init can't do without an RPC (antd's startup chain
 * block):
 *
 *   1. Postage batches ant_init reloaded from postage/<id>.bin that the
 *      chain reports as missing, expired (remainingBalance 0) or owned by
 *      another key are unregistered (files stay on disk). "Missing" must
 *      be read twice, 45 seconds apart, before it counts (an RPC backend
 *      may not have seen a just-bought batch's creation yet); the first
 *      such read only schedules a background re-check, and steps 2 and 3
 *      don't wait for it.
 *   2. Funded batches the account owns on-chain but not on disk
 *      (reinstall, restore from key) are registered.
 *   3. The persisted or on-chain chequebook is adopted and outbound
 *      settlement switched on. Nothing is deployed or funded.
 *
 * A step that fails (e.g. a batch whose read fails stays registered) is
 * retried by the next call with a `gnosis_rpc` — including one that
 * finds the gateway already running. Steps that already succeeded are
 * not repeated. With a `gnosis_rpc`, a batch bought through POST /stamps
 * also makes sure settlement is on afterwards, as ant_storage_buy does:
 * it deploys a chequebook if the account has none (xDAI gas) and brings
 * a new or existing chequebook's deposit up to the settlement target
 * from the wallet's existing xBZZ (nothing is swapped).
 *
 * The gateway's chain wiring is captured here, once. A host serving
 * chain reads itself must call ant_set_chain_transport BEFORE this.
 *
 * CORS: the gateway allows cross-origin reads only for the origins last
 * set with ant_set_gateway_cors. By default none: it sends no CORS
 * headers, so no page from another origin can read its responses.
 * (Up to 0.5.48 it always allowed the `null` origin; hosts that relied
 * on that must now call ant_set_gateway_cors explicitly.)
 * CORS only stops reads: any page can still send CORS-simple requests,
 * which execute — including the spending routes POST
 * /stamps/{amount}/{depth} (which, as above, may also deploy a
 * chequebook for xDAI gas and transfer the wallet's xBZZ into a new or
 * under-funded one; no swap) and POST /chequebook/deposit (see
 * ant_set_gateway_cors; issue #105). The gateway also serves the
 * xDAI-swapping routes POST /v0/storage/buy, POST /v0/storage/extend
 * and POST /v0/settlement/deposit; those refuse (403) any request from
 * a web page unless its origin is listed exactly (not "*" or "null")
 * with ant_set_gateway_cors.
 *
 * Returns true on success (or if a gateway is already running on this
 * handle). On failure returns false and writes an allocated message to
 * *out_err (free with ant_free_string). Idempotent: a second call while
 * one is live is a success that leaves the gateway untouched; with a
 * `gnosis_rpc` it re-runs the chain startup work above, retrying only
 * what hasn't succeeded: a failed or pending batch check is re-read, a
 * failed rediscovery scan or chequebook adoption is retried. A scan or
 * adoption that already succeeded is not repeated in this process (a
 * batch bought on another device needs ant_storage_discover, or a fresh
 * ant_init followed by ant_start_gateway with a `gnosis_rpc` — ant_init
 * alone only reloads persisted state and never rescans). Run off the
 * main thread.
 */
bool ant_start_gateway(const AntHandle *handle,
                       const char *api_addr,
                       bool light_mode,
                       const char *gnosis_rpc,
                       char **out_err);

/*
 * Set the CORS origins the in-process gateway allows (new after 0.5.48).
 * Takes effect at the next ant_start_gateway; call it before the first
 * start or after ant_stop_gateway — while a gateway is running it fails
 * and changes nothing.
 *
 * `origins` points at `origins_len` NUL-terminated UTF-8 strings,
 * matched like bee's cors-allowed-origins: an exact origin such as
 * "https://app.example" (case-insensitive), "*" for any origin, or
 * "null" for opaque origins. NULL / origins_len == 0 clears the list —
 * the default — so the gateway sends no CORS headers and no page from
 * another origin can read its responses. Blank entries are ignored.
 *
 * Beyond bee, an entry may be a wildcard subdomain: a scheme, then
 * "://", then "*." and a host (e.g. scheme https with host
 * "*.bzz.freedom.baby"). It allows every direct-or-deeper
 * subdomain of host on exactly that scheme with no port
 * ("https://abc.bzz.freedom.baby", "https://a.b.bzz.freedom.baby"), but
 * not the apex "https://bzz.freedom.baby", not a lookalike such as
 * "https://x.bzz.freedom.baby.evil.example", not "http://", and not
 * "https://x.bzz.freedom.baby:8443". Matching is case-insensitive. The
 * "*" must be the whole leftmost label and host a plain DNS name of at
 * least two labels. Any other entry containing "*" is rejected: nothing
 * after the "*.", "*.host" without a scheme, "https://a.*.host", a bare
 * TLD such as "*.com" after the scheme, a wildcard with a port or path.
 * (These examples never write the scheme's "//" next to the "*": in
 * this header that pair would open a C comment inside this one.)
 * Use it when each content root is served from its own synthetic origin
 * (Freedom Android's virtual origins): that set is unbounded, so it
 * cannot be listed exactly, and a wildcard keeps it to the host's own
 * namespace, whereas "*" would let any page in any other browser on the
 * device read the API.
 *
 * The gateway has no auth: an allowed page can read /wallet, /addresses,
 * /stamps, ... and send preflighted requests (e.g. uploads with Swarm-*
 * headers). "null" matches ANY page whose request was
 * redirected across origins (the Fetch spec taints its Origin to
 * "null"), and "*" matches every page — allow them only if that is
 * acceptable.
 *
 * This list protects READS only; an empty list does not block writes.
 * A CORS-simple request needs no preflight, so any page can still
 * fetch(url, {method: "POST", mode: "no-cors"}) against
 * POST /stamps/{amount}/{depth} or POST /chequebook/deposit and the
 * gateway executes it, spending the wallet's xBZZ (for /stamps both the
 * batch and a transfer into a new or under-funded chequebook's deposit)
 * and xDAI gas for every transaction it sends — the batch purchase and,
 * for /stamps, deploying a chequebook if there is none yet and the
 * deposit transfer into a new or under-funded one (nothing is swapped)
 * — even though the page cannot read the reply. Do not rely on this
 * call to protect funds.
 *
 * The routes that DO swap xDAI (POST /v0/storage/buy, POST
 * /v0/storage/extend, POST /v0/settlement/deposit) are guarded
 * separately: they refuse (403) requests from web pages whatever this
 * list says, except from an origin listed here exactly — "*" and
 * "null" never unlock them. Listing an exact origin therefore also
 * lets that site spend the wallet's xDAI.
 *
 * Returns true on success. On failure (NULL handle, gateway running,
 * NULL, non-UTF-8 or malformed-wildcard entry) returns false, leaves the stored list
 * unchanged and writes an allocated message to *out_err (free with
 * ant_free_string).
 */
bool ant_set_gateway_cors(const AntHandle *handle,
                          const char *const *origins,
                          size_t origins_len,
                          char **out_err);

/*
 * Stop the in-process HTTP gateway started by ant_start_gateway.
 * Returns true if a gateway was running and was stopped, false if none
 * was running (or `handle` is NULL). Safe to call repeatedly.
 */
bool ant_stop_gateway(const AntHandle *handle);

/*
 * Host-provided JSON-RPC transport for ant's Gnosis chain access.
 *
 * `request_json` is a complete, NUL-terminated JSON-RPC request body
 * ({"jsonrpc":"2.0","id":…,"method":…,"params":…}) owned by ant and
 * valid only for the duration of the call — copy it if you need it
 * longer. `host_ctx` is the pointer handed to ant_set_chain_transport,
 * passed back untouched.
 *
 * Return a malloc(3)'d, NUL-terminated JSON-RPC response body: ant
 * takes ownership and releases it with free(3). Return NULL for
 * "can't serve" — ant then falls back to the configured gnosis_rpc URL
 * exactly as if no transport were installed.
 */
typedef char *(*ant_chain_transport)(const char *request_json, void *host_ctx);

/* ant_set_chain_transport return codes. */
#define ANT_CHAIN_TRANSPORT_OK           0  /* installed (or cleared)   */
#define ANT_CHAIN_TRANSPORT_NULL_HANDLE (-1) /* handle was NULL         */
#define ANT_CHAIN_TRANSPORT_UNSUPPORTED (-2) /* built without `chain`   */

/*
 * Install (or, with transport == NULL, clear) a host-provided JSON-RPC
 * transport for every chain request this handle makes: eth_call,
 * eth_getBalance, eth_getLogs, eth_getTransactionReceipt,
 * eth_sendRawTransaction, eth_getTransactionCount, eth_blockNumber,
 * eth_getCode. Ant keeps issuing exactly the requests it issues without
 * a transport — the transport only decides where they are answered.
 *
 * Can't-serve, both of which fall back to the configured gnosis_rpc URL:
 *   * a NULL return from the callback; and
 *   * a JSON-RPC error with code -32000 — the retryable "my index does
 *     not cover this range yet" shape, carrying the covered block window
 *     in error.data. Ant never surfaces that as an empty result, which
 *     would silently truncate postage-batch discovery.
 * Any other JSON-RPC error (an eth_call revert, say) is a genuine answer
 * and is passed through to the caller.
 *
 * WARNING — emit -32000 ONLY for a coverage gap, never as a generic
 * failure code. geth and Nethermind use -32000 as a catch-all for
 * genuine, non-retryable failures too: "nonce too low", "already known",
 * "insufficient funds for gas * price + value", "replacement transaction
 * underpriced", and on some backends "execution reverted". A host whose
 * verified ladder bottoms out at an RPC quorum and forwards backend
 * replies verbatim therefore reclassifies every one of those as
 * can't-serve, and ant replays the request against gnosis_rpc: for
 * eth_sendRawTransaction that is a SECOND BROADCAST of an
 * already-signed transaction, and the caller then sees the fallback
 * URL's error instead of the real one. Map any failure that is not a
 * coverage gap to a different error code, or answer authoritatively.
 *
 * Threading: the callback runs on ant's runtime blocking pool, so
 * blocking inside it is fine and expected (verified reads, locks, a
 * nested event loop). It may be invoked concurrently from several such
 * threads, so `host_ctx` must be safe to use from any thread.
 *
 * Lifetime: `transport` must stay callable and `host_ctx` valid until
 * the transport is replaced/cleared or ant_shutdown is called. Both of
 * those drain before they return: no thread is inside the old callback
 * once ant_set_chain_transport / ant_shutdown returns, and none can
 * enter it again (chain requests fall back to gnosis_rpc), so freeing
 * host_ctx right after the call is safe — including while a gateway is
 * running. Two consequences: a callback that never returns wedges the
 * replacing call, and calling ant_set_chain_transport from *inside* the
 * callback deadlocks.
 *
 * Ordering: effective immediately for ant_storage_* / ant_settlement_* /
 * ant_deploy_chequebook, which build a chain client per call. The
 * in-process gateway captures its chain wiring at ant_start_gateway, so
 * a transport installed while no transport was installed at its start
 * is only guaranteed to reach it after a restart (ant_stop_gateway +
 * ant_start_gateway) — install it first. Replacing or clearing a
 * transport the gateway did start with takes effect in it immediately,
 * per Lifetime above.
 *
 * Returns ANT_CHAIN_TRANSPORT_OK (0) on success,
 * ANT_CHAIN_TRANSPORT_NULL_HANDLE (-1) if `handle` is NULL, or
 * ANT_CHAIN_TRANSPORT_UNSUPPORTED (-2) when this build has no chain
 * support at all (built without the `chain` feature — no chain request
 * exists to route, and nothing was installed).
 */
int ant_set_chain_transport(AntHandle *handle,
                            ant_chain_transport transport,
                            void *host_ctx);

/*
 * Start the AntStream publisher throughput benchmark (issue #67 stage 1)
 * on this node: a synthetic-segment publisher loop that measures
 * sustained Mbit/s, chunks/s, per-segment publish latency, and how far
 * behind the live edge the uploader falls.
 *
 * `config_json` is a BenchConfig document; only "label" is required:
 *   {
 *     "label":          "iPhone 15 Pro / LTE",   // required, the table row key
 *     "bitrate_kbps":   3400,                    // target video bitrate
 *     "segment_ms":     2000,                    // HLS segment cadence
 *     "duration_s":     1800,                    // >= 1800 for a quotable number
 *     "warmup_s":       30,                      // excluded from the average
 *     "max_in_flight":  4,                       // bounded publish window
 *     "gateway":        "http://127.0.0.1:1633", // ant_start_gateway's address
 *     "batch_id":       "0x<64 hex>",            // omit -> no-network mode
 *     "seed":           123,
 *     "notes":          "iOS 26.0, battery 87%->81%, thermal nominal"
 *   }
 *
 * With a batch_id, every segment is published with a real POST /bzz
 * against `gateway` (start it first with ant_start_gateway) — that is
 * the number the go/no-go gate is about. Without one, the run measures
 * only the local chunk-split + postage-stamp pipeline, which needs no
 * network and no batch (useful as a device CPU ceiling, and the only
 * mode available before a storage plan is connected).
 *
 * Returns immediately; the run drives itself on the node's runtime.
 * Only one run at a time per handle. Returns true on success, false
 * with an allocated message in *out_err (free with ant_free_string).
 */
bool ant_bench_start(const AntHandle *handle,
                     const char *config_json,
                     char **out_err);

/*
 * Live progress of the run started by ant_bench_start, as an allocated
 * JSON object (free with ant_free_string):
 *   {"running":true,"elapsed_s":42.0,"configured_duration_s":1800,
 *    "segments_total":21,"segments_ok":21,"segments_failed":0,
 *    "sustained_mbit_s":3.4,"sustained_chunks_s":105.5,
 *    "lag_ms_last":180,"peers":114}
 * Non-blocking. Returns NULL + an error when no run has been started.
 */
char *ant_bench_progress(const AntHandle *handle, char **out_err);

/*
 * Stop the run and return its final report as an allocated JSON object
 * (free with ant_free_string): the config it ran with plus
 * sustained_mbit_s / sustained_chunks_s, publish_ms_p50|p95|max,
 * lag_ms_p50|p95|max|final, peers_min|max, segments_ok|failed,
 * measured_segments_total|ok, the first few error strings, and a
 * "sustained" verdict (at least one segment measured AND every
 * measured segment published AND the final lag still inside
 * 3 x segment_ms). The verdict is scoped to the post-warm-up window:
 * a segment that failed inside warmup_s is counted in segments_failed
 * but does not fail the run, since excluding cold-start effects is
 * what warmup_s is for.
 *
 * A run stopped before warmup_s has elapsed measures nothing:
 * measured_segments_total is 0, every figure is 0, and "sustained" is
 * false because there is no window to judge — not because the run fell
 * behind. Hosts rendering a verdict should say so rather than showing
 * such a run as a pass or a failure.
 *
 * BLOCKING: the cancel is cooperative, so this waits for the in-flight
 * segments to land (bounded at ~65 s by the bench's own per-segment
 * publish deadline) rather than truncating the measurement. Call it off
 * the main thread. Safe to call on an already-finished run.
 */
char *ant_bench_stop(const AntHandle *handle, char **out_err);

/* -------------------------------------------------------------------
 * AntStream live publisher (issue #67 stage 2)
 * ------------------------------------------------------------------- */

/*
 * Start a live broadcast from this node.
 *
 * This is the bench loop above with the synthetic generator replaced by
 * the host's camera pipeline. Per segment: POST /bzz the segment,
 * rebuild the HLS media playlist over a sliding window, POST /bzz the
 * playlist, and publish its reference as a sequence-feed update with
 * POST /soc (bee-js shape: id = keccak256(topic || index_be8), payload
 * timestamp_be8 || reference) so any bee gateway resolves the channel.
 * A feed manifest is created once at start with POST /feeds; its
 * reference is the single thing a viewer needs.
 *
 * `config_json` is a PublisherConfig document; "channel" and "batch_id"
 * are required:
 *   {
 *     "channel":         "Kitchen",               // required, names the feed
 *     "topic":           "0x<64 hex>",            // omit -> derived per broadcast
 *     "gateway":         "http://127.0.0.1:1633", // ant_start_gateway's address
 *     "batch_id":        "0x<64 hex>",            // required: the storage plan
 *     "segment_ms":      2000,                    // nominal segment duration
 *     "bitrate_kbps":    900,                     // 360p, the stage-1 rendition
 *     "max_in_flight":   4,                       // measured stable window
 *     "max_backlog":     4,                       // drop-oldest past this
 *     "playlist_window": 6,                       // segments in the live playlist
 *     "notes":           "iPhone 15 Pro / LTE"
 *   }
 *
 * Do NOT raise max_in_flight without re-measuring: stage 1 found
 * window 4 stable (899/899 segments) and window 8 a connection-layer
 * collapse (26/316). See crates/ant-ffi/ANTSTREAM_BENCH.md.
 *
 * Returns immediately; the loop drives itself on the node's runtime.
 * Only one broadcast at a time per handle. Returns true on success,
 * false with an allocated message in *out_err (free with
 * ant_free_string).
 */
bool ant_publisher_start(const AntHandle *handle,
                         const char *config_json,
                         char **out_err);

/*
 * Hand one finished capture segment to the running broadcast.
 *
 * `is_init` marks the fMP4 initialization segment (ftyp + moov), which
 * every following media segment needs to be playable; push a fresh one
 * whenever the writer restarts (camera flip, interruption recovery,
 * thermal downshift) and set `discontinuity` on the first media segment
 * after it. `duration_ms` is the segment's real duration (ignored for
 * the initialization segment). The bytes are copied; the caller may
 * free `data` as soon as this returns.
 *
 * CALL ORDER IS THE BROADCAST ORDER — segments are numbered as these
 * calls arrive, and that number fixes both the playlist order and which
 * EXT-X-MAP a media segment is listed under. Push in capture order,
 * from one thread or an ordered queue: getting it wrong around a writer
 * restart lists the old writer's last segment under the new writer's
 * map, which no player can decode.
 *
 * NEVER BLOCKS — a capture pipeline stalled on the uplink drops frames.
 * When the publisher is already a window behind, the oldest pending
 * segment is dropped instead (live-edge discipline).
 *
 * Returns:
 *    0  queued
 *    1  queued, and the oldest pending segment was dropped to stay at
 *       the live edge (the playlist marks the gap EXT-X-DISCONTINUITY)
 *    2  refused: the broadcast is stopping
 *   -1  error, with an allocated message in *out_err
 */
int32_t ant_publisher_push_segment(const AntHandle *handle,
                                   bool is_init,
                                   const unsigned char *data,
                                   size_t len,
                                   uint32_t duration_ms,
                                   bool discontinuity,
                                   char **out_err);

/*
 * Live progress of the broadcast, as an allocated JSON object (free
 * with ant_free_string):
 *   {"running":true,"elapsed_s":42.0,"channel":"Kitchen",
 *    "topic":"<64 hex>","owner":"<40 hex>",
 *    "channel_reference":"<64 hex>","playlist_reference":"<64 hex>",
 *    "feed_index":21,"segments_pushed":22,"segments_published":21,
 *    "segments_listed":21,"segments_failed":0,"segments_dropped":0,
 *    "bytes_published":4725000,
 *    "playlists_published":21,"publish_ms_p50":900,"publish_ms_p95":2100,
 *    "lag_ms":2400,"lag_ms_max":3100,"keeping_up":true,
 *    "sustained_mbit_s":0.9,"peers":114,"last_error":"","error_count":0}
 *
 * "lag_ms" is the live-edge lag the on-screen indicator shows: how far
 * behind live a viewer is right now, i.e. the age of the newest segment
 * a landed feed update made playable. It is an age, not the latency of
 * the last update that landed, so a broadcast whose feed updates stop
 * landing keeps climbing here (and turns "keeping_up" false) instead of
 * freezing at its last good figure while segments go on uploading.
 * "keeping_up" is that lag inside three segment durations, the same
 * budget the bench verdict uses. Non-blocking; poll it about once a
 * second. Returns NULL + an error when this node is not broadcasting.
 */
char *ant_publisher_progress(const AntHandle *handle, char **out_err);

/*
 * End the broadcast and return its final report as an allocated JSON
 * object (free with ant_free_string): the progress fields above plus
 * duration_s, chunks_published, feed_updates, sustained_chunks_s,
 * publish_ms_p50|p95|max, lag_ms_p50|p95|max|final, the first few error
 * strings, and a "kept_up" verdict (at least one feed update landed AND
 * every captured media segment reached a published playlist AND the
 * live edge ended inside 3 x segment_ms). "lag_ms_final" is that live
 * edge — the age of the newest playable segment at stop, frozen there
 * so a report read later still describes the broadcast rather than how
 * long the host waited to ask. The count it uses is
 * "segments_listed", not "segments_published": a segment whose
 * initialization segment never landed uploads fine and is still
 * unplayable, so hosts rendering a verdict should key "is there a
 * verdict at all?" off segments_listed too.
 *
 * BLOCKING: stopping is cooperative. Segments already captured are
 * published — the last seconds of a broadcast are real content — and
 * the playlist is closed with EXT-X-ENDLIST so viewers see a finished
 * recording rather than a stream that stopped updating. Returns after
 * ~130 s at the latest; a worst-case drain (the in-flight window, a
 * segment the pump had already popped behind it, then the backlog —
 * up to three rounds of the 60 s per-segment publish deadline) can
 * still be finishing in the background past that, with the report
 * returned honestly either way. Call it off the main thread.
 *
 * Calling it on a broadcast that already finished on its own returns
 * that broadcast's report. Once a stop call has returned and released
 * the slot, a second call fails with "not broadcasting" — keep the
 * report from the first call rather than re-fetching it.
 */
char *ant_publisher_stop(const AntHandle *handle, char **out_err);

/*
 * Shut the embedded node down and free the handle. After this
 * returns, `handle` must not be used again.
 *
 * Clears any host chain transport first and waits for an in-flight
 * callback to return (see ant_set_chain_transport), so the host_ctx
 * handed to it may be freed once this returns.
 */
void ant_shutdown(AntHandle *handle);

#ifdef __cplusplus
}
#endif

#endif /* ANT_FFI_H */
