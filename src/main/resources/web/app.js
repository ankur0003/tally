"use strict";

// ---------------------------------------------------------------- api

const TOKEN_KEY = "tally.token";
let token = (() => { try { return localStorage.getItem(TOKEN_KEY); } catch { return null; } })();

async function api(path, opts = {}) {
  if (!token) { askToken(); throw new Error("no token"); }
  const res = await fetch("/api" + path, {
    ...opts,
    headers: { "X-Tally-Token": token, ...(opts.body ? { "Content-Type": "application/json" } : {}) },
  });
  if (res.status === 403) {
    const body = await res.json().catch(() => ({}));
    if ((body.error || "").includes("X-Tally-Token")) { askToken(); throw new Error("bad token"); }
    throw new Error(body.error || "Forbidden");
  }
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error || res.statusText);
  }
  return res.status === 204 ? null : res.json();
}
const post = (path, body) => api(path, { method: "POST", body: body ? JSON.stringify(body) : undefined });
const put = (path, body) => api(path, { method: "PUT", body: JSON.stringify(body) });

function askToken() {
  const d = document.getElementById("token-dialog");
  if (!d.open) d.showModal();
}
document.getElementById("token-form").addEventListener("submit", () => {
  token = document.getElementById("token-input").value.trim();
  try { localStorage.setItem(TOKEN_KEY, token); } catch {}
  refreshAll();
});

// ---------------------------------------------------------------- helpers

const $ = (id) => document.getElementById(id);
const inr = (n, d = 2) => n == null ? "–" : n.toLocaleString("en-IN", { minimumFractionDigits: d, maximumFractionDigits: d });
const rupees = (n, d = 0) => n == null ? "–" : (n < 0 ? "−₹" : "₹") + inr(Math.abs(n), d);
const signed = (n, d = 2, suffix = "") => n == null ? "–" : (n > 0 ? "▲ +" : n < 0 ? "▼ −" : "") + inr(Math.abs(n), d) + suffix;
const cls = (n) => n > 0 ? "up" : n < 0 ? "down" : "";
const timeOf = (iso) => iso ? new Date(iso).toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", timeZone: "Asia/Kolkata" }) : "";

/** Build a DOM element; text always goes through textContent (API data is untrusted). */
function el(tag, attrs = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === "class") e.className = v;
    else if (k === "text") e.textContent = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v);
  }
  for (const c of children.flat()) if (c != null) e.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return e;
}
const svgEl = (tag, attrs = {}) => {
  const e = document.createElementNS("http://www.w3.org/2000/svg", tag);
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  return e;
};

async function withBusy(btn, fn) {
  btn.disabled = true;
  btn.classList.add("busy");
  const label = btn.textContent;
  btn.textContent = "Working…";
  try { await fn(); } catch (e) { alert(e.message); } finally { btn.disabled = false; btn.classList.remove("busy"); btn.textContent = label; }
}

const tooltip = $("tooltip");
function showTip(x, y, text) {
  tooltip.textContent = text;
  tooltip.hidden = false;
  const r = tooltip.getBoundingClientRect();
  tooltip.style.left = Math.min(x + 12, innerWidth - r.width - 8) + "px";
  tooltip.style.top = (y - r.height - 10) + "px";
}
const hideTip = () => { tooltip.hidden = true; };

// ---------------------------------------------------------------- index tiles + sparklines

const TILE_INDEX = { "NSE:NIFTY 50": "NIFTY", "NSE:NIFTY BANK": "BANKNIFTY", "NSE:NIFTY FIN SERVICE": "FINNIFTY", "NSE:INDIA VIX": "INDIAVIX" };
const charts = {};

function renderTiles(indices) {
  const box = $("tiles");
  if (!indices) { box.replaceChildren(el("div", { class: "card empty", text: "Log in to Kite to see live index data." })); return; }
  box.replaceChildren(...indices.map((t) => {
    const svg = svgEl("svg", { viewBox: "0 0 200 44", preserveAspectRatio: "none", role: "img", "aria-label": `${t.name} intraday trend` });
    const tile = el("div", { class: "tile" },
      el("div", { class: "label", text: t.name }),
      el("div", { class: "value num", text: inr(t.ltp) }),
      el("div", { class: "delta num " + cls(t.change), text: `${signed(t.change)}  (${signed(t.changePct, 2, "%")})` }),
      el("div", { class: "range num", text: `L ${inr(t.low)} · H ${inr(t.high)} · prev ${inr(t.prevClose)}` }),
      svg,
    );
    drawSpark(svg, charts[TILE_INDEX[t.key]], t.prevClose);
    return tile;
  }));
}

