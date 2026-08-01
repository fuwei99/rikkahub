// rikkahub eval_javascript runtime prelude
// Injected into every QuickJS context created by JavascriptTool.
// Keep this file ES2020-safe: no optional catch binding edge cases, no Intl, no timers.

(function () {
  "use strict";

  var G = globalThis;

  // ---------------------------------------------------------------------------
  // Safe serializer: never throws, never loses type information.
  // QuickJS wrapper cannot marshal BigInt / undefined / functions, and plain
  // JSON.stringify dies on circular references. Everything is normalized here,
  // on the JS side, so the Kotlin layer only ever sees a String.
  // ---------------------------------------------------------------------------
  function serialize(value, maxLen) {
    maxLen = maxLen || 20000;
    var seen = [];

    function conv(x) {
      if (x === null) return null;
      var t = typeof x;
      if (t === "undefined") return "\u2039undefined\u203A";
      if (t === "bigint") return x.toString() + "n";
      if (t === "function") return "\u2039Function: " + (x.name || "anonymous") + "\u203A";
      if (t === "symbol") return x.toString();
      if (t === "number") {
        if (Number.isNaN(x)) return "\u2039NaN\u203A";
        if (x === Infinity) return "\u2039Infinity\u203A";
        if (x === -Infinity) return "\u2039-Infinity\u203A";
        return x;
      }
      if (t !== "object") return x;
      if (seen.indexOf(x) >= 0) return "\u2039Circular\u203A";
      if (x instanceof Error) return "\u2039" + x.name + ": " + x.message + "\u203A";
      if (x instanceof Date) return x.toISOString();
      // Decimal and friends stringify losslessly. The constructor name is unreliable
      // because minification renames it, so test the prototype chain instead.
      if (typeof G.Decimal === "function" && x instanceof G.Decimal) return x.toString();
      if (typeof x.toString === "function" && typeof x.toFixed === "function" &&
        typeof x.plus === "function" && typeof x.times === "function") {
        return x.toString();
      }
      if (x instanceof Promise) {
        return "\u2039Promise: no event loop in this engine, value unavailable. " +
          "Write synchronous code instead.\u203A";
      }

      seen.push(x);
      var out;
      if (Array.isArray(x)) {
        out = [];
        for (var i = 0; i < x.length; i++) out.push(conv(x[i]));
      } else if (typeof Map !== "undefined" && x instanceof Map) {
        out = {};
        out["\u2039Map\u203A"] = Array.from(x.entries()).map(function (e) {
          return [conv(e[0]), conv(e[1])];
        });
      } else if (typeof Set !== "undefined" && x instanceof Set) {
        out = {};
        out["\u2039Set\u203A"] = Array.from(x.values()).map(conv);
      } else {
        out = {};
        for (var k in x) {
          if (Object.prototype.hasOwnProperty.call(x, k)) out[k] = conv(x[k]);
        }
      }
      seen.pop();
      return out;
    }

    var s;
    try {
      s = JSON.stringify(conv(value), null, 1);
    } catch (e) {
      s = "\u2039unserializable: " + (e && e.message) + "\u203A";
    }
    if (s === undefined) s = "\u2039undefined\u203A";
    if (s.length > maxLen) {
      s = s.slice(0, maxLen) + "\n\u2026(truncated " + (s.length - maxLen) + " chars)";
    }
    return s;
  }

  // ---------------------------------------------------------------------------
  // Error formatting: strip the Kotlin/JNI frames, keep only JS-relevant info.
  // ---------------------------------------------------------------------------
  function formatError(e) {
    if (e === null || e === undefined) return "Error: " + String(e);
    if (typeof e !== "object") return String(e);
    var name = e.name || "Error";
    var msg = e.message || String(e);
    var head = name + ": " + msg;
    var stack = typeof e.stack === "string" ? e.stack : "";
    if (!stack) return head;
    var lines = stack.split("\n").filter(function (l) {
      var s = l.trim();
      if (!s) return false;
      // drop host-side frames, they are noise for the caller
      if (s.indexOf("me.rerere") >= 0) return false;
      if (s.indexOf("JavascriptTool") >= 0) return false;
      if (s.indexOf("invokeSuspend") >= 0) return false;
      if (s.indexOf("kotlin") >= 0) return false;
      return true;
    }).slice(0, 6);
    return lines.length ? head + "\n" + lines.join("\n") : head;
  }

  // ---------------------------------------------------------------------------
  // Runner: indirect eval so that `var` / `function` declarations land on the
  // global object and therefore survive into the next call within a session.
  // Returns the *completion value* of the last expression, like a REPL.
  // ---------------------------------------------------------------------------
  var indirectEval = eval;

  G.__rkRun = function (code, maxLen) {
    try {
      var v = indirectEval(code);
      return JSON.stringify({ ok: true, result: serialize(v, maxLen) });
    } catch (e) {
      return JSON.stringify({ ok: false, error: formatError(e) });
    }
  };

  // ---------------------------------------------------------------------------
  // Small conveniences that QuickJS lacks and scripts constantly want.
  // ---------------------------------------------------------------------------

  // Exact decimal arithmetic helper. decimal.js is loaded before this file.
  if (typeof G.Decimal === "function") {
    G.D = function (v) { return new G.Decimal(v); };
    // Decimal.sum(...) style helper for the most common case
    G.dsum = function () {
      var acc = new G.Decimal(0);
      for (var i = 0; i < arguments.length; i++) {
        var a = arguments[i];
        if (Array.isArray(a)) {
          for (var j = 0; j < a.length; j++) acc = acc.plus(a[j]);
        } else {
          acc = acc.plus(a);
        }
      }
      return acc;
    };
  }

  // Round-half-away-from-zero to n digits, without the toFixed(1.005) bug.
  G.round = function (x, n) {
    n = n === undefined ? 2 : n;
    if (typeof G.Decimal === "function") {
      return new G.Decimal(x).toDecimalPlaces(n, G.Decimal.ROUND_HALF_UP).toNumber();
    }
    var p = Math.pow(10, n);
    return Math.round((x + Number.EPSILON) * p) / p;
  };

  // base64, absent from QuickJS
  (function () {
    var CH = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    G.btoa = function (input) {
      var str = String(input), out = "";
      for (var b = 0, c = 0, i = 0; i < str.length; i++) {
        var ch = str.charCodeAt(i);
        if (ch > 255) throw new Error("btoa: only latin1 characters are supported");
        b = (b << 8) | ch; c += 8;
        while (c >= 6) { c -= 6; out += CH[(b >> c) & 63]; }
      }
      if (c > 0) out += CH[(b << (6 - c)) & 63];
      while (out.length % 4) out += "=";
      return out;
    };
    G.atob = function (input) {
      var str = String(input).replace(/[=\s]/g, ""), out = "";
      for (var b = 0, c = 0, i = 0; i < str.length; i++) {
        var idx = CH.indexOf(str[i]);
        if (idx < 0) throw new Error("atob: invalid character");
        b = (b << 6) | idx; c += 6;
        if (c >= 8) { c -= 8; out += String.fromCharCode((b >> c) & 255); }
      }
      return out;
    };
  })();

  // Deterministic sort helpers. Array#sort defaults to string order, which is
  // a classic source of silently wrong numeric results.
  G.asc = function (a, b) { return a - b; };
  G.desc = function (a, b) { return b - a; };
  G.byKey = function (key, dir) {
    var s = dir === "desc" ? -1 : 1;
    return function (a, b) {
      var x = a[key], y = b[key];
      if (typeof x === "number" && typeof y === "number") return (x - y) * s;
      return String(x).localeCompare(String(y)) * s;
    };
  };
})();
