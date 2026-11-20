/*
 * Freedom Agent's page script (#575): desktop's DOM walk
 * (`collectPageSnapshot` and the reference helpers in
 * web-contents-page-adapter.js), run in the page's own world.
 *
 * WebView has no isolated world, so unlike desktop this script keeps no
 * state on the page: no global, no attribute, nothing the page could
 * read back to tell it ran. A reference is the position of a control in
 * the walk (with the same query) plus what it was (role, tag, name); an
 * action walks again, takes the control at that position and refuses it
 * unless it is still the same. Everything it returns is page-controlled
 * and treated as untrusted by the caller.
 *
 * Called as (op, args); returns a plain JSON value, synchronously.
 */
(function (op, a) {
  'use strict';
  var MAX_SCAN = 20000;
  var normalize = function (value) {
    return String(value == null ? '' : value).replace(/\s+/g, ' ').trim();
  };

  function readElementName(element) {
    var labelledBy = element.getAttribute('aria-labelledby');
    if (labelledBy) {
      var root = element.getRootNode();
      var byId = labelledBy.split(/\s+/).map(function (id) {
        var el = root.getElementById ? root.getElementById(id) : null;
        return el ? el.textContent || '' : '';
      }).join(' ');
      var name = normalize(byId);
      if (name) return name;
    }
    var ariaLabel = normalize(element.getAttribute('aria-label'));
    if (ariaLabel) return ariaLabel;
    var labels = Array.prototype.map.call(element.labels || [], function (label) {
      var parts = [];
      var walker = label.ownerDocument.createTreeWalker(label, NodeFilter.SHOW_TEXT);
      for (var node = walker.nextNode(); node; node = walker.nextNode()) {
        if (!element.contains(node)) parts.push(node.textContent || '');
      }
      return parts.join('');
    }).join(' ');
    var associated = normalize(labels);
    if (associated) return associated;
    return normalize(
      element.getAttribute('alt') ||
      element.getAttribute('title') ||
      element.getAttribute('placeholder') ||
      element.innerText ||
      (element.tagName === 'INPUT' && ['button', 'submit', 'reset'].indexOf(element.type) >= 0 ? element.value : '')
    );
  }

  function readScrollState(element) {
    var view = element.ownerDocument.defaultView;
    var style = view.getComputedStyle(element);
    var isViewport = element === element.ownerDocument.scrollingElement;
    var permits = function (overflow) {
      return isViewport ? ['hidden', 'clip'].indexOf(overflow) < 0 : ['auto', 'scroll'].indexOf(overflow) >= 0;
    };
    var rangeX = Math.max(0, element.scrollWidth - element.clientWidth);
    var rangeY = Math.max(0, element.scrollHeight - element.clientHeight);
    var rtl = style.direction === 'rtl';
    return {
      x: Math.round(element.scrollLeft),
      y: Math.round(element.scrollTop),
      width: isViewport ? view.innerWidth : element.clientWidth,
      height: isViewport ? view.innerHeight : element.clientHeight,
      minX: rtl ? -rangeX : 0,
      maxX: rtl ? 0 : rangeX,
      maxY: rangeY,
      horizontal: permits(style.overflowX) && rangeX > 1,
      vertical: permits(style.overflowY) && rangeY > 1
    };
  }

  function readControlState(element, role) {
    var checkedRoles = ['checkbox', 'radio', 'switch', 'menuitemcheckbox', 'menuitemradio', 'option', 'treeitem'];
    var selectedRoles = ['option', 'tab', 'row', 'gridcell', 'columnheader', 'rowheader', 'treeitem'];
    var expandedRoles = ['application', 'button', 'checkbox', 'combobox', 'gridcell', 'link', 'listbox', 'menuitem',
      'row', 'rowheader', 'columnheader', 'tab', 'treeitem', 'menuitemcheckbox', 'menuitemradio', 'switch'];
    var result = {};
    var ariaState = function (attribute, mixed) {
      var value = (element.getAttribute(attribute) || '').trim().toLowerCase();
      if (value === 'true' || value === 'false') return value === 'true';
      if (mixed && value === 'mixed') return 'mixed';
      return undefined;
    };
    var add = function (key, value) { if (value !== undefined) result[key] = value; };
    if (element.tagName === 'INPUT' && (element.type === 'checkbox' || element.type === 'radio')) {
      result.checked = element.type === 'checkbox' && element.indeterminate ? 'mixed' : element.checked;
    } else if (checkedRoles.indexOf(role) >= 0) {
      var checked = ariaState('aria-checked', true);
      add('checked', checked === 'mixed' && ['radio', 'menuitemradio', 'switch'].indexOf(role) >= 0 ? false : checked);
    }
    if (['INPUT', 'SELECT', 'TEXTAREA'].indexOf(element.tagName) >= 0) {
      result.required = element.required === true;
      result.readOnly = element.readOnly === true;
      if (element.willValidate && element.validity) {
        result.valid = element.validity.valid;
        result.validation = ['valueMissing', 'typeMismatch', 'patternMismatch', 'tooLong', 'tooShort',
          'rangeUnderflow', 'rangeOverflow', 'stepMismatch', 'badInput', 'customError']
          .filter(function (key) { return element.validity[key]; });
      }
    }
    if (role === 'button') add('pressed', ariaState('aria-pressed', true));
    if (selectedRoles.indexOf(role) >= 0) add('selected', ariaState('aria-selected'));
    if (expandedRoles.indexOf(role) >= 0) add('expanded', ariaState('aria-expanded'));
    return result;
  }

  var styleFor = function (element) { return element.ownerDocument.defaultView.getComputedStyle(element); };
  var visible = function (element) {
    var style = styleFor(element);
    if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false;
    return element.getClientRects().length > 0;
  };
  var implicitRole = function (element) {
    var tag = element.tagName.toLowerCase();
    if (tag === 'a' && element.hasAttribute('href')) return 'link';
    if (tag === 'button') return 'button';
    if (tag === 'select') return element.multiple || element.size > 1 ? 'listbox' : 'combobox';
    if (tag === 'textarea') return 'textbox';
    if (tag === 'dialog') return 'dialog';
    if (tag === 'input') {
      if (element.type === 'file') return 'button';
      if (['button', 'submit', 'reset'].indexOf(element.type) >= 0) return 'button';
      if (element.type === 'checkbox') return 'checkbox';
      if (element.type === 'radio') return 'radio';
      if (element.type === 'range') return 'slider';
      return 'textbox';
    }
    return element.isContentEditable ? 'textbox' : 'generic';
  };
  var SEMANTIC = 'a[href],button,input:not([type="hidden"]),select,textarea,dialog[open],[role],[contenteditable="true"],[tabindex]:not([tabindex="-1"])';
  var isPointerBoundary = function (element) {
    if (element.tagName === 'HTML' || element.tagName === 'BODY') return false;
    if (styleFor(element).cursor !== 'pointer') return false;
    var parent = element.parentElement;
    return !parent || styleFor(parent).cursor !== 'pointer';
  };
  var roleOf = function (element, inferred, scrollable, semantic) {
    return element.getAttribute('role') || (inferred ? 'button' : scrollable && !semantic ? 'region' : implicitRole(element));
  };

  /*
   * The walk shared by the snapshot and every action: each candidate
   * control, in document order (shadow roots after their host's tree,
   * same-origin frames after their parent document), filtered by the
   * query. [visit] gets (element, frameWindow, frameId, info) and returns
   * true to stop.
   */
  function walk(search, visit, onFrame, onDocument) {
    var visited = 0;
    var frameCount = 0;
    var truncated = false;
    var stopped = false;
    var visitDocument = function (frameWindow, parentFrameId, depth, frameElement) {
      if (stopped) return;
      if (depth > 16 || frameCount >= 64 || visited >= MAX_SCAN) { truncated = true; return; }
      var frameId = depth === 0 ? 'frame_main' : 'frame_' + frameCount;
      frameCount += 1;
      var frameDocument;
      try {
        frameDocument = frameWindow.document;
        void frameDocument.documentElement;
      } catch (e) {
        if (onFrame) onFrame({ frameId: frameId, parentFrameId: parentFrameId, depth: depth, accessible: false,
          name: normalize(frameElement && frameElement.getAttribute('name')).slice(0, 200) });
        return;
      }
      if (onFrame) onFrame({ frameId: frameId, parentFrameId: parentFrameId, depth: depth, accessible: true,
        name: normalize(frameElement && frameElement.getAttribute('name')).slice(0, 200),
        url: String(frameWindow.location.href).slice(0, 2048) });
      if (onDocument) onDocument(frameDocument, frameId);
      var childFrames = [];
      var visitRoot = function (root, shadowDepth) {
        if (shadowDepth > 16) { truncated = true; return; }
        var shadowRoots = [];
        var walker = frameDocument.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
        for (var element = walker.nextNode(); element && !stopped; element = walker.nextNode()) {
          if (visited >= MAX_SCAN) { truncated = true; break; }
          visited += 1;
          if (element.shadowRoot) shadowRoots.push(element.shadowRoot);
          if (element.matches('iframe,frame')) childFrames.push(element);
          if (element === frameDocument.scrollingElement) continue;
          var scroll = readScrollState(element);
          var scrollable = scroll.horizontal || scroll.vertical;
          var semantic = element.matches(SEMANTIC);
          var inferred = !semantic && (element.hasAttribute('onclick') || typeof element.onclick === 'function' || isPointerBoundary(element));
          if (!semantic && !inferred && !scrollable) continue;
          if (!visible(element)) continue;
          var name = readElementName(element);
          if (inferred && !name && !scrollable) continue;
          if (search && name.toLowerCase().indexOf(search) < 0) continue;
          var role = roleOf(element, inferred, scrollable, semantic);
          if (visit(element, frameWindow, frameId, { name: name, role: role, inferred: inferred, scroll: scroll,
            scrollable: scrollable, semantic: semantic })) { stopped = true; return; }
        }
        for (var i = 0; i < shadowRoots.length && !stopped; i++) {
          if (visited >= MAX_SCAN) { truncated = true; break; }
          visitRoot(shadowRoots[i], shadowDepth + 1);
        }
      };
      visitRoot(frameDocument, 0);
      for (var j = 0; j < childFrames.length && !stopped; j++) {
        var child = childFrames[j].contentWindow;
        if (child) visitDocument(child, frameId, depth + 1, childFrames[j]);
      }
    };
    visitDocument(window, null, 0, null);
    return { truncated: truncated };
  }

  /*
   * What a reference must still be when it's used: the control's tag
   * and name, its id, name and type attributes, where a link goes,
   * and the start of the text of the row, list item or card around it, so that one of several same-named controls ("Delete" in
   * each row) isn't taken for another once the list shifts ([find]).
   */
  var fingerprintOf = function (element, info) {
    // Rows and cards only: a form's or section's text changes as the user
    // types (a search box's suggestions), a row's around its button doesn't.
    var around = element.parentElement && element.parentElement.closest('li,tr,article,[role="row"],[role="listitem"],[role="article"]');
    // Not the ARIA role: pages set it from script once they're shown
    // (a search box becomes a combobox), and a held tab is hidden until
    // the approved action brings it on screen.
    return JSON.stringify([
      element.tagName.toLowerCase().slice(0, 128),
      info.name.slice(0, 300),
      String(element.getAttribute('id') || '').slice(0, 200),
      String(element.getAttribute('name') || '').slice(0, 200),
      String(element.getAttribute('type') || '').slice(0, 64),
      element.tagName === 'A' ? String(element.href || '').slice(0, 1000) : '',
      // textContent, not innerText: the same whether or not the tab is on screen.
      around ? normalize(around.textContent).slice(0, 120) : ''
    ]);
  };

  /*
   * The control a reference names: the one at [index] in the walk for
   * [query] if it's still the same control, else the only control on the
   * page that is (one shown or hidden since moved it along). Two or more
   * that match, and none at its place: refused, never a guess.
   */
  function find(index, query, want) {
    var count = -1;
    var atIndex = null;
    var matches = [];
    walk(normalize(query).toLowerCase(), function (element, frameWindow, frameId, info) {
      count += 1;
      if (fingerprintOf(element, info) !== want) return false;
      var hit = { element: element, frameWindow: frameWindow, info: info };
      if (count === index) { atIndex = hit; return true; }
      matches.push(hit);
      return false;
    });
    if (atIndex) return atIndex;
    if (matches.length === 1) return matches[0];
    return { error: 'changed' };
  }

  var composedActive = function (doc) {
    var active = doc.activeElement;
    while (active && active.shadowRoot && active.shadowRoot.activeElement) active = active.shadowRoot.activeElement;
    return active;
  };
  var deepHit = function (root, x, y) {
    var hit = root.elementFromPoint(x, y);
    while (hit && hit.shadowRoot) {
      var nested = hit.shadowRoot.elementFromPoint(x, y);
      if (!nested || nested === hit) break;
      hit = nested;
    }
    return hit;
  };
  var parentOf = function (node) { return node.parentElement || (node.getRootNode() && node.getRootNode().host); };

  /*
   * Maps a point in [frameWindow]'s layout viewport to the top document's,
   * checking at each frame boundary that the frame is what's there; then
   * to the WebView's pixels through the visual viewport (pinch zoom).
   */
  function toViewPoint(frameWindow, x, y) {
    var view = frameWindow;
    while (view !== window) {
      var frame = view.frameElement;
      if (!frame) return null;
      for (var ancestor = frame; ancestor; ancestor = parentOf(ancestor)) {
        var st = ancestor.ownerDocument.defaultView.getComputedStyle(ancestor);
        if (st.transform && st.transform !== 'none') return null;
      }
      var frameRect = frame.getBoundingClientRect();
      x += frameRect.left + frame.clientLeft;
      y += frameRect.top + frame.clientTop;
      view = view.parent;
      var parentHit = deepHit(view.document, x, y);
      if (!parentHit || (parentHit !== frame && !frame.contains(parentHit))) return null;
    }
    var vv = window.visualViewport;
    var offsetLeft = vv ? vv.offsetLeft : 0;
    var offsetTop = vv ? vv.offsetTop : 0;
    var scale = vv ? vv.scale : 1;
    var width = vv ? vv.width : window.innerWidth;
    var height = vv ? vv.height : window.innerHeight;
    if (x < offsetLeft || y < offsetTop || x >= offsetLeft + width || y >= offsetTop + height) return null;
    var ratio = window.devicePixelRatio || 1;
    return { x: (x - offsetLeft) * scale * ratio, y: (y - offsetTop) * scale * ratio };
  }

  function snapshot() {
    var token = a.token;
    var maxElements = a.maxElements;
    var search = normalize(a.query).toLowerCase();
    var elementOffset = a.elementOffset || 0;
    var textOffset = a.textOffset || 0;
    var fieldsTruncated = false;
    var shorten = function (value, limit) {
      var text = String(value || '');
      if (text.length <= limit) return text;
      fieldsTruncated = true;
      var end = /[\uD800-\uDBFF]/.test(text[limit - 1]) ? limit - 1 : limit;
      return text.slice(0, end);
    };
    var elements = [];
    var fingerprints = [];
    var frames = [];
    var dialogs = [];
    var pageText = [];
    var remainingText = 1000000;
    var textCollectionTruncated = false;
    var candidateCount = 0;
    var bytes = 0;
    var budgetReached = false;
    var viewport = null;
    var result = walk(search, function (element, frameWindow, frameId, info) {
      candidateCount += 1;
      var index = candidateCount - 1;
      if (index < elementOffset || elements.length >= maxElements || budgetReached) return false;
      var tag = element.tagName.toLowerCase();
      var inputType = normalize(element.getAttribute('type')).toLowerCase();
      var uploadsFile = tag === 'input' && inputType === 'file';
      var submitsForm = Boolean(element.form) &&
        ((tag === 'button' && (!inputType || inputType === 'submit')) ||
          (tag === 'input' && (inputType === 'submit' || inputType === 'image')));
      var downloadsFile = tag === 'a' && element.hasAttribute('href') && element.hasAttribute('download');
      var entry = { ref: token + '_' + index, frameId: frameId, role: shorten(info.role, 128), name: shorten(info.name, 300), tag: shorten(tag, 128) };
      if (info.name.length > 300) entry.nameTruncated = true;
      if (element.closest('dialog[open],[role="dialog"],[role="alertdialog"]')) entry.inDialog = true;
      if (info.inferred) entry.inferred = true;
      entry.disabled = element.matches(':disabled') || element.getAttribute('aria-disabled') === 'true';
      entry.focused = element === composedActive(element.ownerDocument);
      entry.editable = (!uploadsFile && element.matches('input:not([readonly]),textarea:not([readonly])')) || element.isContentEditable;
      var state = readControlState(element, info.role);
      for (var k in state) entry[k] = state[k];
      if (info.scrollable) entry.scrollable = info.scroll;
      if (info.scrollable && !info.semantic && !info.inferred) entry.scrollOnly = true;
      if (uploadsFile) entry.effect = 'file_upload';
      else if (downloadsFile) entry.effect = 'file_download';
      else if (submitsForm) entry.effect = 'form_submission';
      if (tag === 'a' && element.hasAttribute('href')) {
        var href = String(element.href || '');
        if (href.length <= 2048) entry.url = href; else entry.urlOmitted = true;
      }
      if (tag === 'select') {
        entry.multiple = element.multiple;
        var options = [];
        var optionBytes = 0;
        for (var i = 0; i < Math.min(element.options.length, 50); i++) {
          var option = element.options[i];
          if (option.value.length > 500) { entry.optionsTruncated = true; continue; }
          var o = { value: option.value, label: shorten(normalize(option.label || option.textContent), 200),
            disabled: option.matches(':disabled'), selected: option.selected };
          optionBytes += JSON.stringify(o).length;
          if (optionBytes > 3000) { entry.optionsTruncated = true; break; }
          options.push(o);
        }
        if (element.options.length > 50) entry.optionsTruncated = true;
        if (entry.optionsTruncated) fieldsTruncated = true;
        entry.options = options;
      }
      var size = JSON.stringify(entry).length;
      if (bytes + size > a.maxElementChars) { budgetReached = true; return false; }
      bytes += size;
      elements.push(entry);
      fingerprints.push(fingerprintOf(element, info));
      return false;
    }, function (frame) {
      if (frame.url) frame.url = frame.url.slice(0, 500);
      if (frames.length < 8) frames.push(frame); else fieldsTruncated = true;
    }, function (doc, frameId) {
      if (frameId === 'frame_main' && doc.scrollingElement) {
        viewport = readScrollState(doc.scrollingElement);
        viewport.ref = token + '_viewport';
      }
      var raw = doc.body ? doc.body.innerText || '' : '';
      if (raw.length > remainingText) textCollectionTruncated = true;
      var text = normalize(raw.slice(0, remainingText));
      remainingText = Math.max(0, remainingText - raw.length);
      if (text) pageText.push(text);
      var found = doc.querySelectorAll('dialog[open],[role="dialog"],[role="alertdialog"]');
      for (var d = 0; d < found.length && dialogs.length < 8; d++) {
        if (!visible(found[d])) continue;
        dialogs.push({ frameId: frameId, role: found[d].getAttribute('role') || 'dialog',
          name: readElementName(found[d]).slice(0, 240),
          modal: found[d].getAttribute('aria-modal') === 'true' });
      }
    });
    var fullText = pageText.join(' ');
    var textStart = Math.min(textOffset, fullText.length);
    if (textStart > 0 && /[\uDC00-\uDFFF]/.test(fullText[textStart] || '') && /[\uD800-\uDBFF]/.test(fullText[textStart - 1])) textStart -= 1;
    var excerptLength = a.maxText;
    var textMatch = null;
    var textQuery = normalize(a.textQuery);
    if (textQuery) {
      var literal = textQuery.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      var match = new RegExp(literal, 'iu').exec(fullText.slice(textStart));
      if (match) {
        var start = textStart + match.index;
        textMatch = { start: start, end: start + match[0].length };
        textStart = Math.max(0, start - 240);
        if (textStart > 0 && /[\uDC00-\uDFFF]/.test(fullText[textStart] || '')) textStart -= 1;
        excerptLength = Math.min(fullText.length, textMatch.end + 240) - textStart;
      } else {
        excerptLength = 0;
      }
    }
    var textEnd = Math.min(textStart + excerptLength, fullText.length);
    if (textEnd < fullText.length && /[\uDC00-\uDFFF]/.test(fullText[textEnd]) && /[\uD800-\uDBFF]/.test(fullText[textEnd - 1])) textEnd -= 1;
    var moreElements = candidateCount > elementOffset + elements.length;
    var moreText = textEnd < fullText.length;
    var out = {
      url: String(location.href).slice(0, 2048),
      title: shorten(document.title, 300),
      text: fullText.slice(textStart, textEnd),
      viewport: viewport,
      frames: frames,
      elements: elements,
      dialogs: dialogs,
      elementOffset: elementOffset,
      textOffset: textStart,
      elementsTruncated: moreElements || result.truncated,
      textTruncated: moreText || textCollectionTruncated || result.truncated,
      scanTruncated: result.truncated,
      textCollectionTruncated: textCollectionTruncated,
      fieldsTruncated: fieldsTruncated
    };
    if (search) out.query = normalize(a.query);
    if (moreElements) out.nextElementOffset = elementOffset + elements.length;
    if (!textQuery && moreText) out.nextTextOffset = textEnd;
    if (textQuery) {
      out.textQuery = textQuery;
      out.textMatch = textMatch;
      if (textMatch && textMatch.end < fullText.length) out.nextMatchOffset = textMatch.end;
    }
    // What the caller keeps to resolve a reference later; not for the model.
    out.fingerprints = fingerprints;
    return out;
  }

  /* What an approval shows about the control, read fresh just before it's asked. */
  function describe(element, info, action, key) {
    var tag = element.tagName.toLowerCase();
    var inputType = normalize(element.getAttribute('type')).toLowerCase();
    var submitsForm = Boolean(element.form) &&
      ((tag === 'button' && (!inputType || inputType === 'submit')) || (tag === 'input' && (inputType === 'submit' || inputType === 'image')));
    var activates = action === 'click' || (action === 'press' && (key === 'Enter' || key === 'Space'));
    var implicitSubmit = action === 'press' && key === 'Enter' && Boolean(element.form) && tag === 'input' &&
      ['button', 'checkbox', 'color', 'file', 'hidden', 'image', 'radio', 'range', 'reset', 'submit'].indexOf(inputType) < 0;
    var out = { role: info.role, tag: tag, name: info.name.slice(0, 300) };
    if (tag === 'input' && inputType === 'file') out.effect = 'file_upload';
    else if (tag === 'a' && element.hasAttribute('download') && activates) out.effect = 'file_download';
    else if ((submitsForm && activates) || implicitSubmit) out.effect = 'form_submission';
    if (tag === 'a' && element.hasAttribute('href') && activates) out.target = String(element.href).slice(0, 4096);
    else if (out.effect === 'form_submission') out.target = String(element.form.action).slice(0, 4096);
    out.sensitive = inputType === 'password' || /password|one-time-code/i.test(element.getAttribute('autocomplete') || '');
    return out;
  }

  function usable(element, frameWindow) {
    var style = frameWindow.getComputedStyle(element);
    return !(style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0' ||
      element.getClientRects().length === 0 || element.matches(':disabled') ||
      element.getAttribute('aria-disabled') === 'true');
  }

  function pointFor(found) {
    var element = found.element;
    var frameWindow = found.frameWindow;
    element.scrollIntoView({ block: 'center', inline: 'center' });
    var w = frameWindow;
    while (w !== window) {
      if (!w.frameElement) return null;
      w.frameElement.scrollIntoView({ block: 'center', inline: 'center' });
      w = w.parent;
    }
    var rect = element.getBoundingClientRect();
    var x = rect.left + rect.width / 2;
    var y = rect.top + rect.height / 2;
    var hit = deepHit(element.ownerDocument, x, y);
    if (!hit || (hit !== element && !element.contains(hit))) return null;
    return toViewPoint(frameWindow, x, y);
  }

  function scrollPoint(element, frameWindow, direction) {
    var horizontal = direction === 'left' || direction === 'right';
    var axis = horizontal ? 'horizontal' : 'vertical';
    var isViewport = element === element.ownerDocument.scrollingElement;
    var rect = isViewport ? { left: 0, top: 0, right: frameWindow.innerWidth, bottom: frameWindow.innerHeight } : element.getBoundingClientRect();
    var left = Math.max(0, rect.left), top = Math.max(0, rect.top);
    var right = Math.min(frameWindow.innerWidth, rect.right), bottom = Math.min(frameWindow.innerHeight, rect.bottom);
    if (right - left < 2 || bottom - top < 2) return null;
    var fs = [0.5, 0.3, 0.7];
    for (var i = 0; i < fs.length; i++) {
      for (var j = 0; j < fs.length; j++) {
        var x = Math.floor(left + (right - left) * fs[j]);
        var y = Math.floor(top + (bottom - top) * fs[i]);
        var hit = deepHit(element.ownerDocument, x, y);
        var nearest = null;
        while (hit) {
          if (hit === element.ownerDocument.scrollingElement || readScrollState(hit)[axis]) { nearest = hit; break; }
          hit = parentOf(hit);
        }
        if (nearest === null && isViewport) nearest = element;
        if (nearest !== element) continue;
        var p = toViewPoint(frameWindow, x, y);
        if (p) return p;
      }
    }
    return null;
  }

  function scrollTargetFor() {
    if (a.viewport) {
      var el = document.scrollingElement;
      return el ? { element: el, frameWindow: window } : { error: 'changed' };
    }
    return find(a.index, a.query, a.fingerprint);
  }

  switch (op) {
    case 'snapshot':
      return snapshot();
    case 'describe': {
      var f = find(a.index, a.query, a.fingerprint);
      if (f.error) return f;
      return { ok: true, target: describe(f.element, f.info, a.action, a.key) };
    }
    case 'point': {
      // Click: scrolled into view, hit-tested, as a point in the WebView.
      var c = find(a.index, a.query, a.fingerprint);
      if (c.error) return c;
      if (!usable(c.element, c.frameWindow)) return { error: 'not_interactable' };
      var p = pointFor(c);
      if (!p) return { error: 'not_interactable' };
      return { ok: true, x: p.x, y: p.y, editable: c.element.matches('input:not([readonly]),textarea:not([readonly])') || c.element.isContentEditable };
    }
    case 'focus': {
      // Type / press: focused, and for type with replace, its text selected.
      var t = find(a.index, a.query, a.fingerprint);
      if (t.error) return t;
      var el = t.element;
      if (!usable(el, t.frameWindow)) return { error: 'not_interactable' };
      var editable = el.matches('input:not([readonly]),textarea:not([readonly])') || el.isContentEditable;
      if (a.action === 'type' && !editable) return { error: 'not_interactable' };
      el.scrollIntoView({ block: 'center', inline: 'center' });
      el.focus();
      if (composedActive(el.ownerDocument) !== el) return { error: 'not_interactable' };
      if (a.action === 'type') {
        if (a.replace && typeof el.select === 'function') {
          el.select();
        } else if (a.replace && el.isContentEditable) {
          var sel = el.ownerDocument.defaultView.getSelection();
          var range = el.ownerDocument.createRange();
          range.selectNodeContents(el);
          sel.removeAllRanges();
          sel.addRange(range);
        } else if (!a.replace && typeof el.setSelectionRange === 'function') {
          var end = String(el.value || '').length;
          try { el.setSelectionRange(end, end); } catch (e) { /* not a text input type */ }
        }
      }
      return { ok: true };
    }
    case 'value': {
      var v = find(a.index, a.query, a.fingerprint);
      if (v.error) return v;
      var ve = v.element;
      return { ok: true, value: String(ve.isContentEditable ? ve.innerText : ve.value || '').slice(0, 100000) };
    }
    case 'select': {
      var s = find(a.index, a.query, a.fingerprint);
      if (s.error) return s;
      var se = s.element;
      if (!usable(se, s.frameWindow)) return { error: 'not_interactable' };
      if (se.tagName.toLowerCase() !== 'select' || se.options.length > 1000) return { error: 'unsupported_select' };
      var values = a.values;
      if (!se.multiple && values.length !== 1) return { error: 'unsupported_select' };
      var options = Array.prototype.slice.call(se.options);
      var wanted = {};
      values.forEach(function (x) { wanted[x] = true; });
      if (values.some(function (x) { return options.filter(function (o) { return o.value === x; }).length !== 1; })) return { error: 'option_unavailable' };
      if (options.some(function (o) { return o.matches(':disabled') && (se.multiple ? o.selected !== !!wanted[o.value] : !!wanted[o.value]); })) return { error: 'option_unavailable' };
      if (se.multiple) {
        options.forEach(function (o) { o.selected = !!wanted[o.value]; });
      } else {
        se.value = values[0];
      }
      se.dispatchEvent(new s.frameWindow.Event('input', { bubbles: true }));
      se.dispatchEvent(new s.frameWindow.Event('change', { bubbles: true }));
      var actual = Array.prototype.map.call(se.selectedOptions, function (o) { return o.value; });
      return actual.length === values.length && actual.every(function (x) { return wanted[x]; })
        ? { ok: true, trusted: false } : { error: 'selection_not_applied' };
    }
    case 'scrollPoint': {
      var st = scrollTargetFor();
      if (st.error) return st;
      var state = readScrollState(st.element);
      var horizontal = a.direction === 'left' || a.direction === 'right';
      if (!state[horizontal ? 'horizontal' : 'vertical']) return { ok: true, scroll: state, boundary: true };
      var coord = horizontal ? state.x : state.y;
      var forward = a.direction === 'down' || a.direction === 'right';
      var min = horizontal ? state.minX : 0;
      var max = horizontal ? state.maxX : state.maxY;
      if (forward ? coord >= max - 1 : coord <= min + 1) return { ok: true, scroll: state, boundary: true };
      var sp = scrollPoint(st.element, st.frameWindow, a.direction);
      if (!sp) return { error: 'not_interactable' };
      var vv = window.visualViewport;
      var factor = (vv ? vv.scale : 1) * (window.devicePixelRatio || 1);
      return { ok: true, scroll: state, x: sp.x, y: sp.y, pixelsPerCss: factor };
    }
    case 'scrollState': {
      var ss = scrollTargetFor();
      if (ss.error) return ss;
      return { ok: true, scroll: readScrollState(ss.element) };
    }
    case 'state': {
      // browser_wait's element condition; a control gone counts as hidden.
      var m = find(a.index, a.query, a.fingerprint);
      if (m.error) return { ok: true, matched: a.state === 'hidden' };
      var me = m.element;
      var shown = visible(me);
      if (a.state === 'hidden') return { ok: true, matched: !shown };
      if (!shown) return { ok: true, matched: false };
      var disabled = me.matches(':disabled') || me.getAttribute('aria-disabled') === 'true';
      var cs = readControlState(me, m.info.role);
      return { ok: true, matched: ({ visible: true, enabled: !disabled, disabled: disabled, checked: cs.checked === true,
        unchecked: cs.checked === false, expanded: cs.expanded === true, collapsed: cs.expanded === false })[a.state] === true };
    }
    case 'hasText': {
      var has = function (w) {
        try {
          if (String(w.document.body ? w.document.body.innerText : '').indexOf(a.text) >= 0) return true;
          var kids = w.document.querySelectorAll('iframe,frame');
          for (var q = 0; q < kids.length; q++) if (kids[q].contentWindow && has(kids[q].contentWindow)) return true;
        } catch (e) { /* cross-origin */ }
        return false;
      };
      return { ok: true, matched: has(window) };
    }
    default:
      return { error: 'unknown' };
  }
})