function drawSpark(svg, points, prevClose) {
  if (!points || points.length < 2) return;
  const W = 200, H = 44, pad = 3;
  const vals = points.map((p) => p.c).concat(prevClose ? [prevClose] : []);
  const min = Math.min(...vals), max = Math.max(...vals), span = max - min || 1;
  const x = (i) => (i / (points.length - 1)) * W;
  const y = (v) => pad + (1 - (v - min) / span) * (H - 2 * pad);
  if (prevClose) svg.append(svgEl("line", { class: "ref", x1: 0, x2: W, y1: y(prevClose), y2: y(prevClose), "vector-effect": "non-scaling-stroke" }));
  svg.append(svgEl("path", { class: "line", d: points.map((p, i) => `${i ? "L" : "M"}${x(i).toFixed(1)},${y(p.c).toFixed(1)}`).join(""), "vector-effect": "non-scaling-stroke" }));
  const hair = svgEl("line", { class: "hair", y1: 0, y2: H, visibility: "hidden", "vector-effect": "non-scaling-stroke" });
  svg.append(hair);
  // Crosshair: snap to the nearest 5-minute bar, readout in a tooltip.
  svg.addEventListener("pointermove", (ev) => {
    const r = svg.getBoundingClientRect();
    const i = Math.max(0, Math.min(points.length - 1, Math.round(((ev.clientX - r.left) / r.width) * (points.length - 1))));
    hair.setAttribute("x1", x(i)); hair.setAttribute("x2", x(i)); hair.setAttribute("visibility", "visible");
    const p = points[i];
    const vsPrev = prevClose ? `  ${signed(p.c - prevClose)} vs prev` : "";
    showTip(ev.clientX, r.top, `${p.t}  ${inr(p.c)}${vsPrev}`);
  });
  svg.addEventListener("pointerleave", () => { hair.setAttribute("visibility", "hidden"); hideTip(); });
}

async function loadCharts() {
  await Promise.all(Object.values(TILE_INDEX).map(async (idx) => {
    try { charts[idx] = await api(`/index-chart?index=${idx}`); } catch {}
  }));
}

// ---------------------------------------------------------------- global

async function loadGlobal() {
  try {
    const qs = await api("/global");
    $("global").replaceChildren(...qs.map((q) => el("div", { class: "gq", title: `${q.symbol} · ${q.group}` },
      el("span", { class: "name", text: q.name }),
      el("span", { class: "num" },
        el("span", { text: q.price == null ? "n/a" : inr(q.price, q.price > 1000 ? 0 : 2) + " " }),
        el("span", { class: cls(q.changePct), text: q.changePct == null ? "" : signed(q.changePct, 2, "%") }),
      ),
    )));
  } catch (e) { $("global").replaceChildren(el("div", { class: "empty", text: "Global data unavailable: " + e.message })); }
}

// ---------------------------------------------------------------- AI ideas

function renderIdeas(ai) {
  $("minconf").textContent = `Suggestions need ≥${ai.minConfidence}% confidence plus rule checks`;
  $("marketview").textContent = ai.run ? `${timeOf(ai.run.at)} · ${ai.run.marketView}` : "No analysis yet today. Runs every 15 min while the market is open, or press Analyze now.";
  const latest = ai.run ? new Set(ai.run.ideaIds) : new Set();
  const ideas = ai.ideas.filter((i) => i.kind !== "STOCK" && (i.action !== "NO_TRADE" || latest.has(i.id))).slice(0, 10);
  // Below-the-bar ideas get their trade button only in the High-risk section.
  const risky = new Set(ai.highRiskIds || []);
  if (!ideas.length) { $("ideas").replaceChildren(el("div", { class: "empty", text: "No ideas yet." })); }
  else $("ideas").replaceChildren(...ideas.map((i) => ideaCard(i, risky.has(i.id) ? null : ai.plans?.[i.id], busyReason(ai, i))));

  $("stats").replaceChildren(el("table", { class: "t" },
    el("tr", {}, ...["Confidence", "Ideas", "Scored", "Hit", "Stopped", "Expired", "Hit rate"].map((h, i) => el("th", { class: i ? "r" : "", text: h }))),
    ...ai.stats.map((b) => el("tr", {},
      el("td", { text: b.bucket }), el("td", { class: "r", text: b.ideas }), el("td", { class: "r", text: b.resolved }),
      el("td", { class: "r", text: b.targets }), el("td", { class: "r", text: b.stops }), el("td", { class: "r", text: b.expired }),
      el("td", { class: "r", text: b.hitRatePct == null ? "–" : b.hitRatePct.toFixed(0) + "%" }),
    )),
  ));
}

