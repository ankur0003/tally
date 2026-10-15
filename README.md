# tally

Personal algo-trading and portfolio server for a Zerodha account. Kotlin + Ktor, Kite Connect v3, Claude for research.

- **Execution**: deterministic strategies → risk manager → broker (paper or live). Claude is never in the order path.
- **Markets**: NSE/BSE cash (intraday MIS, swing CNC) and NFO index options (NIFTY / BANKNIFTY ATM option buying).
- **Portfolio**: holdings analytics, concentration warnings, SIP/step-up projections with Monte Carlo, Claude portfolio review.

## Setup

1. **Kite Connect app** — create one at <https://developers.kite.trade>.
   - Redirect URL: `http://127.0.0.1:8090/auth/callback`
   - Market quotes and historical candles need the paid Connect plan, not the free Personal plan. Check current pricing on the developer console.
   - Exchange rules for retail algos require API orders to come from a **static IP registered on the Kite app**. Paper mode only reads data, so it works without one.
2. **Environment**

   ```sh
   export KITE_API_KEY=...
   export KITE_API_SECRET=...
   export ANTHROPIC_API_KEY=...
   export TALLY_API_TOKEN=$(openssl rand -hex 24)   # header for every /api call
   export TALLY_MODE=PAPER                          # LIVE only once you trust it
   ```

3. **Run**: `./gradlew run`, then open <http://127.0.0.1:8090/auth/login> once per day (Kite tokens expire at 06:00 IST).

State lives in `~/.tally` (override with `TALLY_DATA_DIR`): the Kite session, cached instrument dumps, paper-broker state, the runner's open trades, and a daily order audit log in `audit/YYYY-MM-DD.jsonl`.

## Deploy to AWS Lightsail (static IP for Kite whitelisting)

The server binds to `127.0.0.1` only and the firewall exposes SSH alone; you reach the API through an SSH tunnel.

```sh
# 1. Instance in Mumbai (ap-south-1), 2 GB RAM, Ubuntu 24.04, static IP, SSH open to your IP only
./deploy/lightsail-create.sh
#    -> register the printed static IP in the Kite developer console

# 2. Bootstrap + first deploy. Uploads only the source (~100 KB) and builds on the server, so a slow
#    or flaky home connection is fine. Installs JDK 21, `tally` user, systemd unit, /etc/tally/tally.env
export TALLY_HOST=ubuntu@<static-ip> TALLY_SSH_KEY=~/.ssh/lightsail-ap-south-1.pem
./deploy/deploy.sh --bootstrap

# 3. Fill in keys on the server, restart
ssh -i $TALLY_SSH_KEY $TALLY_HOST 'sudo nano /etc/tally/tally.env && sudo systemctl restart tally'
ssh -i $TALLY_SSH_KEY $TALLY_HOST 'sudo grep TALLY_API_TOKEN /etc/tally/tally.env'   # copy for local use

# Every release
./deploy/deploy.sh                      # keeps the previous jar as /opt/tally/tally.jar.prev

# Every trading morning before 09:15
./deploy/tunnel.sh                      # then open http://127.0.0.1:8090/auth/login
```

### HTTPS login on your own domain (optional, no tunnel needed to log in)

1. DNS: `A` record `tally.<your-domain>` → static IP. Lightsail firewall: allow TCP 443 (keep 80 and 8090 closed).
2. `./deploy/setup-https.sh tally.<your-domain>` installs Caddy with an auto-renewing Let's Encrypt cert.
   Only `/auth/login` and `/auth/callback` are public; everything else returns 404.
3. Kite redirect URL: `https://tally.<your-domain>/auth/callback`. Set `KITE_USER_ID` in `/etc/tally/tally.env`
   so only your Zerodha account can log in.

Logs: `ssh ... 'journalctl -u tally -f'`. Data: `/var/lib/tally`. If your home IP changes, re-run `SSH_CIDR=<new-ip>/32 ./deploy/lightsail-create.sh` to update the firewall.

## API

All `/api/*` calls need `-H "X-Tally-Token: $TALLY_API_TOKEN"`.

```sh
T="X-Tally-Token: $TALLY_API_TOKEN"; B=http://127.0.0.1:8090/api

curl -H "$T" $B/status
curl -H "$T" $B/portfolio                                  # holdings, P&L, weights, warnings
curl -H "$T" -X POST $B/portfolio/projection -H 'Content-Type: application/json' \
  -d '{"years":15,"monthlyContribution":25000,"annualStepUpPct":10,"targetValue":20000000}'
curl -H "$T" $B/market/indices                             # NIFTY 50, NIFTY BANK, FINNIFTY
curl -H "$T" "$B/options/chain?underlying=BANKNIFTY&width=5"
curl -H "$T" "$B/market/candles?symbol=INFY&interval=15minute&days=10"

# Orders go through risk checks, then the active broker (paper unless TALLY_MODE=LIVE)
curl -H "$T" -X POST $B/orders -H 'Content-Type: application/json' \
  -d '{"exchange":"NSE","tradingsymbol":"INFY","transactionType":"BUY","quantity":1,"product":"MIS","orderType":"MARKET"}'
curl -H "$T" $B/positions
curl -H "$T" -X POST $B/squareoff                          # panic: kill switch on, flatten everything

# Strategies
curl -H "$T" $B/strategies
curl -H "$T" $B/strategies/signals
curl -H "$T" -X POST $B/strategies/nifty-ema-options -H 'Content-Type: application/json' -d '{"autoTrade":true}'
curl -H "$T" -X POST $B/backtest -H 'Content-Type: application/json' \
  -d '{"strategy":"ema_crossover","symbol":"NIFTY 50","interval":"5minute","days":30,"params":{"stopLossPct":0.5}}'

# Claude research
curl -H "$T" -X POST $B/ai/portfolio-review -H 'Content-Type: application/json' \
  -d '{"goals":"Retire in 15 years, moderate risk","projection":{"years":15,"monthlyContribution":25000}}'
curl -H "$T" -X POST $B/ai/analyze -H 'Content-Type: application/json' -d '{"symbol":"HDFCBANK"}'
```

## Risk controls (`tally.risk` in `application.yaml`)

| Control | Default |
|---|---|
| Kill switch (blocks entries, exits still allowed) | off; trips automatically on daily loss |
| Max daily loss | ₹5,000 |
| Max order value (premium value for options) | ₹1,00,000 |
| Max open positions / orders per day | 5 / 40 |
| No new intraday entries after | 15:00 IST |
| Runner squares off intraday trades at | 15:15 IST |

Turning on `autoTrade` in LIVE mode requires `"confirmLive": true` in the request.

## Strategies

Configured under `tally.strategies.instances`. Each instance picks a strategy, an instrument, an interval, and an execution mode:

- `EQUITY_INTRADAY`: MIS, long and short, flat by EOD.
- `EQUITY_SWING`: CNC, long-only, carried across days.
- `INDEX_OPTIONS`: LONG buys the ATM CE and SHORT buys the ATM PE on the nearest non-expiring expiry, `lots` × lot size from the instrument master. Stop-loss and target are percentages of the premium.

All instances start with `autoTrade: false`, which logs signals without placing orders. Backtest first, then paper-trade, then go live.

## Roadmap

- [ ] WebSocket ticker (KiteTicker binary feed) instead of polling
- [ ] Brokerage/STT/charges model in paper broker and backtests
- [ ] Option backtests on historical premiums; more strategies (VWAP, Supertrend)
- [ ] Web dashboard
- [ ] Telegram/push alerts on fills, stops and kill-switch trips
- [ ] Exchange holiday calendar
