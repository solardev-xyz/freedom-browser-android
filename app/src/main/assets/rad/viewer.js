// Radicle repository browser (#124). Reads the address it was loaded at
// (https://rad.freedom.baby/<rid>/…, shown as rad://<rid>/…) and renders
// it from the read API at /_/api/<rid>/…. Everything a repository
// supplies — names, paths, issue text, a README — is someone else's
// text: it only ever reaches the page through textContent / createElement,
// never as markup, and the page's CSP runs no script but this one (and
// /_/strings.js, the app's own text table for it).
'use strict';

(function () {
  var RID_RE = /^z[1-9A-HJ-NP-Za-km-z]{20,60}$/;
  var REV_RE = /^[0-9a-f]{40}$/;
  var COB_RE = /^[0-9a-f]{6,40}$/;
  var main = document.getElementById('main');
  var titleEl = document.getElementById('title');
  var ridEl = document.getElementById('rid');
  var tabsEl = document.getElementById('tabs');

  // ------------------------------------------------------------------
  // Text (#280): the app serves its string resources as
  // window.RAD_STRINGS (/_/strings.js) — S.<key> sentences, P.<key>
  // counted phrases by plural category. Each is only ever used as text.
  // ------------------------------------------------------------------

  var T = window.RAD_STRINGS || {};
  var S = T.strings || {};
  var P = T.plurals || {};
  var LANG = T.lang || 'en';
  var pluralRules = null;
  var numberFormat = null;
  try {
    pluralRules = new Intl.PluralRules(LANG);
    numberFormat = new Intl.NumberFormat(LANG, { useGrouping: false });
  } catch (e) { /* plain English fallbacks below */ }

  function num(n) {
    return numberFormat ? numberFormat.format(n) : String(n);
  }

  // The pieces of pattern with %1$s / %1$d filled from args (a number
  // formatted for the language, a DOM node kept as a node) and %% as %.
  function pieces(pattern, args) {
    var out = [];
    var re = /%(\d+)\$([sd])|%%/g;
    var last = 0;
    var m;
    pattern = String(pattern === undefined ? '' : pattern);
    while ((m = re.exec(pattern)) !== null) {
      if (m.index > last) out.push(pattern.slice(last, m.index));
      if (!m[1]) {
        out.push('%');
      } else {
        var a = args[parseInt(m[1], 10) - 1];
        if (a === null || a === undefined) a = '';
        out.push(m[2] === 'd' && typeof a === 'number' ? num(a) : a);
      }
      last = re.lastIndex;
    }
    if (last < pattern.length) out.push(pattern.slice(last));
    return out;
  }

  // pattern filled in as plain text.
  function fmt(pattern) {
    return pieces(pattern, Array.prototype.slice.call(arguments, 1)).map(function (p) {
      return typeof p === 'object' ? p.textContent : String(p);
    }).join('');
  }

  // pattern filled in, as pieces for el() / append(): a node argument stays a node.
  function fmtNodes(pattern) {
    return pieces(pattern, Array.prototype.slice.call(arguments, 1));
  }

  // The plural form of forms (P.<key>) for count n.
  function plural(forms, n) {
    forms = forms || {};
    var category = pluralRules ? pluralRules.select(n) : (n === 1 ? 'one' : 'other');
    var form = forms[category];
    return form === undefined ? forms.other : form;
  }

  // ------------------------------------------------------------------
  // DOM helpers
  // ------------------------------------------------------------------

  function el(tag, attrs) {
    var node = document.createElement(tag);
    if (attrs) {
      for (var k in attrs) {
        if (!Object.prototype.hasOwnProperty.call(attrs, k)) continue;
        var v = attrs[k];
        if (v === null || v === undefined || v === false) continue;
        if (k === 'text') node.textContent = String(v);
        else if (k === 'class') node.className = v;
        else node.setAttribute(k, v);
      }
    }
    for (var i = 2; i < arguments.length; i++) append(node, arguments[i]);
    return node;
  }

  function append(node, child) {
    if (child === null || child === undefined || child === false) return;
    if (Array.isArray(child)) {
      child.forEach(function (c) { append(node, c); });
    } else if (typeof child === 'string' || typeof child === 'number') {
      node.appendChild(document.createTextNode(String(child)));
    } else {
      node.appendChild(child);
    }
  }

  function show() {
    main.textContent = '';
    for (var i = 0; i < arguments.length; i++) append(main, arguments[i]);
  }

  function loading(what) {
    show(el('div', { class: 'status' }, el('span', { class: 'spinner' }), what || S.loading));
  }

  function notice(kind, heading) {
    var box = el('div', { class: 'notice ' + kind }, el('h2', { text: heading }));
    for (var i = 2; i < arguments.length; i++) append(box, arguments[i]);
    return box;
  }

  function seg(s) {
    return encodeURIComponent(s);
  }

  function pathHref(parts) {
    return '/' + parts.map(seg).join('/');
  }

  function short(id) {
    return String(id || '').slice(0, 7);
  }

  function when(ts) {
    if (typeof ts !== 'number' || !isFinite(ts)) return '';
    var ms = ts > 1e12 ? ts : ts * 1000;
    var secs = Math.floor((Date.now() - ms) / 1000);
    if (secs < 60) return S.just_now;
    var units = [[60, P.minutes_ago], [60, P.hours_ago], [24, P.days_ago], [30, P.months_ago], [12, P.years_ago]];
    var n = secs;
    var forms = P.seconds_ago;
    for (var i = 0; i < units.length; i++) {
      if (n < units[i][0]) break;
      n = Math.floor(n / units[i][0]);
      forms = units[i][1];
    }
    return fmt(plural(forms, n), n);
  }

  function authorName(a) {
    if (!a) return S.unknown_author;
    if (typeof a === 'string') return a;
    if (a.alias) return a.alias;
    if (a.name) return a.name;
    var id = a.id || a.did || '';
    id = String(id).replace(/^did:key:/, '');
    return id ? id.slice(0, 6) + '…' + id.slice(-4) : S.unknown_author;
  }

  // ------------------------------------------------------------------
  // Markdown, as DOM. A small subset — headings, paragraphs, lists,
  // quotes, fenced and inline code, emphasis, links — and nothing that
  // loads from elsewhere: images show as their alt text.
  // ------------------------------------------------------------------

  function safeHref(href, base) {
    href = String(href || '').trim();
    if (/^(https?:|rad:)/i.test(href)) {
      return /^rad:/i.test(href) ? radHref(href) : href;
    }
    if (/^[a-z][a-z0-9+.-]*:/i.test(href) || href.indexOf('//') === 0) return null;
    if (href.charAt(0) === '#') return href;
    if (!base) return null;
    return base(href);
  }

  function radHref(href) {
    var m = /^rad:(?:\/\/)?(z[1-9A-HJ-NP-Za-km-z]{20,60})(\/[^?#]*)?/i.exec(href);
    return m ? '/' + m[1] + (m[2] || '') : null;
  }

  function inline(text, base) {
    var out = [];
    var re = /(`+)([\s\S]*?)\1|!\[([^\]]*)\]\(([^)\s]*)[^)]*\)|\[([^\]]+)\]\(([^)\s]*)[^)]*\)|\*\*([\s\S]+?)\*\*|__([\s\S]+?)__|\*([^*\s][^*]*?)\*|(https?:\/\/[^\s<>()]+[^\s<>().,;:!?'"])/g;
    var last = 0;
    var m;
    while ((m = re.exec(text)) !== null) {
      if (m.index > last) out.push(text.slice(last, m.index));
      if (m[1]) out.push(el('code', { text: m[2] }));
      else if (m[3] !== undefined && m[4] !== undefined) out.push('[' + (m[3] || S.image) + ']');
      else if (m[5] !== undefined) {
        var href = safeHref(m[6], base);
        out.push(href ? el('a', { href: href, rel: 'noopener noreferrer' }, inline(m[5], base)) : m[5]);
      } else if (m[7] !== undefined || m[8] !== undefined) out.push(el('strong', null, inline(m[7] || m[8], base)));
      else if (m[9] !== undefined) out.push(el('em', null, inline(m[9], base)));
      else if (m[10]) out.push(el('a', { href: m[10], rel: 'noopener noreferrer' }, m[10]));
      last = re.lastIndex;
    }
    if (last < text.length) out.push(text.slice(last));
    return out;
  }

  function markdown(src, base) {
    var root = el('div', { class: 'markdown' });
    var lines = String(src || '').replace(/\r\n?/g, '\n').split('\n');
    var i = 0;
    var para = [];
    function flush() {
      if (para.length) root.appendChild(el('p', null, inline(para.join(' '), base)));
      para = [];
    }
    while (i < lines.length) {
      var line = lines[i];
      var fence = /^\s*(```+|~~~+)/.exec(line);
      if (fence) {
        flush();
        var code = [];
        i++;
        while (i < lines.length && lines[i].trim().indexOf(fence[1]) !== 0) code.push(lines[i++]);
        i++;
        root.appendChild(el('pre', null, el('code', { text: code.join('\n') })));
        continue;
      }
      var h = /^(#{1,6})\s+(.*?)\s*#*\s*$/.exec(line);
      if (h) {
        flush();
        root.appendChild(el('h' + Math.min(h[1].length, 6), null, inline(h[2], base)));
        i++;
        continue;
      }
      if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) {
        flush();
        root.appendChild(el('hr'));
        i++;
        continue;
      }
      if (/^\s*>/.test(line)) {
        flush();
        var quoted = [];
        while (i < lines.length && /^\s*>/.test(lines[i])) quoted.push(lines[i++].replace(/^\s*> ?/, ''));
        var q = markdown(quoted.join('\n'), base);
        root.appendChild(el('blockquote', null, Array.prototype.slice.call(q.childNodes)));
        continue;
      }
      var li = /^\s*([-*+]|\d+[.)])\s+(.*)$/.exec(line);
      if (li) {
        flush();
        var ordered = /\d/.test(li[1]);
        var list = el(ordered ? 'ol' : 'ul');
        while (i < lines.length) {
          var item = /^\s*([-*+]|\d+[.)])\s+(.*)$/.exec(lines[i]);
          if (!item) break;
          var text = [item[2]];
          i++;
          while (i < lines.length && /^\s{2,}\S/.test(lines[i]) && !/^\s*([-*+]|\d+[.)])\s+/.test(lines[i])) {
            text.push(lines[i++].trim());
          }
          list.appendChild(el('li', null, inline(text.join(' '), base)));
        }
        root.appendChild(list);
        continue;
      }
      if (/^\s*$/.test(line)) {
        flush();
        i++;
        continue;
      }
      para.push(line.trim());
      i++;
    }
    flush();
    return root;
  }

  // ------------------------------------------------------------------
  // The API
  // ------------------------------------------------------------------

  function Failure(status, body) {
    this.status = status;
    this.body = body || {};
  }

  function api(rid, path) {
    return fetch('/_/api/' + rid + (path || ''), { headers: { Accept: 'application/json' } }).then(
      function (res) {
        return res.json().then(
          function (body) {
            if (!res.ok) throw new Failure(res.status, body);
            return body;
          },
          function () {
            throw new Failure(res.status, { error: S.unreadable_response });
          }
        );
      }
    );
  }

  function failureNotice(err, rid) {
    var body = (err && err.body) || {};
    var reason = body.reason;
    if (reason === 'integration-disabled') {
      return notice('warn', S.off_title,
        el('p', { text: S.off_body }));
    }
    if (reason === 'node-not-ready') {
      return notice('warn', S.starting_title,
        el('p', { text: S.starting_body }));
    }
    if (reason === 'node-stopped') {
      return notice('warn', S.stopped_title,
        el('p', { text: S.stopped_body }));
    }
    if (err && err.status === 404 && rid) {
      return notice('error', S.repo_not_found_title,
        el('p', null, fmtNodes(S.repo_not_found_body, el('code', { text: 'rad://' + rid }))),
        el('p', { text: S.repo_not_found_hint }));
    }
    if (err && err.status === 403) {
      return notice('error', S.not_available_title, el('p', { text: body.error || S.not_public }));
    }
    if (err && err.status === 404) {
      return notice('error', S.not_found_title, el('p', { text: body.error || S.nothing_here }));
    }
    return notice('error', S.load_failed_title, el('p', { text: (body && body.error) || String(err) }));
  }

  // ------------------------------------------------------------------
  // Views
  // ------------------------------------------------------------------

  function project(meta) {
    var p = (meta && meta.payloads && meta.payloads['xyz.radicle.project']) || {};
    return { data: p.data || {}, meta: p.meta || {} };
  }

  function setHeader(rid, meta) {
    var p = project(meta);
    var name = p.data.name || S.repository;
    titleEl.textContent = name;
    ridEl.textContent = 'rad://' + rid;
    document.title = fmt(S.page_title, name);
  }

  function setTabs(rid, meta, active) {
    var p = project(meta);
    var issues = p.meta.issues && typeof p.meta.issues.open === 'number' ? p.meta.issues.open : null;
    var patches = p.meta.patches && typeof p.meta.patches.open === 'number' ? p.meta.patches.open : null;
    function tab(key, label, href, count) {
      return el('a', { href: href, class: key === active ? 'on' : null }, label,
        count !== null ? el('span', { class: 'count', text: count }) : null);
    }
    tabsEl.textContent = '';
    append(tabsEl, [
      tab('code', S.tab_code, pathHref([rid]), null),
      tab('issues', S.tab_issues, pathHref([rid, 'issues']), issues),
      tab('patches', S.tab_patches, pathHref([rid, 'patches']), patches),
      tab('commits', S.tab_commits, pathHref([rid, 'commits']), null),
    ]);
    tabsEl.hidden = false;
  }

  function withRepo(rid, active, render) {
    loading();
    ridEl.textContent = 'rad://' + rid;
    api(rid, '').then(function (meta) {
      setHeader(rid, meta);
      setTabs(rid, meta, active);
      return render(meta);
    }).catch(function (err) {
      show(failureNotice(err, rid));
    });
  }

  function summaryCard(rid, meta) {
    var p = project(meta);
    var facts = el('div', { class: 'facts' });
    if (p.data.defaultBranch) {
      append(facts, el('span', null, fmtNodes(S.fact_branch, el('b', { text: p.data.defaultBranch }))));
    }
    if (p.meta.head) append(facts, el('span', null, fmtNodes(S.fact_head, el('b', { class: 'mono', text: short(p.meta.head) }))));
    if (Array.isArray(meta.delegates)) {
      var delegates = meta.delegates.length;
      append(facts, el('span', null, fmtNodes(plural(P.delegates, delegates), el('b', { text: num(delegates) }))));
    }
    if (typeof meta.seeding === 'number') {
      append(facts, el('span', null, fmtNodes(plural(P.seeds, meta.seeding), el('b', { text: num(meta.seeding) }))));
    }
    return el('div', { class: 'card' }, el('div', { class: 'card-body' },
      p.data.description ? el('p', { class: 'desc', text: p.data.description }) : null, facts));
  }

  function crumbs(rid, rev, pinned, parts) {
    var box = el('div', { class: 'crumbs' });
    var root = pinned ? pathHref([rid, 'tree', rev]) : pathHref([rid]);
    append(box, el('a', { href: root, text: titleEl.textContent || S.root }));
    for (var i = 0; i < parts.length; i++) {
      append(box, el('span', { class: 'sep', text: '/' }));
      if (i === parts.length - 1) append(box, parts[i]);
      else append(box, el('a', { href: pathHref([rid, 'tree', rev].concat(parts.slice(0, i + 1))), text: parts[i] }));
    }
    if (pinned) append(box, el('span', { class: 'sep', text: '@' + short(rev) }));
    return box;
  }

  function treeView(rid, meta, rev, pinned, parts) {
    var path = parts.map(seg).join('/');
    return api(rid, '/tree/' + rev + (path ? '/' + path : '')).then(function (tree) {
      var entries = (tree.entries || []).slice().sort(function (a, b) {
        var ka = a.kind === 'tree' ? 0 : 1;
        var kb = b.kind === 'tree' ? 0 : 1;
        return ka - kb || String(a.name).localeCompare(String(b.name));
      });
      var list = el('ul', { class: 'list' });
      if (!entries.length) list.appendChild(el('li', null, el('div', { class: 'row' }, el('span', { class: 'sub', text: S.empty }))));
      entries.forEach(function (e) {
        var child = parts.concat([String(e.name)]);
        var dir = e.kind === 'tree';
        var href = dir ? pathHref([rid, 'tree', rev].concat(child)) : pathHref([rid, 'blob', rev].concat(child));
        list.appendChild(el('li', null, el('a', { class: 'row', href: e.kind === 'submodule' ? null : href },
          el('span', { class: 'icon', text: dir ? '▸' : e.kind === 'submodule' ? '⇲' : '·' }),
          el('span', { class: 'main', text: e.name + (dir ? '/' : '') }))));
      });
      var last = tree.lastCommit;
      var head = last ? el('div', { class: 'card-head' },
        el('span', { class: 'mono', text: short(last.id) + ' ' }), last.summary || '',
        el('div', { class: 'sub', text: authorName(last.author) + ' · ' + when(last.committer && last.committer.time) })) : null;
      var out = [crumbs(rid, rev, pinned, parts), el('div', { class: 'card' }, head, list)];
      if (!parts.length) {
        return api(rid, '/readme/' + rev).then(function (readme) {
          out.push(readmeCard(rid, rev, pinned, readme));
          return out;
        }, function () {
          return out;
        });
      }
      return out;
    });
  }

  function blobBase(rid, rev, dirParts) {
    return function (href) {
      var clean = href.split('#')[0].split('?')[0];
      if (!clean) return null;
      var parts = clean.charAt(0) === '/' ? [] : dirParts.slice();
      clean.split('/').forEach(function (p) {
        if (!p || p === '.') return;
        if (p === '..') parts.pop();
        else {
          try { parts.push(decodeURIComponent(p)); } catch (e) { parts.push(p); }
        }
      });
      if (!parts.length) return pathHref([rid, 'tree', rev]);
      return pathHref([rid, 'blob', rev].concat(parts));
    };
  }

  function readmeCard(rid, rev, pinned, readme) {
    var body = readme.binary || typeof readme.content !== 'string'
      ? el('div', { class: 'binary', text: S.binary })
      : /\.(md|markdown)$/i.test(readme.path || '')
        ? markdown(readme.content, blobBase(rid, rev, []))
        : el('pre', { class: 'code', text: readme.content });
    return el('div', { class: 'card' }, el('h2', { text: readme.path || 'README' }), body);
  }

  function blobView(rid, rev, pinned, parts) {
    return api(rid, '/blob/' + rev + '/' + parts.map(seg).join('/')).then(function (blob) {
      var name = parts[parts.length - 1];
      var body;
      if (blob.binary || typeof blob.content !== 'string') {
        body = el('div', { class: 'binary', text: S.binary_not_shown });
      } else if (/\.(md|markdown)$/i.test(name)) {
        body = markdown(blob.content, blobBase(rid, rev, parts.slice(0, -1)));
      } else {
        body = el('pre', { class: 'code', text: blob.content });
      }
      return [crumbs(rid, rev, pinned, parts), el('div', { class: 'card' }, el('h2', { text: name }), body)];
    });
  }

  function codeRoute(rid, rest) {
    withRepo(rid, 'code', function (meta) {
      var head = project(meta).meta.head;
      var kind = rest[0];
      var pinned = (kind === 'tree' || kind === 'blob') && REV_RE.test(rest[1] || '');
      var rev = pinned ? rest[1] : head;
      if (!rev) {
        show(summaryCard(rid, meta), notice('warn', S.nothing_yet_title,
          el('p', { text: S.no_head_body })));
        return;
      }
      var parts = pinned ? rest.slice(2) : [];
      var job = kind === 'blob' && pinned && parts.length ? blobView(rid, rev, pinned, parts)
        : treeView(rid, meta, rev, pinned, parts);
      return job.then(function (nodes) {
        show(parts.length ? null : summaryCard(rid, meta), nodes);
      });
    });
  }

  // An issue or patch state as the page shows it (unknown ones as they are).
  function stateLabel(st) {
    var labels = {
      open: S.state_open, closed: S.state_closed, solved: S.state_solved,
      draft: S.state_draft, merged: S.state_merged, archived: S.state_archived,
    };
    return Object.prototype.hasOwnProperty.call(labels, st) && labels[st] !== undefined ? labels[st] : st;
  }

  // The empty list's line: "No open issues.", or the state as the address had it.
  function noneLine(kind, status) {
    var lines = kind === 'issues'
      ? { open: S.no_open_issues, closed: S.no_closed_issues }
      : { open: S.no_open_patches, draft: S.no_draft_patches, merged: S.no_merged_patches, archived: S.no_archived_patches };
    if (Object.prototype.hasOwnProperty.call(lines, status) && lines[status] !== undefined) return lines[status];
    return fmt(kind === 'issues' ? S.no_issues_in_state : S.no_patches_in_state, status);
  }

  function statusFilters(rid, kind, current, states) {
    return el('div', { class: 'filters' }, states.map(function (s) {
      return el('a', { href: pathHref([rid, kind]) + '?status=' + s, class: s === current ? 'on' : null, text: stateLabel(s) });
    }));
  }

  function listRoute(rid, kind, params) {
    var status = params.get('status') || 'open';
    var page = Math.max(0, parseInt(params.get('page') || '0', 10) || 0);
    var states = kind === 'issues' ? ['open', 'closed'] : ['open', 'draft', 'merged', 'archived'];
    withRepo(rid, kind, function () {
      return api(rid, '/' + kind + '?status=' + encodeURIComponent(status) + '&page=' + page + '&perPage=30')
        .then(function (items) {
          var list = el('ul', { class: 'list' });
          if (!items.length) list.appendChild(el('li', null, el('div', { class: 'row' },
            el('span', { class: 'sub', text: noneLine(kind, status) }))));
          items.forEach(function (it) {
            var st = (it.state && it.state.status) || '';
            var labels = (it.labels || []).map(function (l) { return el('span', { class: 'label', text: l }); });
            var sub = authorName(it.author) + ' · ' + short(it.id);
            var opened = kind === 'issues' ? it.discussion && it.discussion[0] && it.discussion[0].timestamp
              : it.revisions && it.revisions[0] && it.revisions[0].timestamp;
            if (opened) sub += ' · ' + when(opened);
            list.appendChild(el('li', null, el('a', { class: 'row', href: pathHref([rid, kind, it.id]) },
              el('span', { class: 'main' }, el('span', { class: 'badge ' + st, text: stateLabel(st) }), it.title || S.untitled,
                el('div', { class: 'sub', text: sub }), labels.length ? el('div', null, labels) : null))));
          });
          var more = items.length === 30
            ? el('a', { class: 'more', href: pathHref([rid, kind]) + '?status=' + status + '&page=' + (page + 1), text: S.more })
            : null;
          show(statusFilters(rid, kind, status, states), el('div', { class: 'card' }, list), more);
        });
    });
  }

  function thread(comments) {
    var byId = {};
    comments.forEach(function (c) { byId[c.id] = c; });
    return comments.map(function (c, index) {
      var reply = c.replyTo && byId[c.replyTo];
      return el('div', { class: 'comment' + (reply && index > 0 ? ' reply' : '') },
        el('div', { class: 'who' }, el('b', { text: authorName(c.author) }), ' · ' + when(c.timestamp)),
        markdown(c.body || ''));
    });
  }

  function issueRoute(rid, id) {
    withRepo(rid, 'issues', function () {
      return api(rid, '/issues/' + id).then(function (issue) {
        var st = (issue.state && issue.state.status) || '';
        var labels = (issue.labels || []).map(function (l) { return el('span', { class: 'label', text: l }); });
        var discussion = issue.discussion || [];
        show(
          el('div', { class: 'card' }, el('div', { class: 'card-body' },
            el('h2', null, el('span', { class: 'badge ' + st, text: stateLabel(st) }), issue.title || S.untitled),
            el('div', { class: 'sub mono', text: fmt(S.issue_id, issue.id) }),
            labels.length ? el('div', null, labels) : null)),
          el('div', { class: 'card' }, el('h2', { text: fmt(plural(P.comments, discussion.length), discussion.length) }),
            thread(discussion))
        );
      });
    });
  }

  function patchRoute(rid, id) {
    withRepo(rid, 'patches', function () {
      return api(rid, '/patches/' + id).then(function (patch) {
        var st = (patch.state && patch.state.status) || '';
        var revisions = patch.revisions || [];
        var cards = revisions.map(function (r, i) {
          var head = el('div', { class: 'card-head' },
            fmtNodes(S.revision, i + 1, el('span', { class: 'mono', text: short(r.id) })),
            el('div', { class: 'sub', text: authorName(r.author) + ' · ' + when(r.timestamp) +
              (r.oid ? ' · ' + fmt(S.revision_head, short(r.oid)) : '') }));
          var body = [];
          if (r.description) body.push(el('div', { class: 'comment' }, markdown(r.description)));
          body = body.concat(thread(r.discussion || []));
          var browse = r.oid && REV_RE.test(r.oid)
            ? el('a', { class: 'more', href: pathHref([rid, 'tree', r.oid]), text: S.browse_revision })
            : null;
          return el('div', { class: 'card' }, head, body, browse);
        });
        show(
          el('div', { class: 'card' }, el('div', { class: 'card-body' },
            el('h2', null, el('span', { class: 'badge ' + st, text: stateLabel(st) }), patch.title || S.untitled),
            el('div', { class: 'sub mono', text: fmt(S.patch_id, patch.id) }))),
          cards
        );
      });
    });
  }

  function commitsRoute(rid, params) {
    withRepo(rid, 'commits', function (meta) {
      var head = params.get('parent') || project(meta).meta.head;
      var page = Math.max(0, parseInt(params.get('page') || '0', 10) || 0);
      if (!head || !REV_RE.test(head)) {
        show(notice('warn', S.no_history_title, el('p', { text: S.no_head_commit })));
        return;
      }
      return api(rid, '/commits?parent=' + head + '&page=' + page + '&perPage=30').then(function (res) {
        var commits = Array.isArray(res) ? res : res.commits || [];
        var list = el('ul', { class: 'list' });
        commits.forEach(function (entry) {
          var c = entry.commit || entry;
          var t = c.committer && c.committer.time;
          list.appendChild(el('li', null, el('a', { class: 'row', href: pathHref([rid, 'tree', c.id]) },
            el('span', { class: 'icon mono', text: '•' }),
            el('span', { class: 'main' }, c.summary || S.no_message,
              el('div', { class: 'sub', text: short(c.id) + ' · ' + authorName(c.author) + (t ? ' · ' + when(t) : '') })))));
        });
        var more = commits.length === 30
          ? el('a', { class: 'more', href: pathHref([rid, 'commits']) + '?parent=' + head + '&page=' + (page + 1), text: S.older })
          : null;
        show(el('div', { class: 'card' }, list), more);
      });
    });
  }

  function landing(invalid) {
    tabsEl.hidden = true;
    ridEl.textContent = '';
    var input = el('input', { type: 'text', placeholder: S.rid_placeholder, autocapitalize: 'off', autocomplete: 'off', spellcheck: 'false' });
    var go = el('button', { type: 'button', text: S.open });
    function open() {
      var m = /^(?:rad:(?:\/\/)?)?(z[1-9A-HJ-NP-Za-km-z]{20,60})$/.exec(input.value.trim());
      if (m) location.href = pathHref([m[1]]);
      else input.setCustomValidity(S.not_a_rid);
    }
    go.addEventListener('click', open);
    input.addEventListener('keydown', function (e) { if (e.key === 'Enter') open(); });
    input.addEventListener('input', function () { input.setCustomValidity(''); });
    var parts = [];
    if (invalid !== null) {
      parts.push(notice('error', S.invalid_title,
        el('p', null, fmtNodes(S.invalid_body, el('code', { text: 'rad://' + invalid }))),
        el('p', { text: S.invalid_hint })));
    }
    parts.push(notice('', S.open_title,
      el('p', { text: S.open_body }),
      el('div', { class: 'go' }, input, go)));
    show(parts);
  }

  // ------------------------------------------------------------------
  // Routing
  // ------------------------------------------------------------------

  function route() {
    var params = new URLSearchParams(location.search);
    if (location.pathname === '/_/invalid') {
      landing(params.get('id') || '');
      return;
    }
    var raw = location.pathname.split('/').filter(function (s, i, all) { return s !== '' || i === all.length - 1; });
    var parts = [];
    for (var i = 0; i < raw.length; i++) {
      if (raw[i] === '') continue;
      try {
        parts.push(decodeURIComponent(raw[i]));
      } catch (e) {
        parts.push(raw[i]);
      }
    }
    if (!parts.length) {
      landing(null);
      return;
    }
    var rid = parts[0];
    if (!RID_RE.test(rid)) {
      landing(rid);
      return;
    }
    var rest = parts.slice(1);
    var section = rest[0];
    if (!section || section === 'tree' || section === 'blob') codeRoute(rid, rest);
    else if ((section === 'issues' || section === 'patches') && rest.length === 1) listRoute(rid, section, params);
    else if (section === 'issues' && COB_RE.test(rest[1] || '')) issueRoute(rid, rest[1]);
    else if (section === 'patches' && COB_RE.test(rest[1] || '')) patchRoute(rid, rest[1]);
    else if (section === 'commits') commitsRoute(rid, params);
    else withRepo(rid, null, function () { show(notice('error', S.not_found_title, el('p', { text: S.no_page }))); });
  }

  route();
})();
