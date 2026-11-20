package baby.freedom.mobile.agent

import baby.freedom.mobile.agent.PublicationScan.Kind
import baby.freedom.mobile.wallet.Bip39English
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Desktop's publication-scan.test.js, ported: one true positive and at
 * least one look-alike that must stay quiet per rule, redaction, the caps,
 * the backstop, failing closed, binary content and generated-code hygiene.
 */
class PublicationScanTest {
    private fun rulesIn(text: String) = PublicationScan.scanText("file.txt", text).map { it.rule }

    // Values are assembled at runtime so this file itself doesn't look like
    // it leaks credentials to repository scanners.
    private fun join(vararg parts: String) = parts.joinToString("")
    private val hexKey = "4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318"

    private class Case(val match: List<String>, val quiet: List<String>)

    private val cases: Map<String, Case> by lazy {
        mapOf(
            "private-key-block" to Case(
                listOf(join("-----BEGIN ", "RSA PRIVATE KEY-----\nMIIEow…")),
                listOf("-----BEGIN PUBLIC KEY-----", "BEGIN PRIVATE KEY"),
            ),
            "aws-access-key" to Case(listOf(join("AKIA", "Q3EGRZ7NKVB2XJ4M")), listOf(join("AKIA", "IOSFODNN7EXAMPLE"), "AKIA123")),
            "openai-api-key" to Case(
                listOf(join("sk-", "proj-", "a8Fh2kL9mQ3rT6vW1yZ4bC7d")),
                listOf("sk-your-key-here-xxxxxxxxxxxx", "desk-a8Fh2kL9mQ3rT6vW1yZ4bC7d"),
            ),
            "github-token" to Case(listOf(join("ghp_", "a8Fh2kL9mQ3rT6vW1yZ4bC7dE0gJ5n")), listOf("ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxx", "ghp_short")),
            "slack-token" to Case(listOf(join("xoxb-", "1234567890-0987654321-AbCdEfGh")), listOf("xoxb-your-token", "xoxz-1234567890-0987654321")),
            "jwt" to Case(
                listOf(join("eyJ", "hbGciOiJIUzI1NiJ9", ".", "eyJzdWIiOiIxMjM0NTY3ODkwIn0", ".", "dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U")),
                listOf("eyJhbGciOiJIUzI1NiJ9 alone"),
            ),
            "url-credentials" to Case(
                listOf(join("https://deploy:", "Tr0ub4dor3x", "@registry.internal/pkg"), join("postgres://app:", "Zq8vPq2m", "@db:5432/app")),
                listOf("https://user:password@example.com", "https://user:\${PASSWORD}@host", "mailto:me@x.org"),
            ),
            "credential-assignment" to Case(
                listOf(join("password = \"", "Zq8vPq2mRt", "\""), join("API_KEY=", "Zq8vPq2mRt7w")),
                listOf("password = \"your_password\"", "apiKey: process.env.API_KEY", "token: \"<insert token>\""),
            ),
            "ethereum-private-key" to Case(
                listOf("const PRIVATE_KEY = '0x$hexKey';", "deployer pk: $hexKey"),
                listOf(
                    "Transaction 0x$hexKey confirmed",
                    "<a href=\"bzz://$hexKey/\">site</a>",
                    "const PRIVATE_KEY = '0x${"0".repeat(64)}';",
                    "privateKey: 0x${"1234".repeat(16)}",
                ),
            ),
            "keystore-json" to Case(
                listOf(
                    "{\"version\":3,\"crypto\":{\"cipher\":\"aes-128-ctr\",\"ciphertext\":\"" + "a".repeat(8) +
                        "b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4\",\"kdf\":\"scrypt\"}}",
                ),
                listOf("{\"ciphertext\": \"abc\", \"note\": \"keystore docs\"}", "{\"ciphertext\":\"b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4ff\"}"),
            ),
            "rpc-url-key" to Case(
                listOf(
                    "https://mainnet.infura.io/v3/9aa3d95b3bc440fa88ea12eaa4456161",
                    join("wss://eth-mainnet.g.alchemy.com/v2/", "Zq8vPq2mRt7wK1yB3nD5fH"),
                    "https://polished-wild-wave.quiknode.pro/0d1f4c6e8a2b3c5d7e9f",
                ),
                listOf("https://mainnet.infura.io/v3/YOUR_PROJECT_ID", "https://eth-mainnet.g.alchemy.com/v2/demo", "https://rpc.gnosischain.com"),
            ),
            "stripe-secret-key" to Case(
                listOf(join("sk_live_", "51HqLyjWDarjtT1zdp7dc9a"), join("rk_test_", "51HqLyjWDarjtT1zdp7dc9a")),
                listOf(join("pk_live_", "51HqLyjWDarjtT1zdp7dc9a"), join("sk_live_", "x".repeat(24))),
            ),
            "google-api-key" to Case(listOf(join("AIza", "SyD3k9mQ3rT6vW1yZ4bC7dE0gJ5nP8sU2xA")), listOf("AIzaSy...", "AIzaShort")),
            "slack-webhook" to Case(
                listOf(join("https://hooks.slack.com/services/", "T0A1B2C3D/B4E5F6G7H/", "q8vPq2mRt7wK1yB3nD5fH9jL")),
                listOf(join("https://hooks.slack.com/services/", "X".repeat(9), "/", "X".repeat(9), "/", "X".repeat(24)), "https://slack.com/help"),
            ),
            "discord-webhook" to Case(
                listOf(join("https://discord.com/api/webhooks/", "112233445566778899/", "q8vPq2mRt7wK1yB3nD5fH9jL0aZ4cE6gI8kM")),
                listOf("https://discord.com/api/webhooks/{id}/{token}", "https://discord.com/invite/abc"),
            ),
            "twilio-api-key" to Case(listOf(join("SK", "2f7b9c4d1e8a3f5b6c0d9e7a1b4c8d2e")), listOf(join("SK", "0".repeat(32)), "SKU2f7b9c4d1e8a3f5")),
            "sendgrid-api-key" to Case(
                listOf(join("SG.", "q8vPq2mRt7wK1yB3nD5fH9", ".", "jL0aZ4cE6gI8kMq8vPq2mRt7wK1yB3nD5fH9jL0aZ4c")),
                listOf("SG.your-key-here", "SG.short.short"),
            ),
            "email-address" to Case(
                listOf("Contact alice.smith@proton.me for details"),
                listOf("info@example.com", "logo@2x.png", "git@github.com:org/repo.git", "npm i pkg@1.2.3"),
            ),
            "phone-number" to Case(
                listOf("Call +49 30 9876 5432", "<a href=\"tel:+14155552671\">"),
                listOf("Version 2.0.1", "+1 (555) 123-4567", "Order #4155552671"),
            ),
            "iban" to Case(
                listOf("IBAN DE44 5001 0517 5407 3249 31"),
                listOf("DE44 5001 0517 5407 3249 32", "DE89 3704 0044 0532 0130 00", "ABCD12345678"),
            ),
            "postal-address" to Case(
                listOf("Visit us at 221 Baker Street", "Büro: Hauptstraße 12"),
                listOf("Step 3 Open Settings", "123 Main Street", "Musterstraße 1"),
            ),
        )
    }

