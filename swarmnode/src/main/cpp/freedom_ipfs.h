#ifndef FREEDOM_IPFS_H
#define FREEDOM_IPFS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct FreedomIpfsNode FreedomIpfsNode;
typedef struct FreedomIpfsBuffer {
    uint8_t *data;
    size_t len;
} FreedomIpfsBuffer;
typedef struct FreedomIpfsGatewayReadResult {
    uint32_t status;
    size_t bytes_read;
} FreedomIpfsGatewayReadResult;
typedef struct FreedomIpfsGatewayEvent {
    uint32_t status;
    uint32_t events;
    uint64_t request_handle;
} FreedomIpfsGatewayEvent;
typedef struct FreedomIpfsRetrievalStats {
    uint64_t cache_hits;
    uint64_t http_provider_blocks;
    uint64_t bitswap_blocks;
} FreedomIpfsRetrievalStats;
typedef struct FreedomIpfsRoutingStats {
    uint64_t delegated_provider_lookups;
    uint64_t delegated_provider_results;
    uint64_t delegated_provider_errors;
    uint64_t dht_provider_lookups;
    uint64_t dht_provider_results;
    uint64_t dht_provider_errors;
} FreedomIpfsRoutingStats;
typedef struct FreedomIpfsDiagnostics {
    uint64_t block_count;
    uint64_t total_bytes;
    uint64_t cache_hits;
    uint64_t http_provider_blocks;
    uint64_t bitswap_blocks;
    uint64_t delegated_provider_lookups;
    uint64_t delegated_provider_results;
    uint64_t delegated_provider_errors;
    uint64_t dht_provider_lookups;
    uint64_t dht_provider_results;
    uint64_t dht_provider_errors;
    uint64_t active_preload_count;
    uint64_t gateway_running;
    uint64_t lifecycle_background;
} FreedomIpfsDiagnostics;

#define FREEDOM_IPFS_ROUTING_MODE_AUTO ((uint32_t)0)
#define FREEDOM_IPFS_ROUTING_MODE_DELEGATED ((uint32_t)1)
#define FREEDOM_IPFS_ROUTING_MODE_LIGHT_DHT ((uint32_t)2)
#define FREEDOM_IPFS_ROUTING_MODE_OFFLINE ((uint32_t)3)

#define FREEDOM_IPFS_GATEWAY_READ_PENDING ((uint32_t)0)
#define FREEDOM_IPFS_GATEWAY_READ_BYTES ((uint32_t)1)
#define FREEDOM_IPFS_GATEWAY_READ_END ((uint32_t)2)
#define FREEDOM_IPFS_GATEWAY_READ_CANCELLED ((uint32_t)3)
#define FREEDOM_IPFS_GATEWAY_READ_FAILED ((uint32_t)4)
#define FREEDOM_IPFS_GATEWAY_READ_INVALID_HANDLE ((uint32_t)5)

#define FREEDOM_IPFS_GATEWAY_EVENT_STATUS_OK ((uint32_t)0)
#define FREEDOM_IPFS_GATEWAY_EVENT_STATUS_TIMEOUT ((uint32_t)1)
#define FREEDOM_IPFS_GATEWAY_EVENT_STATUS_INVALID_NODE ((uint32_t)2)
#define FREEDOM_IPFS_GATEWAY_EVENT_STATUS_GATEWAY_STOPPED ((uint32_t)3)

#define FREEDOM_IPFS_GATEWAY_EVENT_RESPONSE_READY ((uint32_t)(1u << 0))
#define FREEDOM_IPFS_GATEWAY_EVENT_BODY_READY ((uint32_t)(1u << 1))
#define FREEDOM_IPFS_GATEWAY_EVENT_END ((uint32_t)(1u << 2))
#define FREEDOM_IPFS_GATEWAY_EVENT_FAILED ((uint32_t)(1u << 3))
#define FREEDOM_IPFS_GATEWAY_EVENT_CANCELLED ((uint32_t)(1u << 4))
#define FREEDOM_IPFS_GATEWAY_EVENT_HANDLE_FREED ((uint32_t)(1u << 5))

/*
 * Increment when the mobile/native C ABI changes incompatibly. Product release
 * versions come from freedom_ipfs_version().
 */
#define FREEDOM_IPFS_MOBILE_FFI_ABI_VERSION ((uint32_t)1)

/* Returns the canonical freedom-ipfs runtime version, matching the release tag without the leading "v". */
char *freedom_ipfs_version(void);
/* Returns compact JSON with runtime version, ABI version, target, optional git build metadata, and feature flags. */
char *freedom_ipfs_build_info_json(void);
void freedom_ipfs_string_free(char *ptr);

FreedomIpfsNode *freedom_ipfs_node_new_in_memory(void);
FreedomIpfsNode *freedom_ipfs_node_new_with_data_dir(
    const char *data_dir,
    uint64_t max_cache_bytes);
void freedom_ipfs_node_free(FreedomIpfsNode *ptr);

