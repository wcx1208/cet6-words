import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const HTML_PATH = path.resolve(__dirname, "../../cet6-review/index.html");
const DATA_PATH = path.resolve(__dirname, "../../cet6-review/data.js");

const htmlRaw = fs.readFileSync(HTML_PATH, "utf8");
const dataRaw = fs.existsSync(DATA_PATH) ? fs.readFileSync(DATA_PATH, "utf8") : "const WORDS=[];";

function extractMainScript(html) {
  const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)];
  assert.ok(scripts.length > 0, "index.html must contain inline <script>");
  return scripts[scripts.length - 1][1];
}

function createFakeDom(initialStorage = {}, options = {}) {
  const store = new Map(Object.entries(initialStorage));
  let throwOnSetKeys = new Set(options.throwOnSetKeys || []);

  const localStorage = {
    get length() { return store.size; },
    key(i) { return [...store.keys()][i] ?? null; },
    getItem(k) { return store.has(k) ? store.get(k) : null; },
    setItem(k, v) {
      if (throwOnSetKeys.has(k) || throwOnSetKeys.has("*")) {
        const err = new Error("QuotaExceededError");
        err.name = "QuotaExceededError";
        throw err;
      }
      store.set(String(k), String(v));
    },
    removeItem(k) { store.delete(k); },
    clear() { store.clear(); },
  };

  const sessionStore = new Map();
  const sessionStorage = {
    getItem(k) { return sessionStore.has(k) ? sessionStore.get(k) : null; },
    setItem(k, v) { sessionStore.set(String(k), String(v)); },
    removeItem(k) { sessionStore.delete(k); },
  };

  function makeClassList(el) {
    return {
      _set: new Set(),
      add(...cs) { cs.forEach((c) => this._set.add(c)); },
      remove(...cs) { cs.forEach((c) => this._set.delete(c)); },
      toggle(c, force) {
        if (force === undefined) {
          if (this._set.has(c)) { this._set.delete(c); return false; }
          this._set.add(c); return true;
        }
        if (force) this._set.add(c); else this._set.delete(c);
        return !!force;
      },
      contains(c) { return this._set.has(c); },
    };
  }

  function makeEl(id = "") {
    const styleProps = new Map();
    const attrs = new Map();
    const el = {
      id,
      innerHTML: "",
      textContent: "",
      value: "",
      scrollTop: 0,
      clientHeight: 600,
      firstChild: { textContent: "" },
      classList: makeClassList(),
      attrs,
      style: {
        display: "",
        width: "",
        strokeDashoffset: "",
        setProperty(k, v) { styleProps.set(k, String(v)); },
        removeProperty(k) { styleProps.delete(k); },
        getPropertyValue(k) { return styleProps.get(k) || ""; },
      },
      setAttribute(k, v) { attrs.set(k, String(v)); },
      getAttribute(k) { return attrs.get(k) ?? null; },
      querySelector() { return null; },
      focus() {},
      select() {},
      setSelectionRange() {},
      addEventListener() {},
      closest() { return null; },
    };
    return el;
  }

  const elements = new Map([
    ["main", makeEl("main")],
    ["nav", makeEl("nav")],
    ["modal-root", makeEl("modal-root")],
    ["toast-root", makeEl("toast-root")],
    ["save-fail-bar", makeEl("save-fail-bar")],
    ["top-plan-pill", makeEl("top-plan-pill")],
    ["theme-quick", makeEl("theme-quick")],
    ["imp", makeEl("imp")],
  ]);

  const docEl = makeEl("html");
  const bodyEl = makeEl("body");
  const listeners = new Map();
  const audioCreated = [];

  class FakeAudio {
    constructor(src) {
      this.src = src;
      audioCreated.push(src);
    }
    play() { return Promise.resolve(); }
    pause() {}
  }

  const document = {
    hidden: false,
    documentElement: docEl,
    body: bodyEl,
    activeElement: null,
    getElementById(id) {
      if (!elements.has(id)) elements.set(id, makeEl(id));
      return elements.get(id);
    },
    querySelectorAll() { return []; },
    createElement(tag) { return makeEl(tag); },
    addEventListener(ev, fn) {
      if (!listeners.has(ev)) listeners.set(ev, []);
      listeners.get(ev).push(fn);
    },
    dispatchEvent() {},
    execCommand() { return false; },
  };

  const sandbox = {
    console,
    Math,
    Date,
    Number,
    String,
    Boolean,
    Array,
    Object,
    Map,
    Set,
    JSON,
    RegExp,
    Error,
    Promise,
    encodeURIComponent,
    decodeURIComponent,
    parseInt,
    parseFloat,
    isNaN,
    isFinite,
    localStorage,
    sessionStorage,
    document,
    Audio: FakeAudio,
    CustomEvent: class CustomEvent { constructor(t) { this.type = t; } },
    setTimeout: (fn) => { return 1; },
    clearTimeout: () => {},
    setInterval: () => 1,
    clearInterval: () => {},
    requestAnimationFrame: (fn) => { if (typeof fn === "function") fn(); return 1; },
    navigator: { clipboard: null },
    matchMedia: () => ({ matches: false, addEventListener() {} }),
    addEventListener(ev, fn) {
      if (!listeners.has("win:" + ev)) listeners.set("win:" + ev, []);
      listeners.get("win:" + ev).push(fn);
    },
    removeEventListener() {},
    scrollTo: () => {},
    AndroidAudio: options.AndroidAudio || null,
  };
  sandbox.window = sandbox;

  const ctx = vm.createContext(sandbox);
  // Load minimal sample WORDS unless fullWords requested
  if (options.fullWords) {
    vm.runInContext(dataRaw, ctx);
  } else {
    vm.runInContext(`const WORDS = [
      { w: "abandon", i: "/əˈbændən/", m: "v. 放弃；抛弃；n. 放任", t: "C", c: "动词", imp: 3, en: "to leave completely", ex: "Never abandon hope.", exm: "永不放弃希望。", cf: [["abundant","形近"]] },
      { w: "ability", i: "/əˈbɪləti/", m: "n. 能力；才能", t: "C", c: "名词", imp: 3, en: "power or skill", ex: "She has the ability to succeed.", exm: "她有成功的能力。" },
      { w: "absent", i: "/ˈæbsənt/", m: "adj. 缺席的；v. 缺席", t: "B", c: "形容词", imp: 2, en: "not present", ex: "He was absent yesterday.", exm: "他昨天缺席了。" },
      { w: "absolute", i: "/ˈæbsəluːt/", m: "adj. 绝对的；完全的", t: "T", c: "形容词", imp: 2, en: "total and complete", ex: "Absolute silence filled the room.", exm: "房间里一片寂静。" },
      { w: "absorb", i: "/əbˈzɔːrb/", m: "v. 吸收；吸引", t: "J", c: "动词", imp: 3, en: "to take in", ex: "Plants absorb water.", exm: "植物吸收水分。" }
    ];`, ctx);
  }
  vm.runInContext(extractMainScript(htmlRaw), ctx);

  return {
    ctx,
    store,
    elements,
    docEl,
    bodyEl,
    audioCreated,
    setThrowOnSetKeys(keys) { throwOnSetKeys = new Set(keys); },
  };
}

