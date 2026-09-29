package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

/**
 * [ethereumProviderJs] (#110) runs for real (Rhino) against a fake window:
 * the channel object the platform puts on `window`, timers, and
 * `dispatchEvent` for EIP-6963. The test plays the native side by hand —
 * reading what the page sent and posting answers and events back.
 */
class EthereumProviderScriptTest {
    private val channel = "qwertyuiopasdfgh"

    private val fakeWindow = """
        var window = this;
        var sent = [], portListener = null, winListeners = {}, log = [];
        var location = { protocol: 'https:' };
        var top = window;
        window['$channel'] = {
          postMessage: function (s) { sent.push(JSON.parse(s)); },
          addEventListener: function (t, f) { portListener = f; }
        };
        function setTimeout(f, ms) { return 1; }
        function clearTimeout(id) {}
        var crypto = { randomUUID: function () { return '6f1d0e8c-1111-4222-8333-444455556666'; } };
        function CustomEvent(type, init) { this.type = type; this.detail = init && init.detail; }
        function Event(type) { this.type = type; }
        function addEventListener(t, f) { (winListeners[t] = winListeners[t] || []).push(f); }
        function dispatchEvent(e) { log.push(e.type); (winListeners[e.type] || []).forEach(function (f) { f(e); }); return true; }
        function nativeSays(obj) { portListener({ data: JSON.stringify(obj) }); }
    """.trimIndent()

    private fun run(vararg scripts: String): Pair<Context, Scriptable> {
        val cx = Context.enter()
        cx.languageVersion = Context.VERSION_ES6
        val scope = cx.initStandardObjects()
        cx.evaluateString(scope, fakeWindow, "fake.js", 1, null)
        cx.evaluateString(scope, ethereumProviderJs(channel, "data:image/png;base64,iVBORw0KGgo="), "ethereum.js", 1, null)
        scripts.forEach { cx.evaluateString(scope, it, "test.js", 1, null) }
        return cx to scope
    }

    @Test
    fun `the channel is taken off window and window ethereum is there, announced over EIP-6963`() {
        val (cx, scope) = run()
        try {
            assertEquals("undefined", Context.toString(cx.evaluateString(scope, "typeof window['$channel']", "t", 1, null)))
            assertEquals("true", Context.toString(cx.evaluateString(scope, "window.ethereum.isMetaMask && window.ethereum.isFreedomBrowser", "t", 1, null)))
            assertEquals("eip6963:announceProvider,ethereum#initialized", Context.toString(cx.evaluateString(scope, "log.join()", "t", 1, null)))
            // A dapp asking later gets the same provider, with the same details.
            val announced = """
                var got = [];
                addEventListener('eip6963:announceProvider', function (e) { got.push(e.detail); });
                dispatchEvent(new Event('eip6963:requestProvider'));
                [got.length, got[0].info.rdns, got[0].info.name, got[0].info.uuid, got[0].info.icon, got[0].provider === window.ethereum,
                 Object.isFrozen(got[0].info)].join('|')
            """.trimIndent()
            assertEquals(
                "1|baby.freedom.browser|Freedom Browser|6f1d0e8c-1111-4222-8333-444455556666|data:image/png;base64,iVBORw0KGgo=|true|true",
                Context.toString(cx.evaluateString(scope, announced, "t", 1, null)),
            )
        } finally {
            Context.exit()
        }
    }