/** Stock options: only ideas that clear the confidence bar and every rule check get a trade plan. */
function renderStockIdeas(ai) {
  const n = ai.stockWatchlist.length;
  $("stock-sub").textContent = `Biggest movers of ${n} F&O stocks, scanned every 30 min · trades need ≥${ai.minConfidence}%`;
  $("stock-view").textContent = ai.stockRun ? `${timeOf(ai.stockRun.at)} · ${ai.stockRun.marketView}` : "No stock scan yet today. Runs every 30 min from 09:30 while the market is open, or press Scan now.";
  const latest = ai.stockRun ? new Set(ai.stockRun.ideaIds) : new Set();
  const stock = ai.ideas.filter((i) => i.kind === "STOCK");
  // Paper test ideas show here too so the stock-option pipeline can be tried.
  const top = stock.filter((i) => (i.qualified || i.id.startsWith("test-")) && i.status === "OPEN");
  const watch = stock.filter((i) => latest.has(i.id) && !(i.qualified && i.status === "OPEN"));
  $("stock-ideas").replaceChildren(...(top.length
    ? top.map((i) => ideaCard(i, ai.plans?.[i.id], busyReason(ai, i)))
    : [el("div", { class: "empty", text: `No stock idea at ≥${ai.minConfidence}% right now. That is normal: most scans should find nothing worth risking money on.` })]));
  $("stock-watch-sum").textContent = `Below the confidence bar: watch only (${watch.length})`;
  $("stock-watch").replaceChildren(...watch.map((i) => ideaCard(i, null, true)));
}

/** Below-the-bar ideas with Claude's speculative TAKE/SKIP call. Manual only. */
function renderRisky(ai) {
  const byId = new Map(ai.ideas.map((i) => [i.id, i]));
  const list = (ai.highRiskIds || []).map((id) => byId.get(id)).filter(Boolean);
  // TAKE calls first, then by confidence.
  list.sort((a, b) => (b.riskyVerdict === "TAKE") - (a.riskyVerdict === "TAKE") || b.confidence - a.confidence);
  $("risky-ideas").replaceChildren(...(list.length
    ? list.map((i) => {
        const card = ideaCard(i, ai.plans?.[i.id], busyReason(ai, i), true);
        const take = i.riskyVerdict === "TAKE";
        card.insertBefore(el("p", { class: "verdict " + (take ? "up" : "down") },
          el("strong", { text: take ? "AI says: worth the gamble. " : i.riskyVerdict === "SKIP" ? "AI says: skip it. " : "AI: no call. " }),
          i.riskyReason || ""), card.children[1]);
        return card;
      })
    : [el("div", { class: "empty", text: "No open LONG/SHORT ideas below the confidence bar right now." })]));
}

/** Why a new trade on this idea can't start right now (slots full or same underlying busy), or null. */
function busyReason(ai, i) {
  const t = ai.trader;
  const used = t.opens.map((o) => o.trade.index).concat(t.pendings.map((w) => w.index));
  if (used.includes(i.index)) return `A ${i.index} trade is already open or waiting`;
  if (t.slotsLeft < 1) return `All ${t.settings.maxOpenTrades} trade slots in use`;
  return null;
}

function ideaCard(i, plan, busy, highRisk = false) {
  const statusTag = {
    TARGET: el("span", { class: "tag hit", text: "✓ hit target" }),
    STOP: el("span", { class: "tag miss", text: "✕ stopped" }),
    EXPIRED: el("span", { class: "tag", text: "expired" }),
    NO_TRADE: el("span", { class: "tag", text: "no trade" }),
    OPEN: i.qualified ? el("span", { class: "tag go", text: "● suggested" }) : el("span", { class: "tag", text: "watch only" }),
  }[i.status];
  const dir = i.action === "LONG" ? "▲ LONG" : i.action === "SHORT" ? "▼ SHORT" : "— NO TRADE";
  const directional = i.action !== "NO_TRADE";
  return el("div", { class: "idea" + (i.qualified && i.status === "OPEN" ? " qualified" : "") },
    el("div", { class: "row" },
      el("span", { class: "act " + (i.action === "LONG" ? "up" : i.action === "SHORT" ? "down" : ""), text: `${i.index} ${dir}` }),
      directional ? el("span", { class: "conf", text: `${i.confidence}%` }) : null,
      statusTag,
      i.autoTraded ? el("span", { class: "tag go", text: "auto-traded" }) : null,
      el("span", { class: "spacer" }),
      el("span", { class: "muted small", text: timeOf(i.createdAt) + (directional ? ` · ${i.horizonMinutes}m` : "") }),
    ),
    directional ? el("div", { class: "levels", text: `entry ${inr(i.entry)} · stop ${inr(i.stopLoss)} · target ${inr(i.target)}` }) : null,
    el("p", { class: "why", text: i.rationale }),
    directional && i.invalidation ? el("p", { class: "inv", text: "Invalidation: " + i.invalidation }) : null,
    directional && !i.qualified && i.status === "OPEN" ? el("p", { class: "inv", text: "Not suggested: " + i.note }) : null,
    plan ? planBlock(i, plan, busy, highRisk) : null,
  );
}


