/*
 * 11.5: مولّد رمز QR صغير بلا مكتبات (وضع البايت، تصحيح أخطاء M، الإصدارات 1–40، اختيار القناع بأقل عقوبة).
 * يُستعمل لعرض رابط التفعيل smartacc://activate?k=… فيمسحه العميل بكاميرا هاتفه فيفتح التطبيق بالرمز مباشرة.
 * الخوارزمية حسب المواصفة ISO/IEC 18004.
 */
(function (root) {
  "use strict";

  // عدد كلمات التصحيح لكل كتلة، وعدد الكتل، لمستوى M (الفهرس = الإصدار)
  var ECC_M = [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28];
  var BLOCKS_M = [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49];

  function rawModules(ver) {
    var r = (16 * ver + 128) * ver + 64;
    if (ver >= 2) {
      var n = Math.floor(ver / 7) + 2;
      r -= (25 * n - 10) * n - 55;
      if (ver >= 7) r -= 36;
    }
    return r;
  }

  function dataCodewords(ver) { return Math.floor(rawModules(ver) / 8) - ECC_M[ver] * BLOCKS_M[ver]; }

  // ---------------------------------------------------------------- Reed-Solomon على GF(256)
  function mul(x, y) {
    var z = 0;
    for (var i = 7; i >= 0; i--) {
      z = (z << 1) ^ ((z >>> 7) * 0x11D);
      z ^= ((y >>> i) & 1) * x;
    }
    return z & 0xFF;
  }

  function rsDivisor(deg) {
    var r = [];
    for (var i = 0; i < deg - 1; i++) r.push(0);
    r.push(1);
    var root = 1;
    for (var k = 0; k < deg; k++) {
      for (var j = 0; j < r.length; j++) {
        r[j] = mul(r[j], root);
        if (j + 1 < r.length) r[j] ^= r[j + 1];
      }
      root = mul(root, 0x02);
    }
    return r;
  }

  function rsRemainder(data, div) {
    var r = div.map(function () { return 0; });
    data.forEach(function (b) {
      var f = b ^ r.shift();
      r.push(0);
      div.forEach(function (c, i) { r[i] ^= mul(c, f); });
    });
    return r;
  }

  // ---------------------------------------------------------------- البناء
  function encode(text) {
    var bytes = Array.from(new TextEncoder().encode(text));
    var ver, cap;
    for (ver = 1; ver <= 40; ver++) {
      cap = dataCodewords(ver) * 8;
      var need = 4 + (ver < 10 ? 8 : 16) + bytes.length * 8;
      if (need <= cap) break;
    }
    if (ver > 40) throw new Error("النص أطول من سعة رمز QR");
    // البتات: وضع البايت 0100، الطول، البيانات، نهاية، حشو
    var bits = [];
    function put(v, n) { for (var i = n - 1; i >= 0; i--) bits.push((v >>> i) & 1); }
    put(4, 4);
    put(bytes.length, ver < 10 ? 8 : 16);
    bytes.forEach(function (b) { put(b, 8); });
    put(0, Math.min(4, cap - bits.length));
    put(0, (8 - bits.length % 8) % 8);
    for (var pad = 0xEC; bits.length < cap; pad ^= 0xEC ^ 0x11) put(pad, 8);
    var data = [];
    for (var i = 0; i < bits.length; i += 8) {
      var v = 0;
      for (var j = 0; j < 8; j++) v = (v << 1) | bits[i + j];
      data.push(v);
    }
    // الكتل والتصحيح والتشبيك
    var nb = BLOCKS_M[ver], ecl = ECC_M[ver], raw = Math.floor(rawModules(ver) / 8);
    var shortCount = nb - raw % nb, shortLen = Math.floor(raw / nb);
    var div = rsDivisor(ecl), blocks = [], k = 0;
    for (var b = 0; b < nb; b++) {
      var dlen = shortLen - ecl + (b < shortCount ? 0 : 1);
      var dat = data.slice(k, k + dlen);
      k += dlen;
      var ec = rsRemainder(dat, div);
      if (b < shortCount) dat.push(0);   // مكان مؤقت ليتساوى الطول
      blocks.push(dat.concat(ec));
    }
    var out = [];
    for (var c = 0; c < blocks[0].length; c++) {
      blocks.forEach(function (bl, bi) {
        if (c !== shortLen - ecl || bi >= shortCount) out.push(bl[c]);
      });
    }
    return draw(ver, out);
  }

  function draw(ver, cw) {
    var size = ver * 4 + 17, m = [], fn = [];
    for (var y = 0; y < size; y++) { m.push(new Array(size).fill(false)); fn.push(new Array(size).fill(false)); }
    function set(x, y, d) { m[y][x] = d; fn[y][x] = true; }
    // أنماط التوقيت
    for (var i = 0; i < size; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0); }
    // أنماط الإيجاد
    function finder(cx, cy) {
      for (var dy = -4; dy <= 4; dy++) for (var dx = -4; dx <= 4; dx++) {
        var d = Math.max(Math.abs(dx), Math.abs(dy)), x = cx + dx, y = cy + dy;
        if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, d !== 2 && d !== 4);
      }
    }
    finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
    // أنماط المحاذاة
    var pos = [];
    if (ver > 1) {
      var n = Math.floor(ver / 7) + 2, step = (ver === 32) ? 26 : Math.ceil((ver * 4 + 4) / (n * 2 - 2)) * 2;
      pos = [6];
      for (var p = size - 7; pos.length < n; p -= step) pos.splice(1, 0, p);
    }
    pos.forEach(function (ax, ai) {
      pos.forEach(function (ay, aj) {
        if ((ai === 0 && aj === 0) || (ai === 0 && aj === pos.length - 1) || (ai === pos.length - 1 && aj === 0)) return;
        for (var dy = -2; dy <= 2; dy++) for (var dx = -2; dx <= 2; dx++) set(ax + dx, ay + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
      });
    });
    // حجز أماكن معلومات الصيغة (تُكتب لاحقًا) والإصدار
    formatBits(m, fn, size, 0, true);
    if (ver >= 7) {
      var rem = ver;
      for (var q = 0; q < 12; q++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
      var vb = (ver << 12) | rem;
      for (var t = 0; t < 18; t++) {
        var bit = ((vb >>> t) & 1) === 1, a = size - 11 + t % 3, bb = Math.floor(t / 3);
        set(a, bb, bit); set(bb, a, bit);
      }
    }
    // البيانات بالتعرج
    var idx = 0;
    for (var right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (var vert = 0; vert < size; vert++) {
        for (var jj = 0; jj < 2; jj++) {
          var x = right - jj, up = ((right + 1) & 2) === 0, y = up ? size - 1 - vert : vert;
          if (!fn[y][x] && idx < cw.length * 8) {
            m[y][x] = ((cw[idx >>> 3] >>> (7 - (idx & 7))) & 1) === 1;
            idx++;
          }
        }
      }
    }
    // أفضل قناع
    var best = null, bestPen = Infinity;
    for (var mask = 0; mask < 8; mask++) {
      var c = m.map(function (r) { return r.slice(); });
      applyMask(c, fn, size, mask);
      formatBits(c, fn, size, mask, false);
      var pen = penalty(c, size);
      if (pen < bestPen) { bestPen = pen; best = c; }
    }
    return best;
  }

  function applyMask(m, fn, size, mask) {
    for (var y = 0; y < size; y++) for (var x = 0; x < size; x++) {
      if (fn[y][x]) continue;
      var inv;
      switch (mask) {
        case 0: inv = (x + y) % 2 === 0; break;
        case 1: inv = y % 2 === 0; break;
        case 2: inv = x % 3 === 0; break;
        case 3: inv = (x + y) % 3 === 0; break;
        case 4: inv = (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0; break;
        case 5: inv = x * y % 2 + x * y % 3 === 0; break;
        case 6: inv = (x * y % 2 + x * y % 3) % 2 === 0; break;
        default: inv = ((x + y) % 2 + x * y % 3) % 2 === 0;
      }
      if (inv) m[y][x] = !m[y][x];
    }
  }

  /** معلومات الصيغة: مستوى M (00) والقناع، مع BCH. reserve=true للحجز فقط. */
  function formatBits(m, fn, size, mask, reserve) {
    var data = (0 << 3) | mask, rem = data;
    for (var i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    var bits = ((data << 10) | rem) ^ 0x5412;
    function s(x, y, b) { m[y][x] = reserve ? false : b; if (reserve) fn[y][x] = true; }
    function g(k) { return ((bits >>> k) & 1) === 1; }
    for (var a = 0; a <= 5; a++) s(8, a, g(a));
    s(8, 7, g(6)); s(8, 8, g(7)); s(7, 8, g(8));
    for (var b = 9; b < 15; b++) s(14 - b, 8, g(b));
    for (var c = 0; c < 8; c++) s(size - 1 - c, 8, g(c));
    for (var d = 8; d < 15; d++) s(8, size - 15 + d, g(d));
    s(8, size - 8, true);   // الوحدة الداكنة دائمًا
    if (reserve) fn[size - 8][8] = true;
  }

  function penalty(m, size) {
    var p = 0, dark = 0;
    function lines(get) {
      for (var a = 0; a < size; a++) {
        var run = 1, prev = get(a, 0), hist = [];
        for (var b = 1; b < size; b++) {
          var v = get(a, b);
          if (v === prev) { run++; if (run === 5) p += 3; else if (run > 5) p++; }
          else { run = 1; prev = v; }
        }
        // نمط شبيه بالإيجاد 1:1:3:1:1 مع أربعة فاتحة
        for (var c = 0; c + 10 < size; c++) {
          var pat = [true, false, true, true, true, false, true];
          var ok = true;
          for (var k = 0; k < 7; k++) if (get(a, c + k) !== pat[k]) { ok = false; break; }
          if (!ok) continue;
          var before = c >= 4 && !get(a, c - 1) && !get(a, c - 2) && !get(a, c - 3) && !get(a, c - 4);
          var after = c + 10 < size && !get(a, c + 7) && !get(a, c + 8) && !get(a, c + 9) && !get(a, c + 10);
          if (before || after) p += 40;
        }
      }
    }
    lines(function (a, b) { return m[a][b]; });
    lines(function (a, b) { return m[b][a]; });
    for (var y = 0; y < size - 1; y++) for (var x = 0; x < size - 1; x++) {
      var v = m[y][x];
      if (v === m[y][x + 1] && v === m[y + 1][x] && v === m[y + 1][x + 1]) p += 3;
    }
    for (var yy = 0; yy < size; yy++) for (var xx = 0; xx < size; xx++) if (m[yy][xx]) dark++;
    var total = size * size, kk = Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1;
    p += Math.max(0, kk) * 10;
    return p;
  }

  /** يرسم المصفوفة في لوحة canvas (هامش 4 وحدات). */
  function toCanvas(matrix, scale, doc) {
    var n = matrix.length, q = 4, c = (doc || document).createElement("canvas");
    c.width = c.height = (n + q * 2) * scale;
    var g = c.getContext("2d");
    g.fillStyle = "#fff";
    g.fillRect(0, 0, c.width, c.height);
    g.fillStyle = "#000";
    for (var y = 0; y < n; y++) for (var x = 0; x < n; x++) if (matrix[y][x]) g.fillRect((x + q) * scale, (y + q) * scale, scale, scale);
    return c;
  }

  var api = { encode: encode, toCanvas: toCanvas };
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.QR = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