    @Test
    fun `a request goes down the channel and its answer resolves it, tracking chain and accounts`() {
        val (cx, scope) = run()
        try {
            cx.evaluateString(scope, "var out = []; window.ethereum.request({ method: 'eth_chainId' }).then(function (r) { out.push(r); });", "t", 1, null)
            assertEquals("1|eth_chainId|0", Context.toString(cx.evaluateString(scope, "[sent[0].id, sent[0].method, sent[0].params.length].join('|')", "t", 1, null)))
            cx.evaluateString(scope, "nativeSays({ id: 1, result: '0x64' });", "t", 1, null)
            cx.processMicrotasks()
            assertEquals("0x64|0x64|100", Context.toString(cx.evaluateString(scope, "[out[0], ethereum.chainId, ethereum.networkVersion].join('|')", "t", 1, null)))

            cx.evaluateString(
                scope,
                "var err; window.ethereum.request({ method: 'personal_sign', params: ['hi', '0xabc'] }).catch(function (e) { err = e; });",
                "t", 1, null,
            )
            cx.evaluateString(scope, "nativeSays({ id: 2, error: { code: 4001, message: 'User rejected the request.' } });", "t", 1, null)
            cx.processMicrotasks()
            assertEquals("4001|User rejected the request.", Context.toString(cx.evaluateString(scope, "err.code + '|' + err.message", "t", 1, null)))

            cx.evaluateString(scope, "window.ethereum.request({ method: 'eth_requestAccounts' });", "t", 1, null)
            cx.evaluateString(scope, "nativeSays({ id: 3, result: ['0xAbC'] });", "t", 1, null)
            cx.processMicrotasks()
            assertEquals("0xAbC", Context.toString(cx.evaluateString(scope, "ethereum.selectedAddress", "t", 1, null)))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun `events reach listeners and update the provider's state`() {
        val (cx, scope) = run()
        try {
            cx.evaluateString(
                scope,
                """
                var seen = [];
                function onChain(c) { seen.push('chain:' + c); }
                ethereum.on('chainChanged', onChain);
                ethereum.once('accountsChanged', function (a) { seen.push('accounts:' + a.length); });
                nativeSays({ event: 'chainChanged', data: '0x2105' });
                nativeSays({ event: 'accountsChanged', data: [] });
                nativeSays({ event: 'accountsChanged', data: ['0x1'] });
                ethereum.removeListener('chainChanged', onChain);
                nativeSays({ event: 'chainChanged', data: '0x1' });
                """.trimIndent(),
                "t", 1, null,
            )
            assertEquals("chain:0x2105,accounts:0", Context.toString(cx.evaluateString(scope, "seen.join()", "t", 1, null)))
            assertEquals("0x1|0x1", Context.toString(cx.evaluateString(scope, "ethereum.chainId + '|' + ethereum.selectedAddress", "t", 1, null)))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun `legacy send and sendAsync still work`() {
        val (cx, scope) = run()
        try {
            cx.evaluateString(
                scope,
                """
                var res;
                ethereum.sendAsync({ id: 9, method: 'eth_accounts', params: [] }, function (e, r) { res = r; });
                nativeSays({ id: 1, result: [] });
                """.trimIndent(),
                "t", 1, null,
            )
            cx.processMicrotasks()
            assertEquals("9|2.0|0", Context.toString(cx.evaluateString(scope, "[res.id, res.jsonrpc, res.result.length].join('|')", "t", 1, null)))
            cx.evaluateString(scope, "ethereum.send('net_version');", "t", 1, null)
            assertEquals("net_version", Context.toString(cx.evaluateString(scope, "sent[1].method", "t", 1, null)))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun `frames and non-http documents get no provider`() {
        for (setup in listOf("top = {};", "location = { protocol: 'file:' };")) {
            val cx = Context.enter()
            try {
                cx.languageVersion = Context.VERSION_ES6
                val scope = cx.initStandardObjects()
                cx.evaluateString(scope, fakeWindow + "\n" + setup, "fake.js", 1, null)
                cx.evaluateString(scope, ethereumProviderJs(channel, ""), "ethereum.js", 1, null)
                assertEquals(setup, "undefined", Context.toString(cx.evaluateString(scope, "typeof window.ethereum", "t", 1, null)))
                // …and the channel is still taken off window.
                assertEquals("undefined", Context.toString(cx.evaluateString(scope, "typeof window['$channel']", "t", 1, null)))
            } finally {
                Context.exit()
            }
        }
    }

    @Test
    fun `the script refuses a channel or icon that could break out of its string`() {
        assertTrue(runCatching { ethereumProviderJs("a'b", "") }.isFailure)
        assertTrue(runCatching { ethereumProviderJs(channel, "data:image/png;base64,x'+alert(1)+'") }.isFailure)
    }
}
