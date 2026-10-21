# tally: context history

Handover notes so a fresh session can continue without the earlier chat. Written 2026-10-15 (after market close).
No secrets are stored here. API keys live only in `/etc/tally/tally.env` on the server, and the user enters
them there directly; never ask for them in chat.

---

## 1. What tally is

A personal algo-trading bot for the user's own **Zerodha** account (client ID `KI6075`), built with **Kite Connect v3**.
- Trades **intraday F&O by BUYING ATM options**: CE for LONG ideas, PE for SHORT. It never sells options to open.
  - Underlyings: NIFTY, BANKNIFTY, and a watchlist of liquid F&O stocks.
- **Claude API** (raw HTTP, no SDK) provides:
  - a news/global-ripple market brief, using web search
  - structured trade ideas with confidence scores
  - a one-line "tally says" mood
  - a speculative TAKE/SKIP verdict on each idea
- Minimalist web dashboard: market tiles, global markets, AI ideas, stock-option ideas, a high-risk section, the
  auto-trader panel, the brief, holdings, positions, risk, trade settings, and the "tally says" bubble.
- **Currently in LIVE mode** (real money) since 2026-10-15 ~11:29 IST. Auto-trade is OFF.

## 2. Stack & build

- Kotlin **2.3.21** (temporarily, because 2.4.0 wasn't in the Gradle cache; bump back later), Ktor **3.6.0** (Netty
  server, CIO client), kotlinx.serialization, Gradle 9.5.1, logback.
- **JDK 21 required.** Build with
  `JAVA_HOME=/Users/ankurmalhotra/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home`.
  Homebrew Java 26 makes Gradle hang.
- Build and test locally (offline works): `./gradlew test --offline -q`. **24 tests, all passing.**
- Project root: `/Volumes/SSD/ideaproject/tally`. **Not a git repo.** Package `com.tally`.
- The user's mobile internet (Jio) is slow and drops connections. That's why the **server builds the code itself**:
  `deploy.sh` uploads ~140 KB of source and builds remotely.

## 3. Hosting (AWS Lightsail, Mumbai)

- 2 GB instance, Ubuntu, **static IP `52.66.146.205`**. This is the IP whitelisted in the Kite developer console.
- SSH: `ssh -i ~/.ssh/LightsailDefaultKey-ap-south-1.pem ubuntu@52.66.146.205`
- systemd service `tally` runs `/opt/tally/tally.jar` (`-Xmx1g`). Data dir `/var/lib/tally`. Env file `/etc/tally/tally.env`
  (root, 600).
  - Env keys: `TALLY_MODE` (PAPER|LIVE), `TALLY_API_TOKEN`, `KITE_API_KEY`, `KITE_API_SECRET`, `KITE_USER_ID`,
    `ANTHROPIC_API_KEY`, `TALLY_PUBLIC_HOST`.
  - **`KITE_USER_ID=KI6075` is STILL MISSING.** The user has been asked several times to add it and restart.
- App binds **127.0.0.1:8090** only. Port 8080 is used by the user's other (Python EV community) app.
- **HTTPS:** Caddy reverse proxy at **https://tally.buildingdream.co.in** (Let's Encrypt; expiry Jan 2027, auto-renews).
  - Domain from GoDaddy, DNS on Cloudflare. `tally` A record → 52.66.146.205, **DNS-only (grey cloud)**.
  - The root `buildingdream.co.in` is orange-proxied to the same IP but not served (it shows a Cloudflare error),
    which is fine.
  - Caddy `basic_auth` protects the whole site. Credentials are set by the user with
    `ssh -t ... sudo tally-set-password`; the hash is in `/etc/caddy/tally_auth`, username `ankur`.
  - On top of basic auth, every `/api` call needs the `X-Tally-Token` header. The dashboard stores it in localStorage.
  - Lightsail firewall: 22, 80, 443 open to all; 8090 is NOT open.
  - The app never returns 401: browsers would drop the basic-auth credentials. Token or Kite errors return **403**.
- Kite redirect URL: `https://tally.buildingdream.co.in/auth/callback`. Daily login (the token expires 06:00 IST) is at
  `https://tally.buildingdream.co.in/auth/login`.