test("FSRS-6: 连续 Hard 曲线打破 iv===1 自锁并合理递增 (1->2->3->4->5...)", () => {
  const { ctx } = createFakeDom();
  let s = null;
  let now = 1_700_000_000_000;
  const ivs = [];
  for (let i = 0; i < 8; i++) {
    assert.equal(ctx.nextIntervalLabel(1, s), (() => {
      const s2 = ctx.next(s, 1);
      return s2.iv <= 1 ? "明天" : `${s2.iv}天后`;
    })());
    s = ctx.next(s, 1, now);
    ivs.push(s.iv);
    now += Math.max(1, s.iv) * 86_400_000;
  }
  assert.deepEqual(ivs.slice(0, 5), [1, 2, 3, 4, 5]);
  for (let i = 1; i < ivs.length; i++) {
    assert.ok(ivs[i] > ivs[i - 1], `Expected iv[${i}]=${ivs[i]} > iv[${i - 1}]=${ivs[i - 1]}`);
  }
});

test("FSRS-6: 连续 Again 保持 10 分钟重学与 wCnt 递增", () => {
  const { ctx } = createFakeDom();
  let s = null;
  let now = 1_700_000_000_000;
  for (let i = 1; i <= 5; i++) {
    assert.equal(ctx.nextIntervalLabel(0, s), "10分钟后");
    s = ctx.next(s, 0, now);
    assert.equal(s.iv, 0);
    assert.equal(s.g, 0);
    assert.equal(s.wCnt, i);
    assert.equal(s.due, now + 10 * 60_000);
    now += 10 * 60_000;
  }
});

