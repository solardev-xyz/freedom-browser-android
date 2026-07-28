# publicsuffix/list PR description (ready to paste)

Paste everything below the line into the PR body at
<https://github.com/publicsuffix/list> after applying
`public_suffix_list.patch`. It is the upstream pull-request template
(fetched 2026-07-28) with every FILL IN answered.

Two placeholders still need a human before submitting:

- `<PR-NUMBER>` in the DNS Verification section — you only learn it
  after opening the PR, so open it, then create the TXT records, then
  edit this section to match (see `dns-records.md`).
- `<ABUSE-CONTACT-URL>` — must be reachable *before* filing; upstream
  rejects submissions without one.

---

### Checklist of required steps

* [x] Description of Organization
* [x] Robust Reason for PSL Inclusion
* [x] DNS verification via dig

* [x] Each domain listed in the PRIVATE section has and shall maintain at least two years remaining on registration, and we shall keep the `_psl` TXT record in place in the respective zone(s).

__Submitter affirms the following:__

 * [x] We are listing *any* third-party limits that we seek to work around in our rationale such as those between iOS 14.5+ and Facebook

 None. This request works around no third-party limit. We issue a single
 wildcard certificate per namespace from one ACME account, so Let's
 Encrypt rate limits are not a factor, and we do not use Cloudflare for
 these zones.

 * [x] This request was _not_ submitted with the objective of working around other third-party limits.
 * [x] The submitter acknowledges that it is their responsibility to maintain the domains within their section. This includes removing names which are no longer used, retaining the _psl DNS entry, and responding to e-mails to the supplied address. Failure to maintain entries may result in removal of individual entries or the entire section.
 * [x] The [Guidelines](https://github.com/publicsuffix/list/wiki/Guidelines) were carefully _read_ and _understood_, and this request conforms to them.
 * [x] The submission follows the [Guidelines](https://github.com/publicsuffix/list/wiki/Format) on formatting and sorting.
 * [x] A role-based email address has been used and this inbox is actively monitored with a response time of no more than 30 days.

**Abuse Contact:**

* [x] Abuse contact information (email or web form) is available and easily accessible.

  URL where abuse contact or abuse reporting form can be found:
  <ABUSE-CONTACT-URL>

---

 * [x] *Yes, I understand*. I could break my organization's website cookies and cause other issues, and the rollback timing is acceptable. *Proceed anyway*.

Noted specifically: this request does **not** touch `freedom.baby`
itself, only the four machine-generated namespaces beneath it, so the
organization's own website cookies are unaffected either way.

---

## Description of Organization

Freedom Browser is an open-source Android web browser (package
`baby.freedom.mobile`, source at
<https://github.com/solardev-xyz/freedom-browser-android>) that renders
content addressed on decentralised storage networks — Swarm (`bzz://`),
IPFS (`ipfs://`), IPNS (`ipns://`) and ENS names — alongside ordinary
`https://` sites. Both storage clients are embedded in the app and run
on the device; there is no server-side rendering component.

I am Meinhard Benn, the maintainer of the project, submitting on its
behalf. The project is a small independent engineering effort, not a
hosting company: we do not sell, rent or delegate subdomains to anyone.
Every name under the four requested suffixes is generated
deterministically by the app from a content hash or a decentralised
name, and there is no registration process a third party can go through
to obtain one.

The requested suffixes exist to give each piece of decentralised content
its own web origin. They are the direct analogue of the existing
`*.dweb.link` entry submitted by the IPFS Project, and they are used for
exactly the same purpose.

**Organization Website:**
https://github.com/solardev-xyz/freedom-browser-android

## Reason for PSL Inclusion

**Cookie and storage security / origin isolation.** The browser serves
each content root from its own synthetic host under one of four
namespaces:

```
bzz://<32-byte-swarm-ref>/…      → https://<base36-label>.bzz.freedom.baby/…
ipfs://<cid>/…                   → https://<cidv1-base36>.ipfs.freedom.baby/…
ipns://<key-or-dnslink-name>/…   → https://<label>.ipns.freedom.baby/…
<name>.eth/…                     → https://<name-escaped>.ens.freedom.baby/…
```

Without a PSL entry every one of those hosts shares the registrable
domain `freedom.baby`. That means any decentralised page could set a
cookie on `.freedom.baby` and read it from every other decentralised
page; SameSite classification, `document.domain`, and renderer
site-isolation grouping would all treat mutually untrusted,
anonymously-published content as one site. Content on these networks is
published by anyone, immutably and pseudonymously, so treating it as one
site is precisely the wrong default. Listing the four namespaces makes
each generated label its own registrable domain and its own site, which
is the isolation property the design depends on.

This is the same rationale, and the same shape of entry, as the IPFS
Project's `*.dweb.link` / `*.inbrowser.link` entries already in the
PRIVATE section, and as the various dynamic-DNS and PaaS entries where
one operator hands out per-tenant subdomains.

Two secondary properties follow from the same entry: wildcard TLS
certificates for the namespaces cannot be used to set cookies across
sibling content, and a shared link that leaks out of the app (pages copy
`window.location.href` into share buttons) resolves through a small
redirector we run to a public gateway, so it behaves like a normal
web link in any browser.

We are *not* asking for `freedom.baby` itself, and nothing about the
project's own website changes.

`freedom.baby` is registered to the project with more than two years
remaining on the term, will be renewed for as long as the entry remains
in the list, and the `_psl` TXT records below will stay in place.

No previous issues or PRs relate to this submission.

**Number of THOUSANDS of distinct users this request is being made to serve:**

<!--
Answer honestly. If the app is still below the ~2000-3000 distinct-user
threshold in the Guidelines, say so plainly with the current number and
the trajectory rather than rounding up — an inflated figure is the
fastest way to lose the maintainers' trust, and a clearly-stated small
number with a strong isolation rationale is a better position than a
number that cannot be substantiated. See infra/psl/README.md.
-->

<CURRENT-DISTINCT-USER-COUNT — see note above>

## DNS Verification

```
dig +short TXT _psl.bzz.freedom.baby
"https://github.com/publicsuffix/list/pull/<PR-NUMBER>"
```

```
dig +short TXT _psl.ipfs.freedom.baby
"https://github.com/publicsuffix/list/pull/<PR-NUMBER>"
```

```
dig +short TXT _psl.ipns.freedom.baby
"https://github.com/publicsuffix/list/pull/<PR-NUMBER>"
```

```
dig +short TXT _psl.ens.freedom.baby
"https://github.com/publicsuffix/list/pull/<PR-NUMBER>"
```
