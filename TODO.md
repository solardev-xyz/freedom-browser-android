## Todo

- ENSIP-15 normalization (currently lowercase-ASCII only; emoji / non-ASCII labels may normalize differently than `@adraffy/ens-normalize`).
- CCIP-Read / OffchainLookup (required for `.box` via 3DNS and some offchain `.eth` names; today surfaced as `NotFound(NO_RESOLVER)`).
- End-to-end Swarm content fetch test against a hash we pinned ourselves (blocked on Light-mode uploads).

## Deferred

- **Light mode**: wallet onboarding, xDAI / xBZZ funding, postage stamps, uploads ("Save page to Swarm")
- **Swarm feeds**: mutable name → hash resolution on top of the ENS path that exists today
- **Pinning**: choose which Swarm content to persist locally
