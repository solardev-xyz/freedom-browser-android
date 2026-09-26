// Shared by the reserved-mode fixtures: `?dark` switches to the dark
// palette, and a small badge counts viewport resizes and taps, so a
// screenshot shows whether the WebView was resized (it must not be,
// per frame, while the bar compacts) and which control got a tap.
(function () {
  var dark = /[?&]dark\b/.test(location.search);
  document.documentElement.classList.toggle('dark', dark);
  var resizes = 0, last = 'none', h0 = innerHeight;
  var badge = document.createElement('div');
  badge.id = 'badge';
  function draw() {
    badge.textContent = 'vh ' + innerHeight + ' · resizes ' + resizes + ' · tapped ' + last;
  }
  addEventListener('resize', function () { resizes++; draw(); });
  document.addEventListener('click', function (e) {
    var t = e.target.closest('a, button');
    if (t) { last = t.dataset.name || t.textContent.trim(); draw(); }
  }, true);
  addEventListener('DOMContentLoaded', function () { document.body.appendChild(badge); draw(); });
  window.fixture = { rows: function (el, n, label) {
    for (var i = 1; i <= n; i++) {
      var d = document.createElement('p');
      d.textContent = (label || 'Paragraph') + ' ' + i + '. Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore.';
      el.appendChild(d);
    }
  } };
})();