    @Test
    fun `every rule has a true and a false positive case`() {
        assertEquals((cases.keys + "bip39-seed-phrase").sorted(), PublicationScan.RULES.map { it.id }.sorted())
    }

    @Test
    fun `each rule matches real values and ignores placeholders`() {
        for ((rule, case) in cases) {
            for (text in case.match) assertTrue("$rule should match: $text (got ${rulesIn(text)})", rule in rulesIn(text))
            for (text in case.quiet) assertFalse("$rule should stay quiet: $text", rule in rulesIn(text))
        }
    }

    @Test
    fun `excerpts are redacted so the value cannot be reused`() {
        val secret = join("sk_live_", "51HqLyjWDarjtT1zdp7dc9a")
        val finding = PublicationScan.scanText("pay.js", "\n\nstripe(\"$secret\")").single()
        assertEquals("pay.js", finding.path)
        assertEquals(Kind.Secret, finding.kind)
        assertEquals(3, finding.line)
        assertTrue(finding.blocking)
        assertFalse(finding.excerpt.contains(secret.substring(8)))
        assertEquals(
            "https://mainnet.infura.io/v3/••••••••",
            PublicationScan.scanText("a", "https://mainnet.infura.io/v3/9aa3d95b3bc440fa88ea12eaa4456161")[0].excerpt,
        )
    }