/** How the idea would be traded with an option: money in, levels, max loss and what it can grow to. */
function planBlock(i, p, busy, highRisk = false) {
  const f = p.forecast;
  const pts = (n) => i.kind === "STOCK" ? "₹" + inr(n, 2) : inr(n, 0) + " pts";
  const sign = i.action === "LONG" ? "+" : "−";
  const exp = new Date(p.expiry + "T00:00:00").toLocaleDateString("en-IN", { day: "numeric", month: "short" });
  const units = p.lots > 0 ? `${p.lots} lot × ${p.lotSize} = ${p.quantity} qty` : `1 lot = ${p.lotSize} qty`;
  const invest = p.lots > 0 ? p.investment : p.oneLotCost;
  const loss = p.lots > 0 ? p.maxLoss : p.oneLotRisk;
  const gain = p.lots > 0 ? p.profitAtTarget : (p.targetPremium - p.premium) * p.lotSize;
  const growth = f.expectedPerTrade <= 0
    ? `Expected ${rupees(f.expectedPerTrade, 0)} per trade after charges, so repeating this setup would shrink capital.`
    : `Expected ${rupees(f.expectedPerTrade, 0)} per trade after charges: about ${f.tradesTo1_5x} such trades to grow capital 1.5x, ${f.tradesTo2x} to 2x.`;

  const cell = (label, value, c = "") => el("div", { class: "cell" }, el("span", { class: "muted small", text: label }), el("span", { class: "num " + c, text: value }));
  const btn = el("button", { class: "btn primary", text: "Take trade" });
  // Say why right next to the button: a disabled button with only a tooltip looks broken.
  const why = !p.executable ? "Blocked: " + p.reason : busy || null;
  if (why) { btn.disabled = true; btn.title = why; }
  if (highRisk) { btn.textContent = "Take high-risk trade"; btn.classList.replace("primary", "danger"); }
  btn.addEventListener("click", () => openTake(i, p, highRisk));

  return el("div", { class: "plan" },
    el("div", { class: "row" },
      el("span", { class: "sym", text: `Buy ${p.index} ${inr(p.strike, 0)} ${p.optionType}` }),
      el("span", { class: "muted small", text: `exp ${exp} · ${units}` }),
      el("span", { class: "spacer" }),
      why ? el("span", { class: "why-disabled", text: why }) : null,
      btn,
    ),
    el("div", { class: "grid" },
      cell("Invest", rupees(invest, 0)),
      cell("Entry premium", "₹" + inr(p.premium)),
      cell(`Target (${p.targetMultiple.toFixed(2)}x)`, "₹" + inr(p.targetPremium), "up"),
      cell("Stop-loss", "₹" + inr(p.stopPremium), "down"),
      cell("Profit at target", rupees(gain, 0), "up"),
      cell("Max loss", rupees(-loss, 0), "down"),
    ),
    el("p", { class: "small", text: `${rupees(invest, 0)} → ${rupees(f.valueAtTarget, 0)} at target, ${rupees(f.valueAtStop, 0)} at stop. ${p.index} needs ${sign}${pts(p.indexMoveForTarget)} for the target (AI's target: ${sign}${pts(p.aiIndexMove)}).` }),
    el("p", { class: "small muted", text: `Win chance ${(f.winProbability * 100).toFixed(0)}% (${f.probabilitySource}). ${growth}` }),
    p.executable ? null : el("p", { class: "inv down", text: `To make this tradeable, raise ${highRisk ? "High-risk max loss" : "Max loss per trade / Capital"} in Trade settings.` }),
  );
}