test("FSRS-6: Good 与 Easy 曲线平稳增长且受软上限约束，并在 iv=180 处饱和", () => {
  const { ctx } = createFakeDom();

  // Good curve
  let sg = null;
  let now = 1_700_000_000_000;
  const goodIvs = [];
  for (let i = 0; i < 6; i++) {
    sg = ctx.next(sg, 2, now);
    goodIvs.push(sg.iv);
    now += Math.max(1, sg.iv) * 86_400_000;
  }
  assert.equal(goodIvs[0], 3);
  assert.ok(goodIvs[1] > goodIvs[0] && goodIvs[2] > goodIvs[1]);
  assert.equal(goodIvs[goodIvs.length - 1], 180);

  // Easy curve with soft cap (no 11x jump from 7 -> 78)
  let se = null;
  now = 1_700_000_000_000;
  const easyIvs = [];
  for (let i = 0; i < 6; i++) {
    const prevIv = se ? se.iv : 0;
    se = ctx.next(se, 3, now);
    easyIvs.push(se.iv);
    if (prevIv > 0) {
      assert.ok(se.iv <= Math.round(prevIv * 3.5) + 1, `Easy jump too large: ${prevIv} -> ${se.iv}`);
    }
    now += Math.max(1, se.iv) * 86_400_000;
  }
  assert.equal(easyIvs[0], 7);
  assert.ok(easyIvs[1] <= 26, `Second Easy should be capped smoothly, got ${easyIvs[1]}`);
  assert.equal(easyIvs[easyIvs.length - 1], 180);
  assert.ok(se.s <= 365);
  assert.ok(se.d >= 1 && se.d <= 10);
});

test("旧版 SRS/CFG 迁移兼容：缺少 s/d/lr 的旧记录与 cfg.wallImg 自动迁移", () => {
  const oldSrs = {
    abandon: { iv: 5, e: 2.3, ease: 2.3, due: Date.now() + 86_400_000, r: 3, wCnt: 0, star: false, g: 2 },
    ghost: { iv: 0, e: 2.5, due: 0, r: 0, wCnt: 0, star: false, g: 1 },
  };
  const oldCfg = { daily: 30, wall: "custom", wallImg: "data:image/jpeg;base64,OLDIMG" };
  const { ctx, store, docEl } = createFakeDom({
    srs: JSON.stringify(oldSrs),
    cfg: JSON.stringify(oldCfg),
  });

  // Empty ghost shell removed
  assert.equal(ctx.S.ghost, undefined);
  // Legacy card migrated cleanly on next()
  const updated = ctx.next(ctx.S.abandon, 2);
  assert.ok(Number.isFinite(updated.iv) && updated.iv >= 5);
  assert.ok(Number.isFinite(updated.s) && updated.s > 0);
  assert.ok(Number.isFinite(updated.d) && updated.d >= 1 && updated.d <= 10);

  // Legacy wallImg migrated to localStorage.wall_img and deleted from CFG
  assert.equal(store.get("wall_img"), "data:image/jpeg;base64,OLDIMG");
  assert.equal("wallImg" in ctx.CFG, false);
  assert.equal(docEl.classList.contains("wall-custom"), true);
});

test("P0-2 壁纸持久化：custom 壁纸基于 localStorage.wall_img 生效，失败分支不写回 CFG.wallImg", () => {
  const { ctx, docEl } = createFakeDom({
    cfg: JSON.stringify({ wall: "custom", wallA: 0.7, uiA: 0.75 }),
    wall_img: "data:image/jpeg;base64,TESTWALL",
  });
  assert.equal(docEl.classList.contains("wall-custom"), true);
  assert.equal(docEl.style.getPropertyValue("--wall-img"), 'url("data:image/jpeg;base64,TESTWALL")');
  assert.equal("wallImg" in ctx.CFG, false);

  // When wall_img missing, gracefully falls back without wall-custom
  const env2 = createFakeDom({
    cfg: JSON.stringify({ wall: "custom" }),
  });
  assert.equal(env2.docEl.classList.contains("wall-custom"), false);
  assert.equal(env2.docEl.style.getPropertyValue("--wall-img"), "");
});