    @Test
    fun `a key assignment is reported once, under its specific rule`() {
        assertEquals(listOf("ethereum-private-key"), rulesIn("const PRIVATE_KEY = '0x$hexKey';"))
    }

    @Test
    fun `personal data warns instead of blocking`() {
        val f = PublicationScan.scanText("a", "alice.smith@proton.me")[0]
        assertEquals(Kind.Personal, f.kind)
        assertFalse(f.blocking)
    }

    @Test
    fun `anything the backstop refuses is still reported`() {
        val text = "password: \"xxxxxxxxzzzzzzzz\""
        assertTrue(SecretBackstop.containsSecret(text))
        val f = PublicationScan.scanText("a", text).single()
        assertEquals("possible-credential", f.rule)
        assertTrue(f.blocking)
        assertEquals("Value hidden", f.excerpt)
    }

    private fun phrase(count: Int, offset: Int = 0): List<String> =
        (0 until count).map { Bip39English.words[(offset + it * 97) % Bip39English.words.size] }

    @Test
    fun `flags 12 and 24 word recovery phrases, including numbered backup sheets`() {
        for (n in listOf(12, 24)) {
            val words = phrase(n)
            assertTrue("bip39-seed-phrase" in rulesIn("Backup: ${words.joinToString(" ")}"))
            val numbered = words.mapIndexed { i, w -> "${i + 1}. $w" }.joinToString("\n")
            assertTrue("bip39-seed-phrase" in rulesIn(numbered))
            val excerpt = PublicationScan.scanText("a", words.joinToString(" "))[0].excerpt
            assertTrue(excerpt, Regex("^\\w+ •••• \\((12|24) words\\)$").matches(excerpt))
        }
    }

    @Test
    fun `eleven wordlist words, public test mnemonics and prose do not trip the seed rule`() {
        val words = phrase(12, 5)
        assertFalse("bip39-seed-phrase" in rulesIn(words.take(11).joinToString(" ")))
        assertFalse("bip39-seed-phrase" in rulesIn("${words.take(6).joinToString(" ")}. The ${words.drop(6).joinToString(" ")}"))
        assertFalse("bip39-seed-phrase" in rulesIn("test test test test test test test test test test test junk"))
        assertFalse("bip39-seed-phrase" in rulesIn("abandon ".repeat(11) + "about"))
        val readme = listOf(File("../README.md"), File("README.md")).firstOrNull { it.exists() }
        if (readme != null) {
            assertTrue(PublicationScan.scanText("docs", readme.readText()).none { it.rule == "bip39-seed-phrase" })
        }
    }

    @Test
    fun `the finding cap drops warnings, never secrets`() {
        val email = "alice.smith@proton.me\n"
        val stripe = join("sk_live_", "51HqLyjWDarjtT1zdp7dc9a") + "\n"
        val all = (0 until 12).flatMap { PublicationScan.scanText("people-$it.html", email.repeat(25)) } +
            PublicationScan.scanText("zz-last.js", stripe)
        val capped = PublicationScan.capFindings(all)
        assertEquals(200, capped.size)
        assertEquals(1, capped.count { it.path == "zz-last.js" && it.blocking })
        assertEquals((1..200).map { "finding_$it" }, capped.map { it.id })
        val many = (0 until 9).flatMap { PublicationScan.scanText("k$it.js", stripe.repeat(25)) }
        try {
            PublicationScan.capFindings(many)
            fail("more than 200 secrets must refuse the publication")
        } catch (e: PublicationScan.TooManyFindings) {
            assertEquals(225, e.count)
        }
    }

