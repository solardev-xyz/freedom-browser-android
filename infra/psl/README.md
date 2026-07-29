# Public Suffix List submission

> ⚠️ **File this the day it lands. It is the critical path.**
>
> The entry only starts working once it has propagated through a
> Chromium release into users' Android System WebView. Upstream review
> alone takes weeks to months, and the browser release trains add more
> on top — [the PSL's own
> guidance](https://github.com/publicsuffix/list/wiki/Guidelines#appropriate-expectations-on-derivative-propagation-use-or-inclusion)
> is explicit that neither the volunteers nor we control that timing.
> Everything else in `infra/` takes an afternoon; this one takes
> months of wall-clock time, so it goes first and everything else
> proceeds in parallel behind it.
>
> ⚠️ **Human-only.** Nothing here was submitted. Opening the pull
> request against publicsuffix/list, and creating the DNS records that
> prove domain control, both need credentials this repo does not have.

## What is being requested

Four entries in the PRIVATE DOMAINS section:

```
bzz.freedom.baby
ens.freedom.baby
ipfs.freedom.baby
ipns.freedom.baby
```

Not `freedom.baby` — the project's own site is deliberately untouched.

## Why

The app serves every dweb content root from its own synthetic https
origin under one of those four namespaces (see `docs/virtual-origins.md`
and `VirtualOrigin.kt`). Until the namespaces are public suffixes, all
of those origins share the registrable domain `freedom.baby`: one shared
cookie jar for mutually untrusted, anonymously-published content, plus
shared SameSite classification and renderer site grouping. The PSL entry
makes each generated label its own registrable domain. This is the
`*.dweb.link` precedent — the IPFS Project's entry exists for exactly
this reason, and this submission is modelled on it.

## Files here

| File                       | What it is                                                     |
| -------------------------- | -------------------------------------------------------------- |
| `public_suffix_list.patch` | the exact diff against `public_suffix_list.dat`                 |
| `pr-description.md`        | the upstream PR template, filled in, ready to paste             |
| `dns-records.md`           | the `_psl` TXT records to create, and the order to do it in     |

## The order of operations

1. Fork publicsuffix/list, apply `public_suffix_list.patch`.
2. Open the PR with the body from `pr-description.md`.
3. Create the four `_psl` TXT records pointing at the new PR URL.
4. Update the PR body's `<PR-NUMBER>` placeholders.
5. Wait. Then keep waiting — see the propagation warning above.

The app is correct either way: the cookie sweep in the hardening work
stays as defense in depth regardless of whether the entry has landed,
and virtual origins function (minus cross-root cookie isolation) before
it does.

## Placement in the file

Alphabetical by the organization name on the first comment line, which
puts `// Freedom Browser` between the `freedesktop.org` and
`freemyip.com` blocks. Within our own block the four names are sorted
ascending. Mis-sorted entries are the single most common cause of delay
upstream, so re-check the placement if the file has moved on since the
patch was written (2026-07-28 snapshot).

## Known friction — decide these before filing

Three things in this package will draw review comments. None are
blockers, but going in with an answer is faster than being asked.

- **Personal vs role-based contact address.** The patch and PR body use
  `mufuti@gmail.com`, as pinned in issue #6. Upstream's template
  explicitly asks for a *role-based* address on the organization's own
  domain (e.g. `psl@freedom.baby`) so the contact survives personnel
  changes, and that inbox must answer within 30 days. Consider standing
  up an alias and swapping it into both the patch and the PR body before
  filing — changing it afterwards means another PR.
- **Abuse contact.** `pr-description.md` leaves `<ABUSE-CONTACT-URL>`
  blank. Upstream requires a reachable abuse contact (address or web
  form) that a third party can find from the domain. This must exist
  *before* the PR is opened.
- **User-count threshold.** The contributing guidelines say smaller
  private projects with fewer than ~2000-3000 distinct stakeholders may
  be rejected. Answer the count question honestly; the isolation
  rationale is strong and stands on its own, and an unsupportable number
  is worse than a small one.

## One caveat the entry does not fix

Encrypted Swarm references use **two** labels
(`<hi>.<lo>.bzz.freedom.baby`). With `bzz.freedom.baby` as a public
suffix, the registrable domain of such a host is `<lo>.bzz.freedom.baby`
— the *low* chunk alone. Two distinct encrypted references sharing a low
chunk would therefore share a site. That is a 128-bit collision, so it
is not a practical concern, and the alternative (`*.bzz.freedom.baby`)
would break the common single-label case by making plain references
public suffixes with no site of their own. Recorded here so the choice
is not re-litigated from scratch later.
