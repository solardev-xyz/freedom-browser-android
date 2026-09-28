package baby.freedom.mobile.node;

/**
 * The answer to one [IMyotisService.ethCall]: the engine's JSON
 * (`{"status":"ok","resultHex",…}`, `{"status":"revert","dataHex",…}`,
 * `{"status":"unavailable","reason"}` or `{"error"}`).
 */
oneway interface IMyotisCallResult {
    void onResult(String json);
}
