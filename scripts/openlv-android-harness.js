#!/usr/bin/env node
// Desktop-host stand-in for the phone's OpenLV signing endpoint (#113),
// after freedom-browser's scripts/openlv-ios-harness.js: a local MQTT
// broker for signaling plus a headless Chromium page running desktop's
// vendored openlv bundle (the same file as app/src/main/assets/openlv/
// openlv.esm.js) in the host role, sending the request sequences a
// desktop job sends. Signatures are verified here with ethers, as
// desktop's remote signer does.
//
// Setup (outside the repo):  npm i aedes@1 ws@8 playwright ethers@6
// Run:   node scripts/openlv-android-harness.js   (CHROME=<chromium binary> optional)
//        adb reverse tcp:8720 tcp:8720            (the broker, from the emulator)
// Jobs:  curl 'http://127.0.0.1:8721/start?mode=connect'
//        curl 'http://127.0.0.1:8721/start?mode=sign&account=0x…'     (personal_sign)
//        curl 'http://127.0.0.1:8721/start?mode=typed&account=0x…'    (eth_signTypedData_v4)
//        curl 'http://127.0.0.1:8721/start?mode=tx&account=0x…&tx=<url-encoded JSON>'
//          (wallet_switchEthereumChain 0x64, then eth_sendTransaction — real funds on Gnosis)
// Then paste /state's `uri` on the phone's Scan page (or pass it to
// OpenLvHarnessTest), and read the answers from /state.
const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');
const { Aedes } = require('aedes');
const { WebSocketServer, createWebSocketStream } = require('ws');
const { verifyMessage, verifyTypedData, TypedDataEncoder, toUtf8Bytes } = require('ethers');

const CONTROL_PORT = Number(process.env.CONTROL_PORT || 8721);
const BROKER_PORT = Number(process.env.BROKER_PORT || 8720);
const SIGNALING = process.env.SIGNALING || `ws://127.0.0.1:${BROKER_PORT}/mqtt`;
const MESSAGE = 'Freedom desktop asks the phone to sign this.\nNonce: 42';
const TYPED = {
  domain: { name: 'Freedom harness', version: '1', chainId: 100, verifyingContract: '0x9A676e781A523b5d0C0e43731313A708CB607508' },
  types: {
    Person: [{ name: 'name', type: 'string' }, { name: 'wallet', type: 'address' }],
    Mail: [{ name: 'from', type: 'Person' }, { name: 'to', type: 'Person' }, { name: 'contents', type: 'string' }, { name: 'amounts', type: 'uint256[]' }],
  },
  message: {
    from: { name: 'Desktop', wallet: '0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826' },
    to: { name: 'Phone', wallet: '0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB' },
    contents: 'Hello from desktop Freedom',
    amounts: ['1', '115792089237316195423570985008687907853269984665640564039457584007913129639935'],
  },
};

function hostPage(mode, account, extra) {
  const typedPayload = TypedDataEncoder.getPayload(TYPED.domain, TYPED.types, TYPED.message);
  return `<!doctype html><html><body><script type="module">
import { createSession, encodeConnectionURL, webrtc } from '/openlv.esm.js';
const report = (u) => window.__report(JSON.stringify(u));
const toHex = (t) => '0x' + Array.from(new TextEncoder().encode(t), (b) => b.toString(16).padStart(2, '0')).join('');
try {
  const session = await createSession({ p: 'mqtt', s: ${JSON.stringify(SIGNALING)} }, [webrtc()],
    async () => ({ error: { code: -32601, message: 'Method not found' } }));
  report({ phase: 'qr', uri: encodeConnectionURL(session.getHandshakeParameters()) });
  session.status.subscribe((s) => report({ phase: s }));
  await session.connect();
  const linked = await session.status.until((s) => s === 'connected' || s === 'disconnected');
  if (linked !== 'connected') throw new Error(session.error.get() || 'Session failed to connect');
  report({ phase: 'linked' });
  const exchange = async (method, params) => {
    const response = await session.send({ method, params }, undefined, 300000);
    report({ exchange: { method, params, response } });
    return response;
  };
  const mode = ${JSON.stringify(mode)};
  const account = ${JSON.stringify(account)};
  if (mode === 'connect') {
    await exchange('eth_requestAccounts', []);
  } else if (mode === 'sign') {
    await exchange('personal_sign', [toHex(${JSON.stringify(MESSAGE)}), account]);
  } else if (mode === 'typed') {
    await exchange('eth_signTypedData_v4', [account, ${JSON.stringify(JSON.stringify(typedPayload))}]);
  } else if (mode === 'tx') {
    // desktop's remote backend: switch the phone to the tx's chain, then eth_sendTransaction with intent fields only.
    const sw = await exchange('wallet_switchEthereumChain', [{ chainId: '0x64' }]);
    if (!sw.error) await exchange('eth_sendTransaction', [${JSON.stringify(extra)}]);
  }
  report({ phase: 'done' });
  await session.close();
} catch (err) {
  report({ phase: 'error', error: err?.message || String(err) });
}
</script></body></html>`;
}

