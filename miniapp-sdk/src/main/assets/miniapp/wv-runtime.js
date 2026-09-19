/* GenUI 小程序 WebView 通道逻辑层运行时（wv-runtime.js）
 * 职责：Page()/App() 注册、wx: 指令展开、{{}} 绑定、事件分发、setData 重渲染、
 *       wx API（fetch/localStorage 原生能力 + GSBridge 原生桥）、hash 路由、跨引擎切换。
 * 与微信 AppService 的差异：框架与业务同上下文（绕开序列化桥），单文档页共享渲染实例。 */
(function () {
  "use strict";
  var PAGES = {};        // path -> {html, jsSrc, wxss, renderer, page:null}
  var CURRENT = "";      // 当前页 path
  var PKG_ROOT = "";
  var appInst = { globalData: {} };

  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  // ---------- 表达式求值（{{}} / wx:for / wx:if） ----------
  function evalExpr(expr, data) {
    try {
      var f = new Function("d", "with(d){try{return (" + expr + ")}catch(e){return undefined}}");
      return f(data || {});
    } catch (e) { return undefined; }
  }
  function interpolate(text, data) {
    return String(text).replace(/\{\{([\s\S]*?)\}\}/g, function (_, ex) {
      var v = evalExpr(ex.trim(), data);
      return v == null ? "" : v;
    });
  }
  function truthy(expr, data) { return !!evalExpr(expr, data); }

  // ---------- wx: 指令展开（DOM 树递归） ----------
  function expandNode(node, data, scope) {
    if (node.nodeType !== 1) return null;
    var el = document.createElement(node.tagName);
    for (var i = 0; i < node.attributes.length; i++) {
      var a = node.attributes[i];
      if (a.name.indexOf("data-gs-wx-") === 0) continue;    // 指令不落到最终 DOM
      if (a.name === "data-gs-src") {
        el.setAttribute("src", resolveSrc(interpolate(a.value, data))); continue;
      }
      el.setAttribute(a.name, interpolate(a.value, data));
    }
    // wx:for
    var forExpr = node.getAttribute("data-gs-wx-for");
    if (forExpr) {
      var itemName = node.getAttribute("data-gs-wx-for-item") || "item";
      var idxName = node.getAttribute("data-gs-wx-for-index") || "index";
      var list = evalExpr(forExpr.trim(), data) || [];
      for (var k = 0; k < list.length; k++) {
        var sub = Object.create(data || {});
        sub[itemName] = list[k]; sub[idxName] = k;
        if (scope) scope[subKey(node, k)] = sub;
        var c = node.getAttribute("data-gs-wx-if");
        if (!c || truthy(c, sub)) {
          var clone = expandChildrenShallow(node, sub);
          for (var m = 0; m < clone.length; m++) el.appendChild(clone[m]);
        }
      }
      return el;
    }
    // wx:if / elif / else（同层兄弟链在 expandSiblings 处理，这里只处理自身）
    var ifExpr = node.getAttribute("data-gs-wx-if");
    if (ifExpr && !truthy(ifExpr, data)) return null;
    var kids = expandChildrenShallow(node, data);
    for (var j = 0; j < kids.length; j++) el.appendChild(kids[j]);
    return el;
  }

  // 处理 wx:elif/wx:else 兄弟链：命中后短路剩余
  function expandSiblings(nodes, data, out) {
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (n.nodeType !== 1) {
        var t = interpolate(n.textContent || "", data);
        if (t.trim() !== "") out.push(document.createTextNode(t));
        continue;
      }
      var elif = n.getAttribute("data-gs-wx-elif");
      var els = n.getAttribute("data-gs-wx-else");
      if ((elif || els) && out.__hitBranch) continue;        // 前面分支已命中
      var ok = true;
      if (elif) ok = truthy(elif.trim(), data);
      else if (els) ok = true;
      else ok = condOf(n, data);
      if (!ok) continue;
      var done = expandNode(n, data, null);
      if (done) out.push(done);
      out.__hitBranch = true;
    }
  }
  function condOf(n, data) {
    var f = n.getAttribute("data-gs-wx-for");
    if (f) return true;                                       // for 节点恒渲染容器
    var iff = n.getAttribute("data-gs-wx-if");
    return !iff || truthy(iff.trim(), data);
  }
  function subKey(node, k) { return "__f" + idx(node) + "_" + k; }
  function idx(n) { return Array.prototype.indexOf.call(n.parentNode ? n.parentNode.children : [], n); }
  function resolveSrc(v) {
    if (!v || /^(https?:|data:)/.test(v)) return v;
    return PKG_ROOT + "/" + String(v).replace(/^\//, "");
  }

  function expandChildrenShallow(node, data) {
    var out = [];
    out.__hitBranch = false;
    expandSiblings(node.childNodes, data, out);
    return out;
  }

  // ---------- 渲染 ----------
  function render(page) {
    var tpl = document.createElement("div");
    tpl.innerHTML = page.html;
    var root = document.getElementById("app");
    root.innerHTML = "";
    // 页面样式：wxss 按页注入 <style>
    ensureStyle(page.path, page.wxss);
    var frag = document.createDocumentFragment();
    var tmp = document.createElement("div");
    tmp.innerHTML = tpl.innerHTML;
    expandSiblings(Array.prototype.slice.call(tmp.childNodes), page.page ? page.page.data : {}, frag);
    root.appendChild(frag);
  }

  var styleBook = {};
  function ensureStyle(path, wxss) {
    if (!wxss || styleBook[path]) return;
    styleBook[path] = true;
    var s = document.createElement("style");
    s.textContent = wxss;
    document.head.appendChild(s);
  }

  // ---------- 事件（委托到 #app） ----------
  document.addEventListener("click", function (ev) {
    var el = ev.target;
    while (el && el !== document.body) {
      var h = el.getAttribute && el.getAttribute("data-gs-on-tap");
      if (h) {
        ev.stopPropagation();
        fire(h, el, "tap");
        return;
      }
      el = el.parentNode;
    }
  }, true);
  document.addEventListener("input", function (ev) {
    var el = ev.target;
    while (el && el !== document.body) {
      var h = el.getAttribute && el.getAttribute("data-gs-on-input");
      if (h) { fire(h, el, "input", el.value); return; }
      el = el.parentNode;
    }
  }, true);
  function fire(handler, el, type, value) {
    var p = PAGES[CURRENT];
    if (!p || !p.page) return;
    var fn = handler.split(".").reduce(function (o, k) { return o ? o[k] : null; }, p.page);
    if (typeof fn === "function") {
      var evObj = { type: type, target: { dataset: el.dataset || {} }, detail: { value: value } };
      try { fn.call(p.page, evObj); }
      catch (e) { console.error("handler error:", handler, e); }
    }
  }

  // ---------- wx API ----------
  var wx = {
    navigateTo: function (o) {
      var page = (o && o.url || "").replace(/^.*page(s)?\//, "pages/").replace(/\.wxml$/, "");
      var target = /^pages\//.test(o.url) ? o.url.split("?")[0] : PAGES[CURRENT] ? findPage(o.url) : "";
      if (target && PAGES[target] && PAGES[target].renderer === "skyline") {
        GSBridge.post(JSON.stringify({ api: "switchEngine", args: { page: target } }));
        return;
      }
      if (target && PAGES[target]) { location.hash = "#/" + target; return; }
      console.warn("navigateTo: page not found", o && o.url);
    },
    redirectTo: function (o) { wx.navigateTo(o); },
    navigateBack: function () { GSBridge.post(JSON.stringify({ api: "navigateBack", args: {} })); },
    setNavigationBarTitle: function (o) {
      GSBridge.post(JSON.stringify({ api: "setTitle", args: { v: (o && o.title) || "" } }));
    },
    showToast: function (o) { toast((o && o.title) || ""); },
    hideToast: function () {}, showLoading: function (o) { toast((o && o.title) || "加载中"); },
    hideLoading: function () {}, hideLoadingToast: function () {},
    request: function (o) {
      fetch(o.url, {
        method: (o.method || "GET").toUpperCase(),
        headers: o.header || {},
        body: o.data ? JSON.stringify(o.data) : undefined
      }).then(function (r) { return r.text().then(function (t) {
        var d = null; try { d = JSON.parse(t); } catch (e) { d = t; }
        (o.success || noop)({ data: d, statusCode: r.status });
      }); }).catch(function (e) { (o.fail || noop)({ errMsg: String(e) }); });
    },
    setStorageSync: function (k, v) { try { localStorage.setItem("gs_" + k, JSON.stringify(v)); } catch (e) {} },
    getStorageSync: function (k) {
      try { var s = localStorage.getItem("gs_" + k); return s == null ? "" : JSON.parse(s); } catch (e) { return ""; }
    },
    removeStorageSync: function (k) { try { localStorage.removeItem("gs_" + k); } catch (e) {} },
    getSystemInfoSync: function () {
      return { platform: "android", windowWidth: window.innerWidth, windowHeight: window.innerHeight,
               pixelRatio: window.devicePixelRatio || 1, language: navigator.language };
    }
  };
  function noop() {}
  function toast(t) {
    var d = document.getElementById("gs-toast") || document.createElement("div");
    d.id = "gs-toast";
    d.style.cssText = "position:fixed;left:50%;bottom:80px;transform:translateX(-50%);" +
      "background:rgba(0,0,0,.78);color:#fff;padding:9px 16px;border-radius:8px;font-size:13px;z-index:9999;";
    d.textContent = t;
    document.body.appendChild(d);
    clearTimeout(d.__t); d.__t = setTimeout(function () { d.remove(); }, 1800);
  }
  function findPage(fragment) {
    for (var p in PAGES) if (p.indexOf(fragment) >= 0) return p;
    return "";
  }

  // ---------- Page / App ----------
  window.App = function (o) { appInst = o || {}; if (appInst.globalData == null) appInst.globalData = {}; };
  window.getApp = function () { return appInst; };
  window.Page = function (o) {
    var p = PAGES[CURRENT] || lastRegistered;
    if (!p) return;
    o.data = o.data || {};
    o.setData = function (patch) {
      var d = p.page.data;
      Object.keys(patch || {}).forEach(function (k) {
        // 支持 "a.b" 路径
        var parts = k.split("."), cur = d;
        for (var i = 0; i < parts.length - 1; i++) {
          if (cur[parts[i]] == null) cur[parts[i]] = {};
          cur = cur[parts[i]];
        }
        cur[parts[parts.length - 1]] = patch[k];
      });
      render(p);
    };
    p.page = o;
    if (typeof o.onLoad === "function") o.onLoad({});
    if (typeof o.onShow === "function") o.onShow({});
    render(p);
  };
  var lastRegistered = null;

  // ---------- 页面注册与路由 ----------
  window.GS = {
    setPackageRoot: function (root) { PKG_ROOT = root; },
    registerPage: function (path, html, jsSrc, wxss, renderer) {
      var rec = { path: path, html: html, wxss: wxss, renderer: renderer || "", page: null };
      PAGES[path] = rec;
      lastRegistered = rec;
      if (jsSrc) {
        CURRENT = path;                       // Page() 需要注册上下文
        try { new Function(jsSrc)(); } catch (e) { console.error("page js error:", path, e); }
      }
    },
    start: function () {
      var entry = location.hash ? location.hash.replace(/^#\//, "") : "";
      if (!entry || !PAGES[entry]) {
        for (var p in PAGES) { entry = p; break; }     // app.json pages[0]
      }
      CURRENT = entry;
      var rec = PAGES[entry];
      if (rec && rec.renderer === "skyline") {
        GSBridge.post(JSON.stringify({ api: "switchEngine", args: { page: entry } }));
        return;
      }
      render(rec);
    }
  };
  window.wx = wx;
  window.addEventListener("hashchange", function () {
    var p = location.hash.replace(/^#\//, "");
    if (p && PAGES[p] && p !== CURRENT) {
      CURRENT = p;
      var rec = PAGES[p];
      if (rec.renderer === "skyline") { GSBridge.post(JSON.stringify({ api: "switchEngine", args: { page: p } })); return; }
      render(rec);
    }
  });
  // DOM 就绪后启动（registerPage 全部执行完）
  if (document.readyState === "complete" || document.readyState === "interactive") setTimeout(function () { GS.start(); }, 0);
  else document.addEventListener("DOMContentLoaded", function () { GS.start(); });
})();
