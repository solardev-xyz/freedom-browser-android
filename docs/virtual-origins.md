# Virtual origins for dweb content

Since this release, every dweb content root (Swarm reference, IPFS CID,
IPNS key or DNSLink name, ENS name) is loaded from its own synthetic
https origin instead of the shared local-gateway origin:

```
address bar shows:   bzz://<hash>/gallery/index.html
WebView loads:       https://<label>.bzz.freedom.baby/gallery/index.html
interceptor serves:  http://127.0.0.1:1633/bzz/<hash>/gallery/index.html
```

No DNS or TLS is ever involved — `shouldInterceptRequest` answers for
these hosts before the network stack runs. The mapping between roots
and hostnames lives in `VirtualOrigin.kt` (single source of truth,
mirrored by the redirector service and PSL submission under `infra/`).

What this buys:

- **Per-root storage isolation** — localStorage / IndexedDB / cookies
  are no longer shared between every dweb site.
- **Native URL resolution** — relative *and* absolute-root subresource
  paths (`/_next/static/…`) resolve correctly without the old
  gateway-escape rewrite heuristics (now deleted).
- **Working same-origin `fetch()` / XHR**, a secure context, and
  `bzz://` / `ipfs://` / `ipns://` subresource links (answered with a
  301 to the virtual-origin equivalent).
- **Name-derived ENS origins** — `name.eth` sites keep their storage
  across contenthash updates; the interceptor re-resolves the name.

## Links that leave the app

Pages copy `window.location.href` into share buttons, so the synthetic
URL escapes. Because the domain is ours, that leak is a feature rather
than a bug:

- a small redirector (`infra/redirector/`) decodes the label back to the
  content id and 301s to a public gateway, so a shared link opens the
  same content in any browser;
- on a device with Freedom installed, Android App Links intercepts it
  first — the manifest's `autoVerify` filter covers the four virtual
  suffixes, and `MainActivity` translates the URL back through
  `VirtualOrigin.displayUrlFor` and opens it in a tab. Desktop's
  `bzz://` URLs can't do this.

Both depend on infrastructure that lives outside the app; `infra/`
documents what has to be created and by whom.

## Release note: site storage is reset

Any localStorage / IndexedDB state that dweb sites saved in previous
releases lived under the single shared gateway origin
(`http://127.0.0.1:1633`). That storage does **not** follow sites to
their new per-root origins.

**Decision: document, don't migrate.** The old namespace was shared by
every dweb site the user ever visited — key ownership is unknowable
(any page could read and write any key), which is exactly the isolation
bug this release fixes. Attributing keys to a root after the fact would
be guesswork, and migrating guessed data into an isolated origin would
launder possibly-tainted state into a now-trusted namespace. Sites see
a clean origin and rebuild their state; the shared-origin residue is
cleared with the usual "Clear browsing data" action.

## Known limitations (part of the compatibility contract)

- Pages see `https://<label>.bzz.…` in `window.location`, not `bzz://`.
- Literal `fetch("bzz://…")` from page JS stays blocked — Chromium
  rejects CORS-mode fetches to non-http(s) schemes before interception
  can run. Relative URLs and virtual-origin URLs work.
- `WebResourceRequest` exposes no request body, so virtual origins are
  effectively GET/HEAD-only. Writes (uploads, POSTs) go directly to the
  node API at `http://127.0.0.1:1633` — allowed from https pages
  because localhost is exempt from mixed-content blocking.

The full dapp compatibility contract lives in
`docs/dapp-compatibility.md` (companion issue), and each guarantee is
backed by an instrumented test.
