# Localisation

Every piece of text Freedom shows (Compose screens, dialogs, snackbars,
toasts, notifications, the error page and other pages Freedom serves
itself) comes from Android string resources (#280). The app ships in
English only for now; this is the groundwork that makes a translation a
resources-only change.

## Adding a language

1. Copy each `app/src/main/res/values/strings*.xml` into
   `app/src/main/res/values-<lang>/` (e.g. `values-de`, `values-pt-rBR`)
   and translate the `<string>` and `<plurals>` entries. Leave out
   anything marked `translatable="false"`. Give every `<plurals>` the
   quantities the language needs (`zero`, `one`, `two`, `few`, `many`,
   `other`).
2. Nothing else. The per-app language list Android 13+ shows in
   *Settings → Apps → Freedom → Language* is generated from the
   `values-<lang>` folders (`generateLocaleConfig` in
   `app/build.gradle.kts`; `res/resources.properties` says the default
   `values/` is English), and *Settings → Appearance → Language* in the
   app appears as soon as there is more than one language.

## Where strings live

Resource files are split by area, one per screen group, so parallel
changes don't collide: `strings_common.xml` (actions many screens share:
Cancel, Save, …), `strings_settings.xml`, `strings_wallet.xml`, and so on.
Each file's names start with its area (`settings_…`, `wallet_…`), since all
files share one namespace.

## Writing code

- **Compose**: `stringResource(R.string.x)`,
  `stringResource(R.string.x, arg)`,
  `pluralStringResource(R.plurals.x, count, count)`.
- **Outside Compose** (a function building a status line, an error
  message a screen shows, a notification): `Strings.get(R.string.x, …)` and
  `Strings.plural(R.plurals.x, count, …)` from `baby.freedom.mobile.l10n`.
  It works in every process and needs no `Context`. JVM unit tests read the
  same English XML through `ResourceXmlStrings`, so a test can keep
  asserting the English text.
- **Counts** go through `<plurals>`, never `if (n == 1) "tab" else "tabs"`.
- **Whole sentences**, not concatenated pieces: word order differs between
  languages. Use positional arguments (`%1$s`, `%2$d`) so a translation can
  reorder them.
- **Numbers, dates and durations** are formatted for the user's locale:
  `%d` / `%1$d` in a resource (locale-formatted by `getString`),
  `NumberFormat`, `java.text.DateFormat`, `android.text.format.DateUtils`,
  `Formatter.formatShortFileSize`. Machine-readable text (URLs, file
  names, logs, JSON sent to pages, anything parsed back) stays
  `Locale.ROOT`/`Locale.US`.
- **Token amounts** keep their fixed format (`.` decimal point, no
  grouping) everywhere they appear, above all on review and approval
  sheets: an amount there is security-relevant and must read the same
  whatever the phone's language, and it has to match what a block explorer
  or the dapp shows.
- **Not translated** (stay literals): protocol and wire strings, EIP-1193
  / JSON-RPC error messages sent to pages (developer-facing), log lines,
  persisted keys, test fixtures, brand and network names inside a
  sentence stay in the resource text (`Swarm`, `ENS`, `IPFS`).

## Pages Freedom serves

The error page (`assets/error/error.html`) and the Radicle viewer
(`assets/rad/`) take their text from string resources too: the app hands
the page a JSON table of the strings it needs, and the page's script
looks each one up instead of carrying English literals.

## The lint check

`./gradlew :app:lintDebug` runs the `HardcodedUiText` check (the
`:lint-checks` module), which fails on a string literal passed as user-visible
text: Compose `Text("…")` and `contentDescription = "…"`, `Toast.makeText`,
snackbar messages and notification text. Text that genuinely isn't
language (a symbol, a sample address) can be suppressed with
`@Suppress("HardcodedUiText")` and a comment saying why.
