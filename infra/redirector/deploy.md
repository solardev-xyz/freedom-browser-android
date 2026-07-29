# Deploying the redirector

> ⚠️ **Human-only.** Nothing in this file was executed. It is the
> operator runbook for DNS, TLS and the actual deploy — all of which
> need registrar/ACME credentials.

The service itself is `redirector.js` (no npm dependencies, `node:http`
only). It terminates nothing: put it behind a TLS reverse proxy and give
it the original hostname via `Host` or `X-Forwarded-Host`.

## 1. DNS

Four wildcard records, one per namespace, all pointing at the same host:

```
*.bzz.freedom.baby.    300 IN A     <redirector-ipv4>
*.ipfs.freedom.baby.   300 IN A     <redirector-ipv4>
*.ipns.freedom.baby.   300 IN A     <redirector-ipv4>
*.ens.freedom.baby.    300 IN A     <redirector-ipv4>
```

Add `AAAA` equivalents if the host has IPv6.

Also create the apex records for the four base domains themselves
(`bzz.freedom.baby` and friends). They are not virtual origins — the
redirector 404s on them — but Android App Links verification fetches
`https://bzz.freedom.baby/.well-known/assetlinks.json`, so they must
resolve and serve that file (see `infra/applinks/README.md`).

Encrypted Swarm references use **two** labels
(`<hi>.<lo>.bzz.freedom.baby`), so `*.bzz.freedom.baby` alone does not
cover them — a single wildcard label matches one level only. Either add
`*.*.bzz.freedom.baby` (many providers reject this) or a second wildcard
zone; check your provider before assuming encrypted refs resolve. The
in-app path is unaffected either way: the WebView never does DNS for
these hosts.

## 2. Wildcard TLS

Wildcard certificates require the **DNS-01** challenge — HTTP-01 cannot
validate a wildcard. With certbot and a DNS plugin:

```sh
certbot certonly \
  --dns-<provider> \
  --dns-<provider>-credentials /etc/letsencrypt/<provider>.ini \
  -d '*.bzz.freedom.baby'  -d bzz.freedom.baby \
  -d '*.ipfs.freedom.baby' -d ipfs.freedom.baby \
  -d '*.ipns.freedom.baby' -d ipns.freedom.baby \
  -d '*.ens.freedom.baby'  -d ens.freedom.baby
```

Notes:

- One SAN certificate for all eight names keeps renewal to a single job.
- The `_acme-challenge` TXT records must be writable by the ACME client;
  that API token is the reason this step is human-only.
- Renewal is DNS-01 too, so the token has to stay valid — set a reminder
  independent of certbot's own timer.
- If you go the `*.*.bzz.freedom.baby` route for encrypted refs, the
  certificate needs that name as well; Let's Encrypt issues it, but many
  clients need it quoted exactly.

## 3. Run it

```sh
docker build -t freedom-redirector infra/redirector
docker run -d --restart=unless-stopped -p 127.0.0.1:8080:8080 \
  -e BASE_DOMAIN=freedom.baby \
  freedom-redirector
```

Environment (all optional, defaults shown):

| Variable             | Default                        | Meaning                                       |
| -------------------- | ------------------------------ | --------------------------------------------- |
| `PORT`               | `8080`                         | listen port                                   |
| `BASE_DOMAIN`        | `freedom.baby`                 | suffix the four namespaces hang off           |
| `SWARM_GATEWAY`      | `https://gateway.ethswarm.org` | path-style Swarm gateway base                 |
| `IPFS_GATEWAY_HOST`  | `dweb.link`                    | subdomain-style IPFS gateway host             |
| `IPNS_GATEWAY_HOST`  | `dweb.link`                    | subdomain-style IPNS gateway host             |
| `ENS_GATEWAY_SUFFIX` | `limo`                         | suffix appended to ENS names (`<name>.limo`)  |

The public gateways are the defaults so a fresh deploy works; swap them
for your own if the share-link traffic ever matters.

## 4. Reverse proxy

Anything that terminates TLS and forwards the original host works. nginx:

```nginx
server {
    listen 443 ssl;
    server_name *.bzz.freedom.baby *.ipfs.freedom.baby
                *.ipns.freedom.baby *.ens.freedom.baby;

    ssl_certificate     /etc/letsencrypt/live/freedom.baby/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/freedom.baby/privkey.pem;

    location / {
        proxy_pass       http://127.0.0.1:8080;
        proxy_set_header X-Forwarded-Host $host;
    }
}
```

Serve `/.well-known/assetlinks.json` from the four base domains directly
in the proxy (static file, `Content-Type: application/json`) — the
redirector deliberately does not, so App Links verification never
depends on this service being up.

## 5. Check

```sh
curl -sI https://3kescpgjpg23w0jk9ccszdtmgq3mcqnthwg1oxbfcnb6w68t71.bzz.freedom.baby/ \
  | grep -i location
# → location: https://gateway.ethswarm.org/bzz/8f1d385f…abcd/
```

That reference is the pinned test vector from `test-vectors.json`; it
does not exist on Swarm, so the gateway will 404 — the point is that the
`Location` header is right.
