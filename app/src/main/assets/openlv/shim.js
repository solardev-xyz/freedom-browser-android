/**
 * JS half of WebViewOpenLvEngine (#113): joins the openlv session in a
 * pairing code from desktop Freedom (the wallet/client role — desktop
 * hosts it) and hands the browser's JSON-RPC requests to the app, which
 * answers each one only after the user approved it on its own sheet.
 *
 * iOS's OpenLVShim.js on the openlv 0.2.0 API desktop Freedom uses
 * (status is an observable; createSession loads the signaling backend
 * itself), talking to the app over the `freedomOpenLV` WebMessageListener
 * channel instead of webkit.messageHandlers:
 *
 *  - page → app: {type: 'ready'}
 *                {type: 'status', sid, status: 'connecting' | 'connected' | 'disconnected' | 'failed', message?}
 *                {type: 'request', sid, id, method, params}
 *  - app → page: {type: 'start', sid, uri, max} | {type: 'stop'}
 *                {type: 'response', sid, id, result} | {type: 'response', sid, id, error: {code, message}}
 *
 * `sid` is the app's number for the session a message belongs to, so
 * nothing from a session it has already replaced is taken for the new one.
 */
import { createSession, decodeConnectionURL, webrtc } from './openlv.esm.js';

const channel = window.freedomOpenLV;

// Desktop's own list (its bridge page's): refused here at the transport
// edge, whatever the app would say, so a pairing code can't turn this
// page into a generic RPC proxy.
const ALLOWED_METHODS = new Set([
  'eth_requestAccounts',
  'eth_accounts',
  'eth_chainId',
  'personal_sign',
  'eth_signTypedData_v4',
  'eth_sendTransaction',
  'wallet_switchEthereumChain',
  'wallet_addEthereumChain',
]);

const post = (message) => channel.postMessage(JSON.stringify(message));

let current = null; // {sid, session, pending: Map<id, resolve>, max}
let nextId = 1;

function requestHandler(state) {
  return (payload) => {
    const { method, params } = payload || {};
    if (!ALLOWED_METHODS.has(method)) {
      return Promise.resolve({
        error: { code: -32601, message: 'Method not supported by this wallet' },
      });
    }
    const id = nextId++;
    let text;
    try {
      text = JSON.stringify({ type: 'request', sid: state.sid, id, method, params: Array.isArray(params) ? params : [] });
    } catch {
      text = null; // a cycle or a BigInt: not JSON, so nothing the app could read
    }
    // Over the app's limit (`max`, in UTF-16 units as both sides count
    // them) the app would drop it unread; answer here instead of hanging.
    if (text == null || text.length > state.max) {
      const message = text == null ? 'The request isn’t valid JSON.' : 'The request is too large for the phone.';
      return Promise.resolve({ error: { code: -32600, message } });
    }
    return new Promise((resolve) => {
      state.pending.set(id, resolve);
      channel.postMessage(text);
    });
  };
}

// Why a pairing code's signaling server can't be used, or null if it can:
// wss:, or cleartext ws: only to the phone itself (as the page's CSP).
function signalingRefusal(url) {
  let u;
  try {
    u = new URL(url);
  } catch {
    return 'That pairing code’s server address can’t be read. Scan the code on the computer again.';
  }
  if (u.protocol === 'wss:') return null;
  if (u.protocol === 'ws:') {
    if (u.hostname === '127.0.0.1' || u.hostname === 'localhost') return null;
    return 'That pairing code points at an unencrypted server. Scan the code on the computer again.';
  }
  // Scheme only: the rest of the URL can carry credentials.
  return `That pairing code’s server uses ${u.protocol}, but the phone connects over secure WebSockets (wss:) only. Scan the code on the computer again.`;
}

function stop() {
  const state = current;
  current = null;
  if (!state) return;
  for (const resolve of state.pending.values()) {
    resolve({ error: { code: 4900, message: 'The phone closed the session' } });
  }
  state.pending.clear();
  state.unsubscribe?.();
  if (state.session) Promise.resolve(state.session.close()).catch(() => {});
}

async function start(sid, uri, max) {
  stop();
  const state = { sid, session: null, pending: new Map(), unsubscribe: null, max };
  current = state;
  const report = (status, message) => {
    if (current === state) post({ type: 'status', sid, status, message });
  };
  try {
    let params;
    try {
      params = decodeConnectionURL(uri);
    } catch {
      // The SDK's message quotes the whole code, session key included: not for the screen.
      throw new Error('That pairing code can’t be read. Scan the code on the computer again.');
    }
    if (params.p !== 'mqtt') throw new Error(`Unsupported signaling protocol "${params.p}"`);
    const refusal = params.s != null ? signalingRefusal(String(params.s)) : null;
    if (refusal) throw new Error(refusal);
    report('connecting');
    const session = await createSession(params, [webrtc()], requestHandler(state));
    if (current !== state) {
      Promise.resolve(session.close()).catch(() => {});
      return;
    }
    state.session = session;
    // subscribe() replays the current value, so no state slips past.
    let linked = false;
    state.unsubscribe = session.status.subscribe((status) => {
      if (status === 'connected') {
        linked = true;
        report('connected');
      } else if (status === 'disconnected') {
        // Desktop closes the session once its job is answered, and the SDK
        // reports that as an error ("Data channel closed"): after a link
        // it's the normal end, only before one is it a failure.
        const error = session.error.get();
        if (error && !linked) report('failed', String(error));
        else report('disconnected');
      } else if (status) {
        report('connecting');
      }
    });
    await session.connect();
  } catch (err) {
    report('failed', String(err?.message || err));
  }
}

channel.addEventListener('message', (event) => {
  let message;
  try {
    message = JSON.parse(event.data);
  } catch {
    return;
  }
  switch (message?.type) {
    case 'start':
      start(message.sid, String(message.uri), Number.isFinite(message.max) ? message.max : 0);
      break;
    case 'stop':
      stop();
      break;
    case 'response': {
      const state = current;
      if (!state || state.sid !== message.sid) return;
      const resolve = state.pending.get(message.id);
      if (!resolve) return;
      state.pending.delete(message.id);
      resolve(message.error ? { error: message.error } : { result: message.result ?? null });
      break;
    }
    default:
      break;
  }
});

post({ type: 'ready' });
