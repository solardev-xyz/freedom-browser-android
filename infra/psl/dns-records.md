# `_psl` DNS records

> ⚠️ **Human-only.** These records were not created — that needs
> registrar / DNS-provider credentials.

The PSL uses a `_psl` TXT record in each submitted zone as proof that
the person opening the pull request controls the domain. Upstream's
template asks for one record **per entry**, so this submission needs
four, not one on the apex.

## The records

```
_psl.bzz.freedom.baby.    3600 IN TXT "<PR-URL>"
_psl.ipfs.freedom.baby.   3600 IN TXT "<PR-URL>"
_psl.ipns.freedom.baby.   3600 IN TXT "<PR-URL>"
_psl.ens.freedom.baby.    3600 IN TXT "<PR-URL>"
```

`<PR-URL>` is the full URL of the publicsuffix/list pull request, e.g.
`https://github.com/publicsuffix/list/pull/2914`. Nothing else goes in
the record — no prefix, no second string.

Optionally also publish the same value at `_psl.freedom.baby.`. It is
not required (the apex is not being submitted) but it is harmless and
some third-party PSL tooling looks one level up.

## The chicken-and-egg

The record has to contain the PR URL, and the PR URL only exists once
the PR is open. The order is therefore:

1. Apply `public_suffix_list.patch` to a fork of publicsuffix/list.
2. Open the PR with the body from `pr-description.md`, leaving
   `<PR-NUMBER>` as-is for the moment.
3. Read the PR number off the URL GitHub gives you.
4. Create the four TXT records above with that URL.
5. Edit the PR body, replacing `<PR-NUMBER>` with the real number.

Do not leave a placeholder record ("pending", "TBD") in DNS in step 4 —
maintainers check the record against the PR and a mismatch reads as a
failed verification.

## Verify

```sh
for ns in bzz ipfs ipns ens; do
  printf '%-6s %s\n' "$ns" "$(dig +short TXT _psl.$ns.freedom.baby)"
done
```

All four must print the same quoted PR URL before you ask for review.
Check against a public resolver (`dig @1.1.1.1 …`) too, not just the
provider's own nameserver, and wait out the TTL of any negative cache
from an earlier lookup.

## Keep them

The records are not a one-time gate. Upstream states that entries whose
`_psl` record disappears may be removed from the list, and future
automation is planned to do exactly that. They stay for as long as the
entry is wanted — as does the registration itself, which must never fall
below one year remaining.