async function main() {
  const aedes = await Aedes.createBroker();
  const brokerServer = http.createServer();
  const wss = new WebSocketServer({ server: brokerServer });
  wss.on('connection', (socket) => aedes.handle(createWebSocketStream(socket)));
  await new Promise((r) => brokerServer.listen(BROKER_PORT, '127.0.0.1', r));

  let state = { phase: 'idle', exchanges: [] };
  let page = null;
  let current = { mode: 'connect', account: null, extra: null };
  const esm = fs.readFileSync(path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'openlv', 'openlv.esm.js'));

  const verify = (ex) => {
    const r = ex.response || {};
    if (r.error || typeof r.result !== 'string') return null;
    try {
      if (ex.method === 'personal_sign') return verifyMessage(toUtf8Bytes(MESSAGE), r.result);
      if (ex.method === 'eth_signTypedData_v4') return verifyTypedData(TYPED.domain, TYPED.types, TYPED.message, r.result);
    } catch (e) { return 'verify error: ' + e.message; }
    return null;
  };

  const server = http.createServer((req, res) => {
    const url = new URL(req.url, `http://127.0.0.1:${CONTROL_PORT}`);
    const json = (o) => { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(o, null, 1)); };
    switch (url.pathname) {
      case '/state':
        return json({ ...state, exchanges: state.exchanges.map((e) => ({ ...e, recovered: verify(e) })) });
      case '/start': {
        current = {
          mode: url.searchParams.get('mode') || 'connect',
          account: url.searchParams.get('account'),
          extra: url.searchParams.get('tx') ? JSON.parse(url.searchParams.get('tx')) : null,
        };
        state = { phase: 'starting', exchanges: [] };
        page.goto(`http://127.0.0.1:${CONTROL_PORT}/host?n=${Date.now()}`).then(() => json({ ok: true })).catch((e) => { res.writeHead(500); res.end(String(e)); });
        return undefined;
      }
      case '/host':
        res.writeHead(200, { 'content-type': 'text/html' });
        return res.end(hostPage(current.mode, current.account, current.extra));
      case '/openlv.esm.js':
        res.writeHead(200, { 'content-type': 'text/javascript' });
        return res.end(esm);
      default:
        res.writeHead(404); return res.end();
    }
  });
  await new Promise((r) => server.listen(CONTROL_PORT, '127.0.0.1', r));
  const browser = await chromium.launch({ executablePath: process.env.CHROME, args: ['--disable-features=WebRtcHideLocalIpsWithMdns'] });
  page = await browser.newPage();
  page.on('console', (m) => console.log('[host page]', m.text()));
  await page.exposeFunction('__report', (j) => {
    const u = JSON.parse(j);
    if (u.exchange) state.exchanges.push(u.exchange); else Object.assign(state, u);
    console.log('[harness]', u.exchange ? `${u.exchange.method} answered` : j.slice(0, 200));
  });
  await page.goto('about:blank');
  console.log(`[harness] control http://127.0.0.1:${CONTROL_PORT}, signaling ${SIGNALING}`);
}
main().catch((e) => { console.error(e); process.exit(1); });