### Deploy
```
cd /Volumes/SSD/ideaproject/tally
export TALLY_HOST=ubuntu@52.66.146.205 TALLY_SSH_KEY=~/.ssh/LightsailDefaultKey-ap-south-1.pem
./deploy/deploy.sh          # refuses if AI trades are open/waiting; TALLY_FORCE_DEPLOY=1 overrides
```
- `deploy/setup-https.sh <domain>`: Caddy, basic auth, and `TALLY_PUBLIC_HOST` (CORS allow for that origin).
- `deploy/setup-server.sh`, `deploy/tally.service`, `deploy/tunnel.sh` (old SSH tunnel; not needed now).
- **Lesson learned:** I once redeployed while a live trade was open. The broker-side stop still protected it, but
  trade management paused for ~1 minute. That's why the deploy guard exists. **Always check for open trades before
  restarting.**
- Server-side checks: read the token with `sudo grep ^TALLY_API_TOKEN= /etc/tally/tally.env | cut -d= -f2-`, then
  curl `localhost:8090/api/...`. Never print secret values.
- For safe paper testing while LIVE: run a second instance on port 8091 with `TALLY_MODE=PAPER`,
  `TALLY_DATA_DIR=/tmp/tally-paper` (copy `kite_session.json` and `instruments/`), `TALLY_API_TOKEN=papertest`, and
  args `-P:ktor.deployment.port=8091 -P:tally.ai.scheduler=false`.
  - Set its limits through the `PUT /api/ai/settings` API.
  - Kill it afterwards with `pkill -f` on the jar's port argument. Note that `pkill -f "port=8091"` inside an SSH
    command also kills that SSH session, so use a separate command.

## 4. Code map (`src/main/kotlin`)

- `main.kt` (EngineMain), `Http.kt` (CORS, StatusPages, `/api` token gate), `Routing.kt` (all routes, dashboard
  aggregation, test-idea, `liveIdeas()`).
- `com/tally/Application.kt`: the `Services` wiring.
- `config/AppConfig.kt`: AiConfig has `stocks`, `stockIdeaMinutes`, `stockShortlist`. RiskConfig, PaperConfig, etc.
- `kite/`:
  - `KiteClient.kt`: REST client, throttling, `market_protection=-1` on MARKET/SL-M orders. Also `logout()` and
    `createSession`, which rejects a different user ID.
  - `Models.kt`: Quote now has `depth` and `spreadPct`.
  - `KiteSessionStore.kt`.
- `market/`: `InstrumentService.kt` (daily NFO/NSE dump, `atmOption`, rolls to the next expiry on expiry day),
  `MarketDataService.kt`, `GlobalMarkets.kt` (Yahoo).
- `broker/`: `Broker.kt` (LiveBroker), `PaperBroker.kt` (LTP fills with slippage).
- `risk/`:
  - `RiskManager.kt`: account-wide limits; exits are always allowed.
  - `OrderService.kt`: serialized placement plus an audit JSONL.
- `strategy/`: the old EMA/ORB strategies. Backtests lose money, so they run signals-only (autoTrade false).
- `ai/`:
  - `ClaudeApi.kt`:
    - Messages API; model `claude-opus-5-5`; `output_config.effort`; `fallbacks:"default"` plus beta header
      `server-side-fallback-2026-07-01`.
    - pause_turn loop; per-round spend tracked in `ai_spend.json`.
  - `MarketIntel.kt`:
    - `refreshBrief` uses the `web_search_20260209` tool.
    - `generateIdeas` covers indices; `generateStockIdeas` screens the biggest movers in the watchlist and sends the
      top 6 to Claude.
    - JSON-schema outputs with fields `marketView`, `botSays`, and `ideas[]`. Each idea has symbol/index, action,
      confidence, entry, stopLoss, target, horizonMinutes, rationale, invalidation, riskyVerdict, riskyReason.
    - `validate()` sets `qualified` / `levelsOk` (stop % bands differ for INDEX and STOCK).
    - The last runs are persisted to `ai_last_run.json` / `ai_last_stock_run.json`, so a restart doesn't re-run
      (and re-pay for) scans.
  - `IdeaTracker.kt`:
    - `TradeIdea` has `kind` (INDEX|STOCK), `levelsOk`, `riskyVerdict`/`riskyReason`.
    - Ideas are scored against 1-minute candles. Stats buckets: **90+, 80-89, 60-79, <60**. `test-` ideas are
      excluded from stats.
  - `OptionPlanner.kt`:
    - Maps index/stock levels to premium with delta 0.5; the stop is capped at `maxStopPct`.
    - Sizes lots by capital, the risk budget and the account `maxOrderValue`.
    - Spread filter: ≤ 3% or not executable.
    - Forecast: win probability comes from the track record once the bucket has ≥ 10 resolved ideas, otherwise the
      AI estimate. Includes expected value after ₹60 charges, and trades needed to reach 1.5x / 2x.
  - `AiTrader.kt` (multi-trade):
    - Keeps `opens` / `pendings` lists; state file `ai_trader_live.json` (LIVE) or `ai_trader.json` (PAPER).
    - Entry: arm, then wait until the price is within 0.1% of entry. Then a MARKET BUY on NFO with **MIS**, plus an
      SL (trigger, with a limit 10% below) SELL at the broker.
    - Exits: target; stop (with a market backup if price gaps through); **trailing stop** (cancel and replace the SL
      order); idea horizon; daily profit goal (realised plus open P&L, exits all); 15:15 EOD.
    - Never sells more than held, so it can't create shorts.
    - Limits:
      - up to `maxOpenTrades`, one per underlying
      - the combined open stop-risk must fit within the remaining daily loss limit
      - no new entries after 14:45
    - Sources: `auto` (qualified ideas only), `manual`, `high-risk` (below the bar, manual only, sized with
      `highRiskMaxLoss`).
  - `TradeSettings.kt`: dashboard-editable settings persisted in `ai_settings.json`.
  - `BotMood.kt`: the rules plus the fresh AI line for the "tally says" bubble.
  - `AiScheduler.kt`:
    - every 15 s: manage trades
    - every 60 s: score ideas
    - brief: 08:30–15:30, every 120 min
    - index ideas: 09:25–14:45, every 15 min
    - stock ideas: 09:30–14:30, every 30 min
    - budget `dailyBudgetUsd` = 6.0