let takeCtx = null;
function openTake(i, p, highRisk = false) {
  takeCtx = { i, p, highRisk };
  const live = $("mode").textContent === "LIVE";
  $("take-title").textContent = `Buy ${p.index} ${inr(p.strike, 0)} ${p.optionType}`;
  $("take-mode").textContent = live ? "LIVE: this places a REAL order with real money." : "PAPER: simulated order against live prices.";
  $("take-mode").className = live ? "banner" : "muted small";
  if (highRisk) {
    $("take-mode").textContent += ` HIGH RISK: ${i.confidence}% confidence, below your bar.` +
      (i.riskyVerdict === "SKIP" ? " The AI advises SKIPPING this one." : "");
    $("take-mode").className = "banner";
  }
  $("take-lots").max = p.lots; $("take-lots").value = p.lots;
  $("take-mult").value = "";
  $("take-mult").placeholder = p.targetMultiple.toFixed(2);
  updateTakeSummary();
  $("take-dialog").showModal();
}
function updateTakeSummary() {
  if (!takeCtx) return;
  const { i, p } = takeCtx;
  const lots = Math.max(1, Math.min(p.lots, parseInt($("take-lots").value || p.lots, 10)));
  const mult = parseFloat($("take-mult").value) || p.targetMultiple;
  const qty = lots * p.lotSize;
  const target = p.premium * mult;
  $("take-summary").replaceChildren(
    el("div", { class: "kv" }, el("span", { text: "Quantity" }), el("span", { class: "num", text: `${lots} lot = ${qty}` })),
    el("div", { class: "kv" }, el("span", { text: "Money in" }), el("span", { class: "num", text: rupees(p.premium * qty, 0) })),
    el("div", { class: "kv" }, el("span", { text: `Sell at target (${mult.toFixed(2)}x)` }), el("span", { class: "num up", text: `₹${inr(target)} → +${rupees((target - p.premium) * qty, 0)}` })),
    el("div", { class: "kv" }, el("span", { text: "Stop-loss" }), el("span", { class: "num down", text: `₹${inr(p.stopPremium)} → ${rupees(-(p.premium - p.stopPremium) * qty, 0)}` })),
    el("p", { class: "muted small", text: `Buys when ${i.index} is at ${inr(i.entry, 1)} (waits if it isn't), then sells automatically at target, stop-loss, the daily profit goal, or 15:15.` }),
  );
}
$("take-lots").addEventListener("input", updateTakeSummary);
$("take-mult").addEventListener("input", updateTakeSummary);
$("take-form").addEventListener("submit", async (e) => {
  if (e.submitter?.value !== "ok" || !takeCtx) return;
  e.preventDefault();
  const btn = e.submitter;
  await withBusy(btn, async () => {
    const live = $("mode").textContent === "LIVE";
    if (live && !confirm("Place a REAL order on Zerodha?")) return;
    if (takeCtx.highRisk && !confirm(`High-risk trade at ${takeCtx.i.confidence}% confidence. You accept this is a speculative punt?`)) return;
    const mult = parseFloat($("take-mult").value);
    const r = await post("/ai/take", {
      ideaId: takeCtx.i.id,
      lots: parseInt($("take-lots").value, 10) || null,
      targetMultiple: Number.isFinite(mult) ? mult : null,
      confirmLive: live,
      highRisk: !!takeCtx.highRisk,
    });
    $("take-dialog").close();
    alert(r.message);
    refreshAll();
  });
});

// ---------------------------------------------------------------- trader