test("P1-5 safeLoad 损坏缓存保留与设置页高亮导出按钮", () => {
  const corruptRaw = '{"abandon":{"iv":3';
  const { ctx, store, elements } = createFakeDom({ srs: corruptRaw });
  assert.equal(vm.runInContext("storageRecovered", ctx), true);
  assert.equal(store.has("srs"), false);

  const corruptKeys = [...store.keys()].filter((k) => k.startsWith("srs_corrupt_"));
  assert.equal(corruptKeys.length, 1);
  assert.equal(store.get(corruptKeys[0]), corruptRaw);

  ctx.go("set");
  const mainHtml = elements.get("main").innerHTML;
  assert.match(mainHtml, /btn-export-corrupt/);
  assert.match(mainHtml, /导出损坏缓存副本/);

  ctx.exportCorruptBackup();
  const impVal = elements.get("imp").value;
  assert.equal(JSON.parse(impVal)[corruptKeys[0]], corruptRaw);
});

test("P1-5 saveFailed 常驻红色告警条：写入失败挂起，仅在对应写入恢复成功后自动清除", () => {
  const env = createFakeDom();
  const { ctx, elements, setThrowOnSetKeys } = env;

  setThrowOnSetKeys(["srs"]);
  assert.equal(ctx.saveSrsOnly(), false);
  assert.equal(ctx.saveFailed, true);
  const bar = elements.get("save-fail-bar");
  assert.equal(bar.style.display, "");
  assert.match(bar.innerHTML, /save-fail-bar/);
  assert.match(bar.innerHTML, /立即导出备份/);

  // Saving hist must NOT clear saveFailed while srs is still failed
  assert.equal(ctx.saveHistOnly(), true);
  assert.equal(ctx.saveFailed, true);
  assert.notEqual(bar.innerHTML, "");

  // Restoring storage and saving srs clears saveFailed and banner
  setThrowOnSetKeys([]);
  assert.equal(ctx.saveSrsOnly(), true);
  assert.equal(ctx.saveFailed, false);
  assert.equal(bar.style.display, "none");
  assert.equal(bar.innerHTML, "");
});

test("P1-6 安全原子导入 importP()：拦截畸形/越界/原型污染数据，二次确认后自动备份再覆盖", () => {
  const initialSrs = { abandon: { iv: 4, e: 2.5, ease: 2.5, due: 1700000000000, r: 2, wCnt: 0, star: false, g: 2 } };
  const env = createFakeDom({
    srs: JSON.stringify(initialSrs),
    hist: JSON.stringify({ "Mon Nov 10 2026": 12 }),
  });
  const { ctx, store, elements } = env;

  // Reject malformed / malicious payloads without mutating S/H/CFG
  const badPayloads = [
    "",
    "{bad json",
    "[]",
    JSON.stringify({ srs: null }),
    JSON.stringify({ srs: { foo: { iv: "abc" } } }),
    JSON.stringify({ srs: { foo: { iv: 3, d: 99 } } }),
    JSON.stringify({ srs: { foo: { iv: -5 } } }),
    JSON.stringify({ srs: { foo: { iv: 3 } }, hist: { today: "oops" } }),
    '{"srs":{"__proto__":{"iv":3}}}',
  ];
  for (const p of badPayloads) {
    assert.equal(ctx.importP(p), false, `Should reject payload: ${p}`);
    assert.equal(ctx.S.abandon.iv, 4);
    assert.equal(ctx.S.foo, undefined);
    assert.equal(elements.get("modal-root").innerHTML, "");
  }

  // Valid payload opens confirmation modal and does NOT mutate S yet
  const validBackup = JSON.stringify({
    srs: {
      ability: { iv: 10, s: 10, d: 4.5, due: 1700500000000, r: 4, wCnt: 0, star: true, g: 2 },
    },
    hist: { "Tue Nov 11 2026": 25 },
    cfg: { daily: 45, evilKey: "XSS", __proto__: { polluted: true } },
    tier: "B",
    mode: "quiz",
  });
  assert.equal(ctx.importP(validBackup), true);
  assert.match(elements.get("modal-root").innerHTML, /btn-confirm-import/);
  assert.match(elements.get("modal-root").innerHTML, /backup_pre_import/);
  // Before confirmation, S is still untouched
  assert.equal(ctx.S.abandon.iv, 4);
  assert.equal(ctx.S.ability, undefined);

  // Cancel preserves S
  ctx.cancelImportP();
  assert.equal(ctx.S.abandon.iv, 4);

  // Confirm backs up current progress and atomically replaces S/H/CFG (whitelist only)
  assert.equal(ctx.importP(validBackup), true);
  ctx.confirmImportP();
  assert.equal(ctx.S.abandon, undefined);
  assert.equal(ctx.S.ability.iv, 10);
  assert.equal(ctx.H["Tue Nov 11 2026"], 25);
  assert.equal(ctx.CFG.daily, 45);
  assert.equal("evilKey" in ctx.CFG, false);
  assert.equal(({}).polluted, undefined);

  const preBackup = JSON.parse(store.get("backup_pre_import"));
  assert.equal(preBackup.srs.abandon.iv, 4);
});