    @Test
    fun `every secret in a file is listed, only warnings are capped per file`() {
        val keys = (0 until 30).joinToString("\n") { join("sk_live_", "51HqLyjWDarjtT1zdp7dc", it.toString().padStart(3, 'q')) }
        val secrets = PublicationScan.scanText("keys.js", keys)
        assertEquals(30, secrets.size)
        assertTrue(secrets.all { it.blocking })
        val mixed = PublicationScan.scanText("mixed.html", "alice.smith@proton.me\n".repeat(40) + keys)
        assertEquals(30, mixed.count { it.blocking })
        assertEquals(25, mixed.count { !it.blocking })
    }

    @Test
    fun `a second secret on a credential's line is its own finding, the same value is reported once`() {
        val token = join("ghp_", "a8Fh2kL9mQ3rT6vW1yZ4bC7dE0gJ5n")
        val json = "{\"token\":\"$token\",\"password\":\"Tr0ub4dor&3horse\"}"
        val found = PublicationScan.scanText("Text", json)
        assertEquals(listOf("github-token", "credential-assignment"), found.map { it.rule })
        assertTrue(found.all { it.blocking && it.line == 1 })
        val aws = join("AKIA", "Q3EGRZ7NKVB2XJ4M")
        assertEquals(
            setOf("aws-access-key", "credential-assignment"),
            rulesIn("key=$aws password=\"Tr0ub4dor&3horse\"").toSet(),
        )
        // The token assigned to a credential name is one value: one finding.
        assertEquals(listOf("github-token"), rulesIn("token = \"$token\""))
        assertEquals(listOf("ethereum-private-key"), rulesIn("const PRIVATE_KEY = '0x$hexKey';"))
    }

    @Test
    fun `inline text gets every rule even with a NUL in it`() {
        val nbsp = phrase(12).joinToString("\u00A0")
        assertTrue("bip39-seed-phrase" in PublicationScan.scanPublication("Text", "\u0000$nbsp".toByteArray(), null).map { it.rule })
        assertTrue("email-address" in PublicationScan.scanPublication("Text", "\u0000 mail alice.smith@proton.me".toByteArray(), null).map { it.rule })
    }