function renderTrader(t, mode) {
  const st = t.settings;
  $("trader-limits").textContent = `${mode} · capital ₹${inr(st.capital, 0)} · max loss ₹${inr(st.maxLossPerTrade, 0)}/trade, ₹${inr(st.maxDailyLoss, 0)}/day` +
    (st.dailyProfitGoal > 0 ? ` · goal +₹${inr(st.dailyProfitGoal, 0)}/day` : "");
  renderSettings(st);
  const sw = $("autotrade");
  if (document.activeElement !== sw) sw.checked = t.autoTrade;

  const parts = [];
  if (t.lossBudgetLeft < 1) parts.push(el("div", { class: "banner", text: "Daily AI loss limit reached. No new trades until tomorrow." }));
  if (t.profitGoalReached) parts.push(el("div", { class: "banner good", text: "Daily profit goal reached. Profit locked in; no new trades today." }));
  parts.push(el("div", { class: "kv" }, el("span", { text: "Realised today" }), el("span", { class: "num " + cls(t.realizedPnlToday), text: rupees(t.realizedPnlToday, 2) })));
  parts.push(el("div", { class: "kv" }, el("span", { text: "Loss budget left today" }), el("span", { class: "num", text: rupees(t.lossBudgetLeft, 0) })));

  parts.push(el("div", { class: "kv" }, el("span", { text: "Open trades" }),
    el("span", { class: "num", text: `${t.opens.length + t.pendings.length} / ${t.settings.maxOpenTrades} · at risk ${rupees(t.openRisk, 0)}` + (t.openPnl == null ? "" : ` · open P&L ${rupees(t.openPnl, 0)}`) })));

  for (const w of t.pendings) {
    parts.push(el("div", { class: "idea" },
      el("div", { class: "row" },
        el("span", { class: "act", text: `Waiting: ${w.index} ${w.action === "LONG" ? "▲ LONG (call)" : "▼ SHORT (put)"}` }),
        el("span", { class: "muted small", text: `${w.source} · until ${timeOf(w.expiresAt)}` }),
        el("span", { class: "spacer" }),
        el("button", { class: "btn", text: "Cancel", onclick: (e) => withBusy(e.target, async () => { await post("/ai/pending/cancel", { ideaId: w.ideaId }); refreshAll(); }) }),
      ),
      el("div", { class: "levels", text: `buys when ${w.index} is near ${inr(w.indexEntry, 1)} · cancels if it hits ${inr(w.indexStop, 1)} first` }),
    ));
  }
  for (const v of t.opens) {
    const o = v.trade;
    const mult = v.ltp ? v.ltp / o.entryPrice : null;
    parts.push(el("div", { class: "idea qualified" + (o.source === "high-risk" ? " hr" : "") },
      el("div", { class: "row" },
        el("span", { class: "act up", text: `${o.symbol} × ${o.quantity}` }),
        o.source === "high-risk" ? el("span", { class: "tag miss", text: "high risk" }) : null,
        el("span", { class: "num " + cls(v.pnl), text: v.pnl == null ? "" : rupees(v.pnl, 2) + (mult ? ` (${mult.toFixed(2)}x)` : "") }),
        el("span", { class: "spacer" }),
        el("button", { class: "btn danger", text: "Close now", onclick: (e) => withBusy(e.target, async () => {
          if (confirm(`Exit ${o.symbol} at market now?`)) { await post("/ai/trade/close", { ideaId: o.ideaId }); refreshAll(); }
        }) }),
      ),
      el("div", { class: "levels", text: `bought ₹${inr(o.entryPrice)} (${rupees(o.entryPrice * o.quantity, 0)}) · now ₹${inr(v.ltp)} · stop ₹${inr(o.stopPrice)}${o.initialStop && o.stopPrice > o.initialStop ? " (trailing)" : ""} · peak ₹${inr(o.peakPrice)} · target ₹${inr(o.targetPrice)} · risk ${rupees(Math.max(0, (o.entryPrice - o.stopPrice) * o.quantity), 0)} · until ${timeOf(o.expiresAt)}` }),
    ));
  }
  if (t.opens.length > 1) {
    parts.push(el("div", { class: "row-end" }, el("button", { class: "btn danger", text: "Close all AI trades", onclick: (e) => withBusy(e.target, async () => {
      if (confirm(`Exit all ${t.opens.length} AI trades at market now?`)) { await post("/ai/trade/close"); refreshAll(); }
    }) })));
  }
  if (!t.opens.length && !t.pendings.length) {
    parts.push(el("div", { class: "empty", text: t.autoTrade ? "Auto-trade on: waiting for qualified ideas (≥ confidence threshold and all rule checks)." : "No trade open. Use Take trade on an idea, or switch on auto-trade." }));
  }
  if (t.closedToday.length) {
    parts.push(el("table", { class: "t" },
      el("tr", {}, ...["Time", "Trade", "Entry", "Exit", "P&L", "Why"].map((h, i) => el("th", { class: i >= 2 && i <= 4 ? "r" : "", text: h }))),
      ...t.closedToday.map((c) => el("tr", {},
        el("td", { text: timeOf(c.openedAt) }), el("td", { text: `${c.symbol} × ${c.quantity}` }),
        el("td", { class: "r", text: inr(c.entryPrice) }), el("td", { class: "r", text: inr(c.exitPrice) }),
        el("td", { class: "r " + cls(c.pnl), text: rupees(c.pnl, 2) }), el("td", { class: "muted", text: c.exitReason }),
      )),
    ));
  }
  if (t.log.length) parts.push(el("ul", { class: "log" }, ...t.log.map((l) => el("li", { text: l }))));
  $("trader").replaceChildren(...parts);
}

$("autotrade").addEventListener("change", async (e) => {
  const on = e.target.checked;
  const live = $("mode").textContent === "LIVE";
  if (on && !confirm(live
    ? "LIVE mode: auto-trade will place REAL orders with real money within your limits. Continue?"
    : "Enable auto-trade in PAPER mode (simulated orders)?")) { e.target.checked = false; return; }
  try { await post("/ai/autotrade", { on, confirmLive: live }); } catch (err) { alert(err.message); }
  refreshAll();
});

// ---------------------------------------------------------------- bot mood

