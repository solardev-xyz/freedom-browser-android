package baby.freedom.mobile.browser

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Pointer Lock, emulated in the page (#389).
 *
 * WebView has `Element.requestPointerLock`, but no API lets an embedder
 * grant it: `AwWebContentsDelegate` doesn't override `RequestPointerLock`,
 * so Chromium's base delegate denies every request. Games that gate
 * "start" on pointer lock (meinhard.eth's Play button, most WebGL
 * first-person games) see the promise reject / `pointerlockerror` fire,
 * and stay in their menu forever. On a touch screen there is no cursor to
 * capture anyway, and those games drive look/move from their own touch
 * controls once they're in play state.
 *
 * So [SCRIPT], a document-start script in every frame, answers the page
 * the way a browser that granted the lock would, without asking Chromium:
 *
 * - `Element.prototype.requestPointerLock` records the element and, in a
 *   later task, makes it `document.pointerLockElement`, fires
 *   `pointerlockchange` at the document and resolves the returned
 *   promise. An element that isn't connected, or belongs to another
 *   document, gets `pointerlockerror` and a `WrongDocumentError`
 *   rejection instead, as Chromium does.
 * - `Document.prototype.exitPointerLock` releases it and fires
 *   `pointerlockchange`.
 * - The lock is also released, like a real browser's, when the page goes
 *   hidden (`visibilitychange`, which WebView fires when the app is
 *   backgrounded or the tab is switched away), on `pagehide`, on Escape
 *   from a hardware keyboard, and when the element leaves the document
 *   (a `MutationObserver` on the document and on every shadow root
 *   between it and the element, connected only while a lock is held).
 * - `pointerLockElement` on `Document` and `ShadowRoot` returns the locked
 *   element (retargeted to the shadow host outside its shadow tree), and
 *   otherwise whatever Chromium's own getter says.
 *
 * Each replaced function or getter is Chromium's own behind a `Proxy`, so
 * it still reads as native code, still throws Chromium's own TypeError on
 * a foreign receiver, and `'requestPointerLock' in el` still holds.
 * Nothing new appears on `window` (no global, no marker), and everything
 * the shim calls after document start was saved before the page ran, so
 * a page that later wraps `dispatchEvent`, `Promise` or `setTimeout`
 * neither sees the shim call them nor changes its answers. The init
 * dictionaries it hands Blink (`EventInit`, `MutationObserverInit`) have
 * no prototype and spell out every member, so Blink's dictionary
 * conversion never looks one up on a page-patched `Object.prototype`.
 *
 * Not emulated: the cursor itself (WebView has none to hide on a touch
 * screen) — a physical mouse's `movementX/Y` are Chromium's own, which it
 * fills in on every `mousemove` anyway; and the user-activation
 * requirement, since an emulated lock captures nothing a page could abuse;
 * and one lock per page: each frame keeps its own, so the top document and
 * an iframe (same- or cross-origin) can both hold one at once, where
 * Chromium allows a single lock per tab. Coordinating them would need a
 * channel between frames that the page could see.
 */
internal object PointerLock {

    /** Register [SCRIPT] on [webView]; applies to documents created from then on. */
    fun install(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching { WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, setOf("*")) }
    }

    val SCRIPT: String = """
        (() => {
          const w = window, doc = document;
          const El = w.Element, Doc = w.Document, Nd = w.Node, SR = w.ShadowRoot;
          if (!El || !Doc || !Nd) return;
          const own = Object.getOwnPropertyDescriptor, def = Object.defineProperty;
          const req = own(El.prototype, 'requestPointerLock');
          const ple = own(Doc.prototype, 'pointerLockElement');
          const exit = own(Doc.prototype, 'exitPointerLock');
          if (!req || typeof req.value !== 'function' || !ple || typeof ple.get !== 'function' ||
              !exit || typeof exit.value !== 'function') return;
          const sple = SR ? own(SR.prototype, 'pointerLockElement') : undefined;
          const getter = (o, k) => { const d = o && own(o, k); return d && d.get; };
          const apply = Reflect.apply, P = Promise, Px = Proxy, Ev = Event, DE = w.DOMException;
          const later = w.setTimeout, MO = w.MutationObserver;
          const moObserve = MO && MO.prototype.observe, moDisconnect = MO && MO.prototype.disconnect;
          const dispatch = w.EventTarget.prototype.dispatchEvent;
          const listen = w.EventTarget.prototype.addEventListener;
          const tagName = getter(El.prototype, 'tagName');
          const connected = getter(Nd.prototype, 'isConnected');
          const ownerDoc = getter(Nd.prototype, 'ownerDocument');
          const rootOf = Nd.prototype.getRootNode;
          const host = SR ? getter(SR.prototype, 'host') : undefined;
          const visibility = getter(Doc.prototype, 'visibilityState');
          const keyOf = w.KeyboardEvent ? getter(w.KeyboardEvent.prototype, 'key') : undefined;
          if (!tagName || !connected || !ownerDoc || !rootOf || !visibility) return;

          // Blink converts an init dictionary by reading every member it
          // knows, and a plain object literal would send the ones it lacks
          // to Object.prototype, where page getters could watch the shim and
          // change its answers. These have no prototype, and name every member.
          const eventInit = { __proto__: null, bubbles: true, cancelable: false, composed: false };
          const observeInit = {
            __proto__: null, childList: true, subtree: true, attributes: false,
            attributeOldValue: false, characterData: false, characterDataOldValue: false,
          };

          let locked = null, pending = null, watcher = null;
          const fire = (target, type) => apply(dispatch, target, [new Ev(type, eventInit)]);
          const isConnected = (el) => apply(connected, el, []);
          const unwatch = () => { if (watcher) { apply(moDisconnect, watcher, []); watcher = null; } };
          const release = () => {
            pending = null;
            if (!locked) return;
            locked = null;
            unwatch();
            apply(later, w, [() => fire(doc, 'pointerlockchange'), 0]);
          };
          // Observe every tree between the locked element and the document:
          // its own (shadow) root, the root holding that root's host, and so
          // on up to the document, since a document observer doesn't see
          // into shadow trees. Re-done after each batch of mutations, as a
          // host moved into another shadow tree changes the chain.
          const observeChain = () => {
            let n = locked;
            for (;;) {
              const root = apply(rootOf, n, []);
              apply(moObserve, watcher, [root, observeInit]);
              if (root === doc) return;
              let next = null;
              try { next = host ? apply(host, root, []) : null; } catch (e) { return; }
              if (!next) return;
              n = next;
            }
          };
          const watch = () => {
            if (!MO) return;
            unwatch();
            watcher = new MO(() => {
              if (!locked) return;
              if (!isConnected(locked)) { release(); return; }
              apply(moDisconnect, watcher, []);
              observeChain();
            });
            observeChain();
          };
          // The locked element as seen from [scope]: itself in its own tree,
          // the shadow host that contains it from outside, null elsewhere.
          const retarget = (scope) => {
            let n = locked;
            for (;;) {
              const root = apply(rootOf, n, []);
              if (root === scope) return n;
              let next = null;
              try { next = host ? apply(host, root, []) : null; } catch (e) { return null; }
              if (!next) return null;
              n = next;
            }
          };

          const requestPointerLock = new Px(req.value, {
            apply(target, self, args) {
              // A foreign receiver gets Chromium's own TypeError, no side effects.
              try { apply(tagName, self, []); } catch (e) { return apply(target, self, args); }
              const ticket = {};
              pending = ticket;
              return new P((resolve, reject) => {
                apply(later, w, [() => {
                  const owner = apply(ownerDoc, self, []);
                  if (owner !== doc || !isConnected(self)) {
                    if (pending === ticket) pending = null;
                    fire(owner, 'pointerlockerror');
                    reject(new DE('The root document of this element is not valid for pointer lock.', 'WrongDocumentError'));
                    return;
                  }
                  if (pending !== ticket) {
                    reject(new DE('The user has exited the lock before this request was completed.', 'AbortError'));
                    return;
                  }
                  pending = null;
                  if (locked !== self) {
                    locked = self;
                    watch();
                    fire(doc, 'pointerlockchange');
                  }
                  resolve(undefined);
                }, 0]);
              });
            },
          });
          def(El.prototype, 'requestPointerLock', { value: requestPointerLock, writable: req.writable, enumerable: req.enumerable, configurable: req.configurable });

          const exitPointerLock = new Px(exit.value, {
            apply(target, self, args) {
              if (self !== doc) return apply(target, self, args);
              release();
              return undefined;
            },
          });
          def(Doc.prototype, 'exitPointerLock', { value: exitPointerLock, writable: exit.writable, enumerable: exit.enumerable, configurable: exit.configurable });

          const lockedGetter = (d) => new Px(d.get, {
            apply(target, self, args) {
              const native = apply(target, self, args);
              if (!locked) return native;
              if (!isConnected(locked)) { release(); return native; }
              return retarget(self) || native;
            },
          });
          def(Doc.prototype, 'pointerLockElement', { get: lockedGetter(ple), set: ple.set, enumerable: ple.enumerable, configurable: ple.configurable });
          if (sple && typeof sple.get === 'function') {
            def(SR.prototype, 'pointerLockElement', { get: lockedGetter(sple), set: sple.set, enumerable: sple.enumerable, configurable: sple.configurable });
          }

          apply(listen, doc, ['visibilitychange', () => {
            if (apply(visibility, doc, []) === 'hidden') release();
          }, true]);
          apply(listen, w, ['pagehide', release, true]);
          if (keyOf) apply(listen, w, ['keydown', (e) => {
            if (locked && apply(keyOf, e, []) === 'Escape') release();
          }, true]);
        })();
    """.trimIndent()
}
