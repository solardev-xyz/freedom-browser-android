# Off-app infrastructure for virtual origins

The app serves every dweb content root from its own synthetic https
origin under `freedom.baby` (see `docs/virtual-origins.md` and
`app/src/main/java/baby/freedom/mobile/browser/VirtualOrigin.kt`).
Inside the app those hosts never touch the network — the WebView's
request interceptor answers first. This directory is everything that has
to be true *outside* the app for that design to hold up.

| Directory    | What                                                                       |
| ------------ | -------------------------------------------------------------------------- |
| `psl/`       | Public Suffix List submission — makes each origin its own registrable domain |
| `redirector/`| the service that turns a leaked share link into a public-gateway redirect    |
| `applinks/`  | Digital Asset Links, so a shared link opens the app instead                  |

Start with `psl/README.md`: its propagation into users' WebView takes
months and is the critical path. Everything else is quick.

Each directory's README marks which steps need a human — DNS records,
TLS certificates, the upstream pull request, the deploy, and the release
certificate fingerprint all need credentials this repo does not have.

`VirtualOrigin.kt` is the single source of truth for the label
encodings; `redirector/test-vectors.json` pins them for both
implementations at once.