const MOOD_FACE = { good: "😎", calm: "🙂", caution: "🤔", danger: "😬" };
function renderMood(m) {
  if (!m) return;
  $("mood").className = "mood " + m.tone;
  $("mood-face").textContent = MOOD_FACE[m.tone] || "🙂";
  $("mood-text").textContent = m.text;
  $("mood").title = m.source === "AI" ? "From Claude's latest market read" : "From live market numbers";
}

// ---------------------------------------------------------------- trade settings

const SETTING_FIELDS = ["maxOpenTrades", "capital", "maxLossPerTrade", "maxDailyLoss", "dailyProfitGoal", "targetMultiple", "maxStopPct", "trailStartPct", "trailPct", "highRiskMaxLoss"];
let settingsDirty = false;
function renderSettings(st) {
  if (settingsDirty) return; // don't overwrite what the user is typing
  for (const k of SETTING_FIELDS) $("set-" + k).value = st[k];
  $("set-targetMode").value = st.targetMode;
  $("set-targetMultiple").disabled = st.targetMode !== "MULTIPLE";
}
$("settings-form").addEventListener("input", () => {
  settingsDirty = true;
  $("set-targetMultiple").disabled = $("set-targetMode").value !== "MULTIPLE";
});
$("settings-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  await withBusy(e.submitter, async () => {
    const body = { targetMode: $("set-targetMode").value };
    for (const k of SETTING_FIELDS) body[k] = parseFloat($("set-" + k).value);
    await put("/ai/settings", body);
    settingsDirty = false;
    $("settings-msg").textContent = "Saved " + new Date().toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit" });
    refreshAll();
  });
});

// ---------------------------------------------------------------- brief

let shownBriefAt = null;
async function loadBrief(force = false) {
  try {
    const b = await api("/ai/brief");
    if (!force && b.at === shownBriefAt) return;
    shownBriefAt = b.at;
    $("brief-at").textContent = `${new Date(b.at).toLocaleString("en-IN", { timeZone: "Asia/Kolkata", dateStyle: "medium", timeStyle: "short" })} · $${b.costUsd.toFixed(2)}`;
    const html = window.marked && window.DOMPurify ? DOMPurify.sanitize(marked.parse(b.markdown)) : null;
    if (html) $("brief").innerHTML = html; else $("brief").textContent = b.markdown;
    $("sources").replaceChildren(...b.sources.map((s) => el("a", { href: s.url, target: "_blank", rel: "noopener noreferrer", title: s.url, text: s.title })));
  } catch {
    $("brief").replaceChildren(el("div", { class: "empty", text: "No brief yet. Generated every 2 hours from 08:30 on trading days, or press Refresh brief." }));
  }
}

// ---------------------------------------------------------------- side panel

function renderHoldings(p) {
  if (!p) { $("holdings-total").replaceChildren(); $("holdings").replaceChildren(el("div", { class: "empty", text: "–" })); return; }
  $("holdings-total").replaceChildren(
    el("span", { class: "big num", text: rupees(p.currentValue) }),
    el("span", { class: "num " + cls(p.pnl), text: `${rupees(p.pnl)} (${signed(p.pnlPct, 1, "%")})` }),
  );
  $("holdings").replaceChildren(...p.holdings.map((h) => el("div", { class: "hrow", title: `avg ${inr(h.averagePrice)} · ltp ${inr(h.lastPrice)} · day ${signed(h.dayChangePct, 2, "%")}` },
    el("div", {}, el("div", { class: "sym", text: h.symbol }), el("div", { class: "sub", text: `${h.quantity} · ${h.weightPct.toFixed(1)}%` })),
    el("div", { class: "right" }, el("div", { text: rupees(h.currentValue) }), el("div", { class: "small " + cls(h.pnlPct), text: signed(h.pnlPct, 1, "%") })),
  )));
}

function renderPositions(ps) {
  if (!ps || !ps.length) { $("positions").replaceChildren(el("div", { class: "empty", text: "No positions today." })); return; }
  $("positions").replaceChildren(...ps.map((p) => el("div", { class: "hrow" },
    el("div", {}, el("div", { class: "sym", text: p.tradingsymbol }), el("div", { class: "sub", text: `${p.product} · qty ${p.quantity}` })),
    el("div", { class: "right" }, el("div", { class: cls(p.pnl), text: rupees(p.pnl, 2) }), el("div", { class: "sub", text: inr(p.lastPrice) })),
  )));
}