bool freedom_ipfs_node_import_car(FreedomIpfsNode *ptr, const uint8_t *data, size_t len);
FreedomIpfsBuffer freedom_ipfs_node_export_car(FreedomIpfsNode *ptr);
void freedom_ipfs_buffer_free(FreedomIpfsBuffer buffer);
uint64_t freedom_ipfs_node_block_count(FreedomIpfsNode *ptr);
uint64_t freedom_ipfs_node_total_bytes(FreedomIpfsNode *ptr);
FreedomIpfsRetrievalStats freedom_ipfs_node_retrieval_stats(FreedomIpfsNode *ptr);
FreedomIpfsRoutingStats freedom_ipfs_node_routing_stats(FreedomIpfsNode *ptr);
uint64_t freedom_ipfs_node_active_preload_count(FreedomIpfsNode *ptr);
FreedomIpfsDiagnostics freedom_ipfs_node_diagnostics(FreedomIpfsNode *ptr);
char *freedom_ipfs_node_progress_snapshot_json(FreedomIpfsNode *ptr);
char *freedom_ipfs_node_native_gateway_stats_json(FreedomIpfsNode *ptr);
bool freedom_ipfs_node_clear_progress(FreedomIpfsNode *ptr);
bool freedom_ipfs_node_clear_cache(FreedomIpfsNode *ptr);
bool freedom_ipfs_node_trim_cache(FreedomIpfsNode *ptr, uint64_t max_bytes);
bool freedom_ipfs_node_enter_background(FreedomIpfsNode *ptr);
bool freedom_ipfs_node_enter_foreground(FreedomIpfsNode *ptr);
bool freedom_ipfs_node_handle_low_memory(FreedomIpfsNode *ptr, uint64_t max_cache_bytes);
bool freedom_ipfs_node_handle_network_change(FreedomIpfsNode *ptr);
/* Gateway start/restart functions accept only loopback socket addresses. */
bool freedom_ipfs_node_start_gateway(FreedomIpfsNode *ptr, const char *addr);
bool freedom_ipfs_node_start_gateway_online(
    FreedomIpfsNode *ptr,
    const char *addr,
    const char *delegated_router);
bool freedom_ipfs_node_start_gateway_online_with_config(
    FreedomIpfsNode *ptr,
    const char *addr,
    const char *delegated_router,
    uint32_t routing_mode,
    size_t max_concurrent_requests);
bool freedom_ipfs_node_start_gateway_online_with_config_v2(
    FreedomIpfsNode *ptr,
    const char *addr,
    const char *delegated_router,
    uint32_t routing_mode,
    size_t max_concurrent_requests,
    uint64_t dht_query_timeout_secs,
    size_t dht_max_providers);
bool freedom_ipfs_node_start_gateway_online_with_config_v3(
    FreedomIpfsNode *ptr,
    const char *addr,
    const char *delegated_router,
    uint32_t routing_mode,
    size_t max_concurrent_requests,
    uint64_t dht_query_timeout_secs,
    size_t dht_max_providers,
    uint64_t request_queue_timeout_ms);
bool freedom_ipfs_node_restart_gateway_online_with_config_v2(
    FreedomIpfsNode *ptr,
    const char *addr,
    const char *delegated_router,
    uint32_t routing_mode,
    size_t max_concurrent_requests,
    uint64_t dht_query_timeout_secs,
    size_t dht_max_providers);
bool freedom_ipfs_node_restart_gateway_online_with_config_v3(
    FreedomIpfsNode *ptr,
    const char *addr,
    const char *delegated_router,
    uint32_t routing_mode,
    size_t max_concurrent_requests,
    uint64_t dht_query_timeout_secs,
    size_t dht_max_providers,
    uint64_t request_queue_timeout_ms);
bool freedom_ipfs_node_start_native_gateway_online_with_config_v2(
    FreedomIpfsNode *ptr,
    const char *delegated_router,
    uint32_t routing_mode,
    size_t max_concurrent_requests,
    uint64_t dht_query_timeout_secs,
    size_t dht_max_providers);
bool freedom_ipfs_node_start_native_gateway_online_with_config_v3(
    FreedomIpfsNode *ptr,
    const char *delegated_router,
    uint32_t routing_mode,
    size_t max_concurrent_requests,
    uint64_t dht_query_timeout_secs,
    size_t dht_max_providers,
    uint64_t request_queue_timeout_ms);
char *freedom_ipfs_node_gateway_url(FreedomIpfsNode *ptr);
uint64_t freedom_ipfs_gateway_request_start(
    FreedomIpfsNode *ptr,
    const char *request_json);
char *freedom_ipfs_gateway_request_response_json(
    FreedomIpfsNode *ptr,
    uint64_t request_handle);
/* Waits up to timeout_ms for metadata or a terminal state; timeout_ms=0 is nonblocking. */
char *freedom_ipfs_gateway_request_response_json_wait(
    FreedomIpfsNode *ptr,
    uint64_t request_handle,
    uint64_t timeout_ms);
FreedomIpfsGatewayReadResult freedom_ipfs_gateway_request_read(
    FreedomIpfsNode *ptr,
    uint64_t request_handle,
    uint8_t *buffer,
    size_t buffer_len);
/* Waits up to timeout_ms for bytes or a terminal state; timeout_ms=0 is nonblocking. */
FreedomIpfsGatewayReadResult freedom_ipfs_gateway_request_read_wait(
    FreedomIpfsNode *ptr,
    uint64_t request_handle,
    uint8_t *buffer,
    size_t buffer_len,
    uint64_t timeout_ms);
/* Waits up to timeout_ms for any native request readiness; timeout_ms=0 is nonblocking. */
FreedomIpfsGatewayEvent freedom_ipfs_gateway_wait_next_event(
    FreedomIpfsNode *ptr,
    uint64_t timeout_ms);
bool freedom_ipfs_gateway_request_cancel(
    FreedomIpfsNode *ptr,
    uint64_t request_handle);
bool freedom_ipfs_gateway_request_free(
    FreedomIpfsNode *ptr,
    uint64_t request_handle);
uint64_t freedom_ipfs_node_preload_path(FreedomIpfsNode *ptr, const char *path);
bool freedom_ipfs_node_cancel_preload(FreedomIpfsNode *ptr, uint64_t task_id);
bool freedom_ipfs_node_stop_gateway(FreedomIpfsNode *ptr);

#ifdef __cplusplus
}
#endif

#endif