    @Test
    fun `svg and xhtml published as text get the third-party check`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script src=\"https://tracker.example.net/t.js\"></script></svg>"
        for (type in listOf("image/svg+xml", "application/xhtml+xml; charset=utf-8")) {
            val found = PublicationScan.scanPublication("Text", svg.toByteArray(), type).filter { it.kind == Kind.ThirdParty }
            assertEquals(type, listOf("third-party-script" to "https://tracker.example.net"), found.map { it.rule to it.excerpt })
        }
        assertTrue(PublicationScan.scanPublication("Text", svg.toByteArray(), "text/plain").none { it.kind == Kind.ThirdParty })
    }

    @Test
    fun `binary content is scanned for secrets but not personal data`() {
        val key = join("sk-proj-", "Ab3dEf6hIj9kLm2nOp5qRs8tUv")
        val db = byteArrayOf(0, 1, 2, 3) + key.toByteArray() + byteArrayOf(0xc3.toByte(), 0) + " alice.smith@proton.me ".toByteArray()
        assertEquals(listOf("openai-api-key" to true), PublicationScan.scanBytes("data.db", db).map { it.rule to it.blocking })
        val clean = byteArrayOf(0) + "woff2 glyph table".toByteArray()
        assertEquals(emptyList<Any>(), PublicationScan.scanBytes("font.woff2", clean))
    }

    @Test
    fun `a token-shaped run of a million characters is scanned, not a stack overflow`() {
        val run = "Ab3dE9gHk2".repeat(100_000)
        val long = mapOf(
            "openai-api-key" to join("sk-", run),
            "github-token" to join("ghp_", run),
            "slack-token" to join("xoxb-", run),
            "jwt" to join("eyJ", run, ".", run.take(20), ".", run.take(20)),
            "stripe-secret-key" to join("sk_live_", run),
            "slack-webhook" to join("https://hooks.slack.com/services/", run),
            "discord-webhook" to join("https://discord.com/api/webhooks/12345/", run),
            "rpc-url-key" to join("https://eth-mainnet.g.alchemy.com/v2/", run),
        )
        for ((id, value) in long) {
            val rule = PublicationScan.RULES.first { it.id == id }
            assertEquals(id, listOf(value.length), rule.matches(" $value ").map { it.value.length }.toList())
        }
        val runOnly = " eyJ$run"
        assertFalse(SecretBackstop.containsSecret(runOnly))
        assertEquals(emptyList<Any>(), PublicationScan.scanPublication("Text", runOnly.toByteArray(), null))
        assertTrue(SecretBackstop.containsSecret(long.getValue("jwt")))
    }

    @Test
    fun `hostile runs scan without findings or a crash`() {
        fun run(unit: String) = unit.repeat(Math.ceil(300_000.0 / unit.length).toInt())
        val hostile = mapOf(
            "token" to " eyJ" + run("Ab3dE9gHk2"),
            "dashes" to run("-eyJ"),
            "email" to run("a"),
            "domain" to "x@" + run("a."),
            "quoted" to "password: \"" + run("a"),
            "bare" to "password = " + run("a") + " x",
            "url" to "http://u:" + run("a"),
            "keystore" to "\"ciphertext\": \"" + run("a"),
            "street" to "H" + run("a"),
            "spaces" to "priv" + run(" "),
            "assignSpaces" to "password" + run(" ") + "= \"x",
            "lineSpaces" to run("\n") + "password" + run(" ") + "= " + run("a") + " x",
            "keywords" to run(" token"),
            "pem" to "-----BEGIN " + run("A "),
            "kdf" to "\"ciphertext\": \"" + "a".repeat(40) + "\" \"kdf\"" + run(" "),
        )
        val started = System.currentTimeMillis()
        for ((name, text) in hostile) {
            assertEquals(name, emptyList<String>(), PublicationScan.scanPublication("blob.js", text.toByteArray(), null).map { it.rule }.distinct())
        }
        assertTrue("linear, not quadratic", System.currentTimeMillis() - started < 60_000)
    }

    @Test
    fun `assignments led by any Unicode space or long blank runs, and many-label emails, are found`() {
        val secret = "hunter2hunter2X"
        for (line in listOf(
            "﻿password = $secret\n",
            " password = $secret",
            "\u000cpassword = $secret",
            "\u000btoken: $secret",
            "\n\n   \n\tpassword=$secret",
            "password${" ".repeat(100)}= $secret",
            "password =${" ".repeat(100)}$secret  # prod",
            "password\n=\n$secret",
            "secret = \"$secret\"",
            "\"api_key\"${" ".repeat(100)}:${" ".repeat(80)}\"$secret\"",
            "token﻿=﻿'$secret'",
        )) {
            assertEquals(line, listOf("credential-assignment"), rulesIn(line))
            assertTrue(line, SecretBackstop.containsSecret(line))
        }
        for (line in listOf("x password = $secret", "password = $secret x", "password = \"short\"", "apassword = $secret")) {
            assertEquals(line, emptyList<String>(), rulesIn(line))
            assertFalse(line, SecretBackstop.containsSecret(line))
        }
        assertEquals(listOf("email-address"), rulesIn("bob@a.b.c.d.e.f.g.h.i.example-mail.de"))
        assertEquals(listOf("private-key-block"), rulesIn(join("-----BEGIN ", "A".repeat(60), " PRIVATE KEY-----")))
        assertTrue(SecretBackstop.containsSecret(join("-----BEGIN ", "A".repeat(60), " PRIVATE KEY-----")))
        assertFalse(SecretBackstop.containsSecret(join("-----BEGIN ", " PRIVATE KEY-----")))
    }

    @Test
    fun `the walked rules match what the original unbounded patterns matched`() {
        // Desktop's originals, in Java syntax with JavaScript's \s spelled out.
        val s = "[\\t\\n\\x0B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"
        val original = mapOf(
            "credential-assignment" to listOf(
                Regex(
                    "[\"']?(?:password|passwd|api[_-]?key|secret|token|access[_-]?token|auth[_-]?token|authorization|private[_-]?key|credential)[\"']?$s*[:=]$s*[\"'](?<v>[^\"'\\r\\n]{8}[^\"'\\r\\n]*)[\"']",
                    RegexOption.IGNORE_CASE,
                ) to Regex("^(?:\\$\\{|process\\.env\\.|your[_ -]|example|placeholder|changeme|test|dummy|<)", RegexOption.IGNORE_CASE),
                Regex(
                    "^$s*(?:password|passwd|api[_-]?key|secret|token|access[_-]?token|auth[_-]?token|private[_-]?key)$s*[:=]$s*(?<v>[A-Za-z0-9_+/.=-]{8}[A-Za-z0-9_+/.=-]*)$s*(?:#.*)?$",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
                ) to Regex("^(?:your[_-]|example|placeholder|changeme|test|dummy)", RegexOption.IGNORE_CASE),
            ),
            "email-address" to listOf(
                Regex("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}@(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,24}(?![A-Za-z0-9-])") to null,
            ),
            "private-key-block" to listOf(Regex("-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----") to null),
        )
        val pieces = listOf(
            "password", "token", "api_key", "access_token", " ", "  ", "\t", "\n", "\r\n", "﻿", " ", "\u000c",
            "=", ":", "\"", "'", "#", "x", "Qz9kLm2PwX", "a", ".", "-", "@", "bob", "de", "mail", "PRIVATE KEY", "-----", "-----BEGIN ",
            "RSA", "1", "secret", "password = ", "\npassword: ", " = \"", "='", "bob@", "q@", "a.", "mail.", "b-c.", "..",
            "Qz9kLm2PwXr4\"", "\n# c", " ", "\u000b", "PRIVATE KEY-----", "-----BEGIN RSA ",
            " ".repeat(70), "\n﻿password", "http://", "eyJ", "u:p@", "/", "test1234 ", "a.b.c.d.e.f.g.h.i.", "A".repeat(45),
        )
        val comparable = Regex("your|example|placeholder|changeme|dummy|test|\\.\\.\\.|[<>]|(.)\\1{7}|x{6}", RegexOption.IGNORE_CASE)
        var seed = 7L
        fun random(): Double {
            seed = (seed * 1103515245 + 12345) % 2147483648
            return seed / 2147483648.0
        }
        repeat(5_000) {
            val text = (0 until 2 + (random() * 14).toInt()).joinToString("") { pieces[(random() * pieces.size).toInt()] }
            for ((id, patterns) in original) {
                val expected = patterns.flatMap { (pattern, refuse) ->
                    pattern.findAll(text).map { if ("(?<v>" in pattern.pattern) (it.groups as MatchNamedGroupCollection)["v"]!!.value else it.value }
                        .filter { refuse == null || !refuse.containsMatchIn(it) }.toList()
                }.filterNot { comparable.containsMatchIn(it) }.sorted()
                val rule = PublicationScan.RULES.first { it.id == id }
                val actual = rule.matches(text).map { it.value }.filterNot { comparable.containsMatchIn(it) }.toList().sorted()
                assertEquals("$id in ${text.replace("\n", "\\n")}", expected, actual)
            }
        }
    }

    @Test
    fun `a rule that throws fails the scan closed with a blocking finding`() {
        val throwing = PublicationScan.RULES.map { rule ->
            if (rule.id != "jwt") rule else PublicationScan.Rule(rule.id, rule.kind, rule.label) { throw StackOverflowError() }
        }
        val findings = PublicationScan.scanText("big.txt", "nothing secret here", rules = throwing)
        assertEquals(listOf("scan-incomplete" to true), findings.map { it.rule to it.blocking })
        assertEquals(Kind.Secret, findings[0].kind)
        assertEquals(emptyList<Any>(), PublicationScan.scanText("big.txt", "nothing secret here"))
    }

    private val page = listOf(
        "<!doctype html><html><head>",
        "<link rel=\"canonical\" href=\"https://example.org/page\">",
        "<link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/x/dist/x.css\">",
        "<script src=\"//unpkg.com/lib@1/lib.js\"></script>",
        "<script src=\"app.js\"></script><link rel=\"icon\" href=\"favicon.png\">",
        "</head><body>",
        "<img alt=\"pixel\" src=\"https://tracker.example.net/p.gif?id=SECRET_TOKEN\">",
        "<img src=\"bzz://abc/logo.png\"><img src=\"data:image/png;base64,AAAA\"><img src=\"/logo.png\">",
        "<form method=post action='https://formspree.io/f/abcd'><input name=email></form>",
        "<form action=\"/subscribe\"></form><form action=\"#\"></form>",
        "<script>fetch(\"https://api.example.com/v1/data?key=SECRET_TOKEN\").then(r => r.json()); fetch(\"/local.json\")</script>",
        "</body></html>",
    ).joinToString("\n")

    @Test
    fun `third-party loads in HTML published as text warn by origin only`() {
        val findings = PublicationScan.scanPublication("Text", page.toByteArray(), "text/html; charset=utf-8")
        val hygiene = findings.filter { it.kind == Kind.ThirdParty }
        assertEquals(
            listOf(
                Triple("third-party-link", "https://cdn.jsdelivr.net", 3),
                Triple("third-party-script", "https://unpkg.com", 4),
                Triple("third-party-image", "https://tracker.example.net", 7),
                Triple("external-form", "https://formspree.io", 9),
                Triple("third-party-fetch", "https://api.example.com", 11),
            ),
            hygiene.map { Triple(it.rule, it.excerpt, it.line) },
        )
        assertTrue(findings.none { it.blocking })
        assertFalse(findings.toString().contains("SECRET_TOKEN"))
        // Only text published as HTML is checked.
        assertTrue(PublicationScan.scanPublication("Text", page.toByteArray(), "text/plain").none { it.kind == Kind.ThirdParty })
    }

    @Test
    fun `one finding per rule and origin, and relative, same-site and non-http references stay quiet`() {
        val images = (0 until 30).joinToString("\n") { "<img src=\"https://cdn.example.com/$it.png\">" }
        assertEquals(listOf("third-party-image"), PublicationScan.hygieneFindings("gallery.html", images, markup = true, script = false).map { it.rule })
        val quiet = "<script src=\"./a.js\"></script><script src=\"/b.js\"></script><link href=\"style.css\" rel=\"stylesheet\">" +
            "<img src=\"bzz://ref/x.png\"><img src=\"ipfs://cid/x.png\"><img src=\"data:,x\"><form action=\"./send\"></form>" +
            "<a href=\"https://example.com/\">a link is not a load</a><link rel=\"alternate\" href=\"https://example.com/feed.xml\">" +
            "<script>fetch(url); fetch(\"api/x\"); window.fetch(\"/y\")</script>"
        assertEquals(emptyList<Any>(), PublicationScan.hygieneFindings("index.html", quiet, markup = true, script = true))
    }

    @Test
    fun `origins are read the way a browser splits an authority`() {
        assertEquals("https://unpkg.com", PublicationScan.thirdPartyOrigin("//unpkg.com/lib@1/x.js"))
        assertEquals("https://a.example", PublicationScan.thirdPartyOrigin("HTTPS://user:pw@A.Example:443/x"))
        assertEquals("http://h.example:8080", PublicationScan.thirdPartyOrigin(" http://h.example:8080\\x"))
        assertNull(PublicationScan.thirdPartyOrigin("/local"))
        assertNull(PublicationScan.thirdPartyOrigin("bzz://abc"))
        assertNull(PublicationScan.thirdPartyOrigin("https://h.example:99999/"))
    }

    @Test
    fun `the model's summary carries counts and rule names, never an excerpt`() {
        val findings = PublicationScan.scanPublication("Text", "Mail alice.smith@proton.me\n${join("ghp_", "a8Fh2kL9mQ3rT6vW1yZ4bC7dE0gJ5n")}".toByteArray(), null)
        val summary = PublicationScan.summary(findings).toString()
        assertTrue(summary.contains("email-address") && summary.contains("github-token"))
        assertFalse(summary.contains("proton") || summary.contains("•"))
    }
}