function renderRisk(r, equityNet, ordersToday) {
  const kill = $("kill");
  kill.classList.toggle("on", !!r?.killSwitch);
  kill.textContent = r?.killSwitch ? "Kill switch ON" : "Kill switch";
  if (!r) { $("risk").replaceChildren(el("div", { class: "empty", text: "–" })); return; }
  const kv = (k, v, c = "") => el("div", { class: "kv" }, el("span", { text: k }), el("span", { class: "num " + c, text: v }));
  $("risk").replaceChildren(
    r.killSwitch ? el("div", { class: "banner", text: "Kill switch: " + (r.killReason || "on") }) : null,
    kv("Cash (equity net)", equityNet == null ? "–" : rupees(Number(equityNet), 2)),
    kv("Day P&L", rupees(r.dayPnl, 2), cls(r.dayPnl)),
    kv("Daily loss limit", rupees(r.maxDailyLoss)),
    kv("Open positions", `${r.openPositions} / ${r.maxOpenPositions}`),
    kv("Orders today", `${ordersToday ?? r.ordersToday} / ${r.maxOrdersPerDay}`),
  );
}

$("kite-logout").addEventListener("click", (e) => withBusy(e.target, async () => {
  const late = new Date().toLocaleTimeString("en-GB", { timeZone: "Asia/Kolkata", hour12: false }) >= "15:30";
  if (!confirm((late ? "" : "The market is still open. ") + "Log out of Zerodha? tally stops trading and tracking until you log in again.")) return;
  await post("/auth/logout");
  refreshAll();
}));
$("kill").addEventListener("click", (e) => withBusy(e.target, async () => {
  const on = !e.target.classList.contains("on");
  await post("/risk/kill-switch", { on, reason: on ? "Dashboard" : "Released from dashboard" });
  refreshAll();
}));
$("squareoff").addEventListener("click", (e) => withBusy(e.target, async () => {
  if (!confirm("Engage the kill switch and exit EVERY open position at market?")) return;
  await post("/squareoff");
  refreshAll();
}));
$("test-idea").addEventListener("click", (e) => withBusy(e.target, async () => {
  const index = (prompt("Underlying for the test idea: NIFTY, BANKNIFTY or an F&O stock like BHARTIARTL", "NIFTY") || "").trim().toUpperCase();
  if (!index) return;
  const action = confirm("OK = LONG (buy call), Cancel = SHORT (buy put)") ? "LONG" : "SHORT";
  await post("/ai/test-idea", { index, action });
  await refreshAll();
}));
$("gen-stocks").addEventListener("click", (e) => withBusy(e.target, async () => { await post("/ai/stock-ideas/refresh"); await refreshAll(); }));
$("gen-ideas").addEventListener("click", (e) => withBusy(e.target, async () => { await post("/ai/ideas/refresh"); await refreshAll(); }));
$("gen-brief").addEventListener("click", (e) => withBusy(e.target, async () => { await post("/ai/brief/refresh"); await loadBrief(true); refreshAll(); }));

// ---------------------------------------------------------------- main loop

async function refreshAll() {
  try {
    const d = await api("/dashboard");
    const s = d.status;
    $("mode").textContent = s.mode;
    $("mode").classList.toggle("live", s.mode === "LIVE");
    $("test-idea").hidden = s.mode !== "PAPER";
    const kite = $("kite");
    $("kite-logout").hidden = !s.kiteLoggedIn;
    if (s.kiteLoggedIn) kite.textContent = "Kite ✓";
    else kite.replaceChildren(el("a", { href: "/auth/login", text: "Log in to Kite" }));
    $("market").textContent = s.marketOpen ? "● Market open" : "Market closed";
    $("spend").textContent = `AI $${d.ai.spendUsd.toFixed(2)} / $${d.ai.budgetUsd.toFixed(2)}` + (d.ai.overBudget ? " (paused)" : "");
    $("updated").textContent = "updated " + timeOf(s.now);

    renderTiles(d.indices);
    renderIdeas(d.ai);
    renderStockIdeas(d.ai);
    renderRisky(d.ai);
    renderTrader(d.ai.trader, s.mode);
    renderHoldings(d.portfolio);
    renderMood(d.ai.mood);
    renderPositions(d.positions);
    renderRisk(d.risk, d.equityNet, d.ordersToday);
    if (d.ai.briefAt && d.ai.briefAt !== shownBriefAt) loadBrief();
  } catch (e) {
    $("updated").textContent = "offline: " + e.message;
  }
}

async function boot() {
  if (!token) { askToken(); return; }
  await loadCharts();
  await Promise.all([refreshAll(), loadGlobal(), loadBrief()]);
}
boot();
setInterval(refreshAll, 10_000);
setInterval(loadGlobal, 60_000);
setInterval(() => loadCharts().then(refreshAll), 60_000);