- `src/main/resources/web/`: `index.html`, `app.css`, `app.js` (vanilla JS; marked + DOMPurify from cdnjs).
- Tests: `ServerTest` (4), `CoreLogicTest` (10), `OptionPlannerTest` (6), `BotMoodTest` (4).

## 5. Key config (`application.yaml`)

- `tally.risk`:
  - `maxOrderValue` **25000** (raised by the user)
  - `maxDailyLoss` 1500 (account-wide brake)
  - `maxOpenPositions` 5
  - cutoff 15:00, square-off 15:15
  - allowed exchanges NSE / BSE / NFO
- `tally.ai`:
  - `minConfidence` **80** (lowered from 90 at the user's request)
  - `minRewardRisk` 1.5, `dailyBudgetUsd` 6.0
  - `autoTrade` false, `allowShort` true (puts)
  - `stocks`: RELIANCE, HDFCBANK, ICICIBANK, SBIN, AXISBANK, KOTAKBANK, BHARTIARTL, INFY, TCS, LT, ITC, BAJFINANCE,
    MARUTI, SUNPHARMA, HINDUNILVR, M&M
- `publicHost` comes from `TALLY_PUBLIC_HOST` (CORS).

## 6. Current live trade settings (the user saved these; they're the user's choice)

- capital ₹25,000/trade; **maxLossPerTrade ₹2,001**; **maxDailyLoss ₹4,001**; **dailyProfitGoal ₹10,000**
- target mode AI; maxStopPct 35; trailing at +25% with a 10% trail
- highRiskMaxLoss: the user may have raised it from the ₹1,000 default, since the high-risk trades went through
- maxOpenTrades 3

I advised 1–2% risk per trade (₹500–1,000), a ~3% daily limit (₹1,500) and a realistic profit goal (₹1,000–2,000).
The user kept higher values; respect that, but mention the risk where relevant.

## 7. Account facts (2026-10-15)

- F&O (NFO) enabled. Funds about ₹50,153 (₹25,000 added today).
- Lot sizes seen: NIFTY 65, BANKNIFTY 30, RELIANCE 500, BHARTIARTL 475.
- ATM premium examples: NIFTY ~₹115–146 (~₹8–9.5k a lot); BANKNIFTY ~₹540–710 (~₹16–21k a lot).

## 8. What happened today (2026-10-15)

- **Paper tests:**
  - NIFTY CE test idea: +₹2,502 (exited when the 60-minute window ended; came within ₹2 of the target).
  - A 52% RELIANCE idea taken via the old UI: +₹314.
  - Multi-trade test on the 8091 paper copy: all checks passed. Closing 3 trades instantly cost ₹755 in
    spread and slippage.
- **LIVE trades** (all from below-80% ideas, via the high-risk section):
  - NIFTY 22950 CE, 54%: ₹133.55 → ₹155.00, **+₹1,394**. The trailing stop moved 3 times, then the stop filled.
  - RELIANCE 1210 CE, 50%: ₹23.30 → ₹25.30, **+₹1,000** (closed by the user).
  - NIFTY 23050 CE: ₹114.90 → ₹98.95, **−₹1,037** (stop-loss).
  - **Day: +₹1,357.50 realised** before ~₹180 charges.
- **AI behaviour:** confidence mostly 40–54%; **no idea reached 80%**. NIFTY ran +346 points against a ~268-point
  ATR, so the AI said "don't chase" while Asia crashed (KOSPI −8.7%).

## 9. User profile & preferences

- Personal project; wants daily profit from F&O using global/US ripple effects. Comfortable taking risk.
  Started small and is adding capital.
- Wants decisive building, direct answers, and honest explanations. Asks "info only, don't change anything" at
  times; respect that.
- Communicates briefly and informally. Explain trading concepts plainly (they are learning F&O mechanics).
- Security rules I follow:
  - never ask for secrets in chat
  - never open 8090 to the internet
  - LIVE actions need explicit confirmation
  - the user flips PAPER/LIVE themselves in the env file
- Honesty points already discussed:
  - "80% confidence" is Claude's estimate, not measured. Real edges are ~50–55% (Renaissance: "right 50.75% of the
    time").
  - SEBI: 93% of individual F&O traders lost money in FY22–24.
  - Daily profit every day isn't realistic; aim for positive weeks and months, with the daily goal and loss limits.
  - Break-even win rate at 1.5:1 reward/risk is ~43% after charges.

## 10. Open items / ideas offered (not built yet)

1. **User action:** add `KITE_USER_ID=KI6075` to `/etc/tally/tally.env`, then `sudo systemctl restart tally`.
2. **Report card panel:** expectancy, profit factor, win rate vs break-even, max drawdown, risk per trade, vs holding
   NIFTY, and confidence calibration, with a verdict of keep testing / edge confirmed / stop.
3. **Automatic confidence check:** pause trading if 80%+ ideas actually win below break-even after ≥ 20 scored ideas.
4. **Shadow paper book:** paper-trade every idea ≥ 50% in the background, to compare a 50% rule with the 80% rule
   using real costs.
5. **NRML "carry winners only":** carry profitable trades overnight with the stop at break-even or better, a GTT stop
   overnight, max 3 days, out 2 days before expiry. My recommendation, offered but **not yet approved**. Currently
   everything is MIS (CNC isn't valid for options).
6. **Global-open playbook:** rules-based trades on the opening gap and first hour, backtestable on history, with
   Claude as a news filter.
7. Hedged option selling (credit spreads); requires allowing SELL to open with hedges.
8. A separate swing section on NRML.
9. Bump Kotlin back to 2.4.0. Optional: persist the scheduler timers (done for idea runs).

## 11. API quick reference (all need the `X-Tally-Token` header)

- `GET /api/dashboard` returns everything the UI polls:
  - `status`, `indices`, `portfolio`, `positions`, `risk`
  - `ai`: `ideas`, `stats`, `plans`, `trader`, `mood`, `run`, `stockRun`, `highRiskIds`, `stockWatchlist`
- `POST /api/ai/ideas/refresh`, `POST /api/ai/stock-ideas/refresh`, `POST /api/ai/brief/refresh`
- `POST /api/ai/take` `{ideaId, lots?, targetMultiple?, confirmLive, highRisk}`
- `POST /api/ai/trade/close` `{ideaId?}` (none = close all); `POST /api/ai/pending/cancel` `{ideaId?}`
- `GET`/`PUT /api/ai/settings`; `POST /api/ai/autotrade` `{on, confirmLive}`
- `POST /api/ai/test-idea` `{index, action}`: PAPER mode only (NIFTY, BANKNIFTY, or a watchlist stock)
- `POST /api/auth/logout[?force=true]`: refused while trades or positions are open
- `GET /auth/login`, `/auth/callback`: Kite OAuth with a state cookie
- `GET /api/ai/trader`, `/api/orders`, `/api/positions`, `/api/account/profile`, `/api/account/margins`,
  `/api/options/chain?underlying=`, `/api/risk`, `POST /api/risk/kill-switch`, `POST /api/squareoff`

## 12. 2026-10-16 changes (built locally, NOT deployed yet)

- Dashboard reshuffle: "tally says" + "Positions today" now sit at the top of the main column. Removed the
  Global markets card (Yahoo data still fetched and fed to Claude), the AI trade ideas card and the Stock options
  card. Ideas still generate in the background and feed the High-risk section and the AI trader. The old auto-trade
  toggle is gone from the UI (backend `autoTrade` stays, off).
- **AI trader** (`ai/AiTraderDesk.kt`): a fund-manager Claude call after each index/stock idea run, only when it is
  enabled, there are new LONG/SHORT ideas with OK levels, and there's room in the limits. It reads 5m→1y charts
  (snapshots now carry 365 daily bars, ema200, 52w high/low, 5/20/60-day returns), the brief, global, the book
  and the track record. It picks ideas and riskPct (25–100% of maxLossPerTrade), then executes via
  `AiTrader.aiTraderTake` (source `ai-trader`, confidence bar bypassed, all other limits kept).
  - Fund cap = premium in open + waiting ai-trader trades.
  - State is in `ai_desk_live.json` / `ai_desk.json`.
  - API: `GET`/`PUT /api/ai/desk` `{enabled, fund, confirmLive}`, `POST /api/ai/desk/run`.

## 13. 2026-10-21: paper challenge + learning loop (deployed)

- **Server switched to PAPER** (`TALLY_MODE=PAPER`) at the user's request; LIVE auto-trade was ON before that.
  Old paper state backed up to `/var/lib/tally/backup-20261021-0542/` (plus `tally.env.bak`). To go back to LIVE,
  set `TALLY_MODE=LIVE` and restart; LIVE keeps its own settings (`ai_settings.json`), risk limits and state.
- Env additions: `TALLY_PAPER_CASH=5000000` (₹50L; only used when `paper_state.json` doesn't exist),
  `TALLY_AI_BUDGET_USD=15`.
- `tally.paper.maxOrderValue/maxDailyLoss/maxOrdersPerDay/maxOpenPositions` replace `tally.risk` in PAPER only.
- Trade settings are now per mode: PAPER uses `ai_settings_paper.json` (capital ₹25L, maxLoss ₹1.5L/trade,
  ₹6L/day, profit goal ₹20L, 4 open trades). AI trader desk: fund ₹50L, **target ₹70L** (new `target` field).
- **`ai/TradeJournal.kt`**: every AI trade journaled with entry/exit market picture (`journal.json` /
  `journal_live.json`); on close Claude reviews it (GOOD_WIN/GOOD_LOSS/BAD_WIN/BAD_LOSS, mistakes, lesson) and
  rewrites the **playbook** (`ai_playbook.json`, ≤15 rules, shared by both modes). The playbook goes into idea
  prompts (index + stock) and the desk prompt; the desk also sees the last 12 journal lines. EOD debrief after
  15:20 (`AiScheduler.dayReviewDue`). API: `GET /api/ai/journal[?day=]`, `POST /api/ai/journal/review-day {day, notes}`.
  Playbook v1 was seeded from the 2026-10-16 debrief. Dashboard has a "Learning" card.
- `CoreLogicTest` "daily loss trips kill switch" fails since the 10-16 commit made the loss limit non-latching;
  test not yet updated (needs the user's OK).
- **Learning mode (PAPER only)**, enabled ~11:40 on 2026-10-21 at the user's request: `TALLY_LEARNING_MODE=true`,
  `TALLY_IDEA_MINUTES=3`, `TALLY_STOCK_IDEA_MINUTES=3`, `TALLY_AI_BUDGET_USD=40`. Idea prompts call every setup with
  clear levels; the desk gets no analyst confidence or bucket stats and is told small learning trades are fine.
  `AiConfig.learningMode` is forced false in LIVE. Rule checks (levels, R:R, stop distance) still apply.
- Day digests (`ai_days.json` / `ai_days_live.json`): saved by each day review; last 10 go to desk and review prompts.
- **S3 backup installed 2026-10-21 16:52 IST**: bucket `tally-data-<account-id>` (ap-south-1, private, versioned,
  SSE-S3, old versions expire after 30 days). IAM user `tally-s3-sync` with a bucket-only inline policy; its key is in
  `/etc/tally/s3.env` (root 600). `tally-s3-sync.timer` runs `/opt/tally/s3-sync.sh` every 15 min + 15:45 IST:
  data dir -> `s3://<bucket>/tally/` (no kite_session.json, instruments, backups), plus `daily/<date>/` copies of
  playbook, day digests and journal. Restore on a new server: stop tally, `sudo bash -c 'set -a; . /etc/tally/s3.env;
  /opt/tally/s3-sync.sh restore'`. Re-running `deploy/s3-setup.sh` rotates the key. The user logged in to the AWS CLI
  as the root user for setup.
- Paper learning mode also overrides playbook entry cutoffs earlier than 14:45 (desk `LEARNING` text) and tells
  reviews not to write them (`TradeJournal.REVIEW_LEARNING`); the playbook itself is untouched (shared with LIVE).
- Dashboard: `cachedDashboard` (single-flight, 5 s cache) + 30 s option-plan cache, and app.js never stacks polls;
  Kite's 1 quote/s limit made `/api/dashboard` take 47-106 s in learning mode.
