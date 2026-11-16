/* Small QR code encoder (byte mode, error correction level M, versions 1-40).
 * Follows the ISO/IEC 18004 construction as described by Project Nayuki's
 * reference implementation. QR.matrix(text) -> array of rows of booleans,
 * QR.svg(text, size) -> SVG markup string. */
(function (root) {
  'use strict';
  var ECC_M = [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28];
  var BLOCKS_M = [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49];

  function utf8(s) {
    var out = [];
    var e = unescape(encodeURIComponent(s));
    for (var i = 0; i < e.length; i++) out.push(e.charCodeAt(i));
    return out;
  }
  function rawModules(v) {
    var r = (16 * v + 128) * v + 64;
    if (v >= 2) {
      var n = Math.floor(v / 7) + 2;
      r -= (25 * n - 10) * n - 55;
      if (v >= 7) r -= 36;
    }
    return r;
  }
  function dataCodewords(v) { return Math.floor(rawModules(v) / 8) - ECC_M[v] * BLOCKS_M[v]; }
  function gfMul(x, y) {
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
    for (i = 0; i < deg; i++) {
      for (var j = 0; j < r.length; j++) {
        r[j] = gfMul(r[j], root);
        if (j + 1 < r.length) r[j] ^= r[j + 1];
      }
      root = gfMul(root, 2);
    }
    return r;
  }
  function rsRemainder(data, div) {
    var r = div.map(function () { return 0; });
    data.forEach(function (b) {
      var f = b ^ r.shift();
      r.push(0);
      div.forEach(function (c, i) { r[i] ^= gfMul(c, f); });
    });
    return r;
  }
  function alignPositions(v, size) {
    if (v === 1) return [];
    var n = Math.floor(v / 7) + 2;
    var step = v === 32 ? 26 : Math.ceil((v * 4 + 4) / (n * 2 - 2)) * 2;
    var res = [6];
    for (var pos = size - 7; res.length < n; pos -= step) res.splice(1, 0, pos);
    return res;
  }

  function matrix(text) {
    var bytes = utf8(String(text));
    var v, cap;
    for (v = 1; v <= 40; v++) {
      cap = dataCodewords(v) * 8;
      if (4 + (v <= 9 ? 8 : 16) + bytes.length * 8 <= cap) break;
    }
    if (v > 40) throw new Error('text too long for a QR code');
    // Bit stream
    var bits = [];
    function put(val, len) { for (var i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1); }
    put(4, 4);
    put(bytes.length, v <= 9 ? 8 : 16);
    bytes.forEach(function (b) { put(b, 8); });
    put(0, Math.min(4, cap - bits.length));
    put(0, (8 - bits.length % 8) % 8);
    for (var pad = 0xEC; bits.length < cap; pad ^= 0xEC ^ 0x11) put(pad, 8);
    var data = [];
    for (var i = 0; i < bits.length; i += 8) {
      var b = 0;
      for (var k = 0; k < 8; k++) b = (b << 1) | bits[i + k];
      data.push(b);
    }
    // Error correction and interleaving
    var nBlocks = BLOCKS_M[v], eccLen = ECC_M[v], raw = Math.floor(rawModules(v) / 8);
    var nShort = nBlocks - raw % nBlocks, shortLen = Math.floor(raw / nBlocks);
    var div = rsDivisor(eccLen), blocks = [];
    for (i = 0, k = 0; i < nBlocks; i++) {
      var dat = data.slice(k, k + shortLen - eccLen + (i < nShort ? 0 : 1));
      k += dat.length;
      var ecc = rsRemainder(dat, div);
      if (i < nShort) dat.push(0);
      blocks.push(dat.concat(ecc));
    }
    var codewords = [];
    for (i = 0; i < blocks[0].length; i++) {
      for (var j = 0; j < blocks.length; j++) {
        if (i !== shortLen - eccLen || j >= nShort) codewords.push(blocks[j][i]);
      }
    }
    // Function patterns
    var size = v * 4 + 17, m = [], fn = [];
    for (i = 0; i < size; i++) { m.push(new Array(size).fill(false)); fn.push(new Array(size).fill(false)); }
    function setF(x, y, d) { m[y][x] = d; fn[y][x] = true; }
    for (i = 0; i < size; i++) { setF(6, i, i % 2 === 0); setF(i, 6, i % 2 === 0); }
    [[3, 3], [size - 4, 3], [3, size - 4]].forEach(function (c) {
      for (var dy = -4; dy <= 4; dy++) for (var dx = -4; dx <= 4; dx++) {
        var d = Math.max(Math.abs(dx), Math.abs(dy)), x = c[0] + dx, y = c[1] + dy;
        if (x >= 0 && x < size && y >= 0 && y < size) setF(x, y, d !== 2 && d !== 4);
      }
    });
    var al = alignPositions(v, size), na = al.length;
    for (i = 0; i < na; i++) for (j = 0; j < na; j++) {
      if ((i === 0 && j === 0) || (i === 0 && j === na - 1) || (i === na - 1 && j === 0)) continue;
      for (var dy2 = -2; dy2 <= 2; dy2++) for (var dx2 = -2; dx2 <= 2; dx2++) {
        setF(al[i] + dx2, al[j] + dy2, Math.max(Math.abs(dx2), Math.abs(dy2)) !== 1);
      }
    }
    function drawFormat(mask) {
      var d = (0 << 3) | mask, r = d; // level M = 0
      for (var t = 0; t < 10; t++) r = (r << 1) ^ ((r >>> 9) * 0x537);
      var fb = ((d << 10) | r) ^ 0x5412;
      function g(n) { return ((fb >>> n) & 1) !== 0; }
      for (var q = 0; q <= 5; q++) setF(8, q, g(q));
      setF(8, 7, g(6)); setF(8, 8, g(7)); setF(7, 8, g(8));
      for (q = 9; q < 15; q++) setF(14 - q, 8, g(q));
      for (q = 0; q < 8; q++) setF(size - 1 - q, 8, g(q));
      for (q = 8; q < 15; q++) setF(8, size - 15 + q, g(q));
      setF(8, size - 8, true);
    }
    drawFormat(0);
    if (v >= 7) {
      var r2 = v;
      for (i = 0; i < 12; i++) r2 = (r2 << 1) ^ ((r2 >>> 11) * 0x1F25);
      var vb = (v << 12) | r2;
      for (i = 0; i < 18; i++) {
        var bit = ((vb >>> i) & 1) !== 0, a = size - 11 + i % 3, bb = Math.floor(i / 3);
        setF(a, bb, bit); setF(bb, a, bit);
      }
    }
    // Data modules in the zigzag order
    var idx = 0;
    for (var right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (var vert = 0; vert < size; vert++) for (j = 0; j < 2; j++) {
        var x = right - j, up = ((right + 1) & 2) === 0, y = up ? size - 1 - vert : vert;
        if (!fn[y][x] && idx < codewords.length * 8) {
          m[y][x] = ((codewords[idx >>> 3] >>> (7 - (idx & 7))) & 1) !== 0;
          idx++;
        }
      }
    }
    function maskHit(mask, x, y) {
      switch (mask) {
        case 0: return (x + y) % 2 === 0;
        case 1: return y % 2 === 0;
        case 2: return x % 3 === 0;
        case 3: return (x + y) % 3 === 0;
        case 4: return (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0;
        case 5: return x * y % 2 + x * y % 3 === 0;
        case 6: return (x * y % 2 + x * y % 3) % 2 === 0;
        default: return ((x + y) % 2 + x * y % 3) % 2 === 0;
      }
    }
    function applyMask(mask) {
      for (var yy = 0; yy < size; yy++) for (var xx = 0; xx < size; xx++) {
        if (!fn[yy][xx] && maskHit(mask, xx, yy)) m[yy][xx] = !m[yy][xx];
      }
    }
    function penalty() {
      var p = 0, dark = 0, yy, xx;
      for (yy = 0; yy < size; yy++) {
        var runR = 1, runC = 1;
        for (xx = 0; xx < size; xx++) {
          if (m[yy][xx]) dark++;
          if (xx > 0) {
            if (m[yy][xx] === m[yy][xx - 1]) { runR++; if (runR === 5) p += 3; else if (runR > 5) p++; } else runR = 1;
            if (m[xx][yy] === m[xx - 1][yy]) { runC++; if (runC === 5) p += 3; else if (runC > 5) p++; } else runC = 1;
          }
          if (xx > 0 && yy > 0) {
            var c = m[yy][xx];
            if (c === m[yy - 1][xx] && c === m[yy][xx - 1] && c === m[yy - 1][xx - 1]) p += 3;
          }
        }
      }
      var total = size * size;
      p += Math.max(0, Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1) * 10;
      return p;
    }
    var best = 0, bestP = Infinity;
    for (var mk = 0; mk < 8; mk++) {
      applyMask(mk); drawFormat(mk);
      var pp = penalty();
      if (pp < bestP) { bestP = pp; best = mk; }
      applyMask(mk);
    }
    applyMask(best); drawFormat(best);
    return m;
  }

  function svg(text, px, fg, bg) {
    var m = matrix(text), n = m.length, q = 4, s = n + q * 2, d = '';
    for (var y = 0; y < n; y++) for (var x = 0; x < n; x++) {
      if (m[y][x]) d += 'M' + (x + q) + ' ' + (y + q) + 'h1v1h-1z';
    }
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + s + ' ' + s + '" width="' + (px || 200) +
      '" height="' + (px || 200) + '" shape-rendering="crispEdges"><rect width="100%" height="100%" fill="' +
      (bg || '#fff') + '"/><path d="' + d + '" fill="' + (fg || '#000') + '"/></svg>';
  }

  var QR = { matrix: matrix, svg: svg };
  if (typeof module !== 'undefined' && module.exports) module.exports = QR;
  else root.QR = QR;
})(this);