test("P2-7 联网兜底发音开关与 Android 满包重启 #pack-bar 状态", () => {
  let nativePackCnt = 5;
  const mockAndroid = {
    play: () => false,
    stop: () => {},
    setDark: () => {},
    sentencePlay: () => false,
    cacheSentence: () => {},
    packCount: () => nativePackCnt,
    downloadPack: () => {},
    packStatus: () => JSON.stringify({ state: "idle", percent: -1, unzip: 0, count: nativePackCnt, error: "" }),
  };

  const { ctx, elements, audioCreated } = createFakeDom({}, { AndroidAudio: mockAndroid });
  // Default onlineAudio is true
  assert.equal(ctx.CFG.onlineAudio, true);
  ctx.play("abandon");
  assert.equal(audioCreated.length, 1);

  // Turn off onlineAudio -> strictly offline, no Audio() network request
  ctx.CFG.onlineAudio = false;
  ctx.play("ability");
  ctx.playLongSentence("This is a long test sentence that is not cached locally.");
  ctx.playTts("absent");
  assert.equal(audioCreated.length, 1);

  // #pack-bar after full pack restart (state="idle", percent=-1, count>=SENT_TOTAL)
  nativePackCnt = 5; // matches sample WORDS.length === 5
  ctx.go("set");
  const setHtml = elements.get("main").innerHTML;
  assert.match(setHtml, /width:100%/);
  assert.match(setHtml, /全部例句已离线/);
  assert.match(setHtml, /allowBackup/);
});

test("v3.8 静态卫生检查：零全角空格 \\u3000、零外部 CDN/字体依赖、关键 SVG/胶囊修复生效", () => {
  assert.equal([...htmlRaw.matchAll(/\u3000/g)].length, 0, "index.html must contain zero literal \\u3000");
  const urls = [...new Set([...htmlRaw.matchAll(/https?:\/\/[^\s"'`<>)]+/g)].map((m) => m[0]))];
  for (const u of urls) {
    assert.ok(
      u.startsWith("https://fanyi.baidu.com/") ||
      u.startsWith("https://dict.youdao.com/") ||
      u.startsWith("https://github.com/wcx1208/cet6-words/"),
      `Unexpected external URL: ${u}`
    );
  }
  assert.equal(htmlRaw.includes('fill="var(--surf-rgb)"'), false, "Milestone SVG must not use bare rgb triplet in fill");

  const { ctx, elements } = createFakeDom();
  const meaningHtml = ctx.renderMeaning("v. 放弃；n. 放任");
  assert.ok(meaningHtml.includes("pos-v") && meaningHtml.includes("pos-n"));
  assert.equal(meaningHtml.includes("white-space:nowrap"), false);

  ctx.go("stat");
  const statHtml = elements.get("main").innerHTML;
  assert.match(statHtml, /data-lv="1"/);
  assert.match(statHtml, /data-lv="4"/);

  // drillGrade multi-round completion check
  ctx.go("trans");
  ctx.drillSetTag("中国文化");
  const len = ctx.eval ? 0 : vm.runInContext("drillOrder.length", ctx);
  assert.ok(len > 1);
  for (let i = 0; i < len - 1; i++) {
    ctx.drillGrade(2);
    assert.equal(vm.runInContext("drillRound.ok", ctx), i + 1);
  }
  ctx.drillGrade(2);
  assert.equal(vm.runInContext("drillRound.ok", ctx), 0, "drillRound should reset after completing full round");
});
