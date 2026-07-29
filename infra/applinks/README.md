# Android App Links

> ⚠️ **Human-only.** `assetlinks.json` here is a template with a
> placeholder fingerprint, and nothing is hosted. Reading the release
> keystore and publishing files on the domains both need credentials
> this repo does not have.

A virtual-origin URL that leaks out of the app (pages copy
`window.location.href` into share buttons) is a real `https://` URL. On
a device without Freedom it resolves through the redirector to a public
gateway; on a device *with* Freedom, App Links makes it open in the app
at the right content instead — a universal-link capability the desktop
`bzz://` scheme cannot offer.

The app side is already done: `AndroidManifest.xml` carries an
`android:autoVerify="true"` intent filter for `*.bzz.freedom.baby`,
`*.ipfs.freedom.baby`, `*.ipns.freedom.baby` and `*.ens.freedom.baby`,
and `MainActivity` routes the incoming URL back through
`VirtualOrigin.displayUrlFor` before opening it. What is missing is the
domain's half of the handshake.

## Where the file goes

The same file, byte for byte, at four URLs:

```
https://bzz.freedom.baby/.well-known/assetlinks.json
https://ipfs.freedom.baby/.well-known/assetlinks.json
https://ipns.freedom.baby/.well-known/assetlinks.json
https://ens.freedom.baby/.well-known/assetlinks.json
```

Requirements Android enforces during verification:

- served over **https** with a valid certificate — the wildcard cert
  from `infra/redirector/deploy.md` covers these names only if the bare
  namespace hosts were included as SANs alongside the wildcards, which
  the certbot invocation there does;
- `Content-Type: application/json`;
- **no redirects** — not even http→https, and not the redirector's own
  301. Serve it as a static file from the reverse proxy, above the
  proxy_pass, so verification never depends on the redirector process
  being up;
- reachable without authentication, and to a crawler outside your
  network.

For a wildcard host like `*.bzz.freedom.baby`, Android verifies the
domain *without* the wildcard, i.e. `bzz.freedom.baby`. That is why
there are four files rather than one on the apex — `freedom.baby` itself
is never checked, and hosting it only there does nothing.

## Getting the fingerprint

The placeholder `<RELEASE-CERT-SHA256-FINGERPRINT>` must be the SHA-256
of the certificate that signs the **published** APKs — the key in
`FREEDOM_KEYSTORE_FILE` / the repo's release secrets (see
`app/build.gradle.kts`), not the debug key.

From the keystore:

```sh
keytool -list -v \
  -keystore "$FREEDOM_KEYSTORE_FILE" \
  -alias "${FREEDOM_KEY_ALIAS:-freedom}" \
  | grep -A1 'SHA256:'
```

Or from a signed APK, which is the safer check because it proves what
actually shipped:

```sh
unzip -p app-release.apk META-INF/*.RSA | keytool -printcert | grep 'SHA256:'
```

Either way the value is 32 uppercase hex bytes separated by colons:

```
14:6D:E9:83:C5:73:06:50:D8:EE:B9:95:2F:34:FC:64:16:A0:83:42:E6:1D:BE:A8:8A:04:96:B2:3F:CF:44:E5
```

Paste it into `assetlinks.json` in place of the placeholder, keeping the
quotes.

If the app is ever distributed through Google Play with Play App
Signing, the fingerprint that matters is the **app signing key** shown
in Play Console → Setup → App integrity, not the upload key. Both can be
listed in the array at once; App Links accepts any match, which is also
how you rotate a key without a flag day.

## Verifying

After the app is installed and the files are live:

```sh
adb shell pm verify-app-links --re-verify baby.freedom.mobile
adb shell pm get-app-links baby.freedom.mobile
```

Every domain should read `verified`. `none` usually means the file was
unreachable, redirected, or served with the wrong content type;
`legacy_failure` means the fingerprint did not match. Google's tester at
`https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://bzz.freedom.baby&relation=delegate_permission/common.handle_all_urls`
reports the same thing without a device.

Verification runs at install time and needs network, so a device that
installed the app offline stays unverified until a re-verify. Until
then, links still open the app — the user just gets the disambiguation
chooser instead of going straight there.
