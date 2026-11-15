# FalkeAI — Complete Zero-Cost Heroku Deployment Guide (GitHub Student Pack)

This guide walks you through creating every secret and deploying **FalkeAI** (`falkeai-core` in Rust + `wa-bridge` in Go + **Turso** + **Cloudflare Workers AI**) on **Heroku** for **\$0 out of pocket** using your GitHub Student Developer Pack.

---

## 1. Secret #1 & #2: Turso Database (`TURSO_DATABASE_URL` & `TURSO_AUTH_TOKEN`)
**Cost:** \$0 (Turso Free Starter Tier — 5 GB storage, 500M rows read/month, no credit card required).

1. Sign up at **[turso.tech](https://turso.tech)** using your GitHub account.
2. Install the Turso CLI or use the Turso Web Dashboard:
   ```bash
   turso auth login
   turso db create falkeai-db
   ```
3. Copy your database URL (`TURSO_DATABASE_URL`):
   ```bash
   turso db show falkeai-db --url
   # Example: libsql://falkeai-db-yourname.turso.io
   ```
4. Create a permanent database token (`TURSO_AUTH_TOKEN`):
   ```bash
   turso db tokens create falkeai-db
   # Example: eyJhbGciOiJFZERTQSI...
   ```

---

## 2. Secret #3 & #4: Cloudflare Workers AI (`CF_ACCOUNT_ID` & `AI_API_KEY`)
**Cost:** \$0 (Cloudflare Workers AI Free Tier — **10,000 free Neurons every day**, no credit card required).

1. Log in to **[dash.cloudflare.com](https://dash.cloudflare.com)**.
2. In the left sidebar, click **AI** -> **Workers AI**.
3. Click **Use REST API**:
   - **Get API Token (`AI_API_KEY`)**: Click **Create a Workers AI API Token** and copy the token.
   - **Copy Account ID (`CF_ACCOUNT_ID`)**: Copy the **Account ID** shown on the same page (`32-character hex string`).
4. Set your model (`AI_MODEL`) to Mistral Small on Cloudflare:
   - **Recommended**: `@cf/mistralai/mistral-small-3.1-24b-instruct`

---

## 3. Secret #5: WhatsApp Pairing Phone (`PAIR_PHONE`)
- Your WhatsApp phone number in international format **with digits only** (no `+`, no spaces, no leading zero).
- Example for `+234 911 368 3395`:
  ```text
  2349113683395
  ```

---

## 4. Complete Preset Configuration Table (Heroku Config Vars)

| Config Var | Preset / Value | Purpose |
| :--- | :--- | :--- |
| `TURSO_DATABASE_URL` | `libsql://falkeai-db-<your-org>.turso.io` | Permanent 3-layer memory, bookings, moderation & session backup |
| `TURSO_AUTH_TOKEN` | `eyJhbG...` | Auth token for your Turso database |
| `PAIR_PHONE` | `2349113683395` | Your WhatsApp number (digits only) for 8-character pairing code |
| `CF_ACCOUNT_ID` | `<your-32-char-cloudflare-account-id>` | Automatically configures Cloudflare's OpenAI-compatible endpoint |
| `AI_API_KEY` | `<your-cloudflare-workers-ai-token>` | Cloudflare Workers AI Bearer token (10,000 free Neurons/day) |
| `AI_MODEL` | `@cf/mistralai/mistral-small-3.1-24b-instruct` | Mistral Small model on Cloudflare Workers AI |
| `REPLY_MODE` | `everyone` | `everyone` (all DMs after 5-min unread wait) or `unsaved` (only unsaved contacts) |
| `REPLY_WAIT_MINUTES` | `5` | Minutes to wait on unread chats before FalkeAI introduces itself and steps in |
| `TAKEOVER_PAUSE_MINUTES` | `120` | Minutes FalkeAI stays silent in a DM after you reply or open without replying |

---

## 5. Step-by-Step Deployment on Heroku (GitHub Student Pack)
**Cost:** \$0 (GitHub Student Developer Pack gives **\$13/month credit for 24 months**; a `basic` worker dyno costs \$7/month, leaving \$6/month spare).

1. **Claim Heroku Student Credits**:
   - Go to **[heroku.com/github-students](https://www.heroku.com/github-students)** and connect your verified GitHub Student account to receive your \$13/month platform credit.
2. **Create App & Set Stack to Container**:
   Install the [Heroku CLI](https://devcenter.heroku.com/articles/heroku-cli), then run:
   ```bash
   heroku login
   heroku create falkeai-bot
   heroku stack:set container -a falkeai-bot
   ```
3. **Set Your Secrets (Config Vars)**:
   ```bash
   heroku config:set \
     TURSO_DATABASE_URL="libsql://falkeai-db-yourname.turso.io" \
     TURSO_AUTH_TOKEN="your-turso-token" \
     PAIR_PHONE="2349113683395" \
     CF_ACCOUNT_ID="your-32-char-cloudflare-account-id" \
     AI_API_KEY="your-cloudflare-workers-ai-api-token" \
     AI_MODEL="@cf/mistralai/mistral-small-3.1-24b-instruct" \
     REPLY_MODE="everyone" \
     REPLY_WAIT_MINUTES="5" \
     TAKEOVER_PAUSE_MINUTES="120" \
     -a falkeai-bot
   ```
4. **Push & Scale Worker Dyno**:
   ```bash
   git push heroku main
   heroku ps:scale worker=1:basic -a falkeai-bot
   ```
5. **Link WhatsApp via 8-Character Pairing Code**:
   Watch the live logs:
   ```bash
   heroku logs --tail -a falkeai-bot
   ```
   - Within ~10 seconds of startup, FalkeAI prints:
     ```text
     ==============================
     PAIRING CODE: XXXX-XXXX
     WhatsApp > Settings > Linked devices > Link a device > Link with phone number instead
     ==============================
     ```
   - On your phone, open **WhatsApp -> Settings -> Linked devices -> Link a device -> Link with phone number instead**, and enter that 8-character code.
   - As soon as it connects, FalkeAI automatically snapshots `wa_session.db` into your Turso database (`auth_state`), so future Heroku dyno restarts reconnect automatically without ever asking you to re-pair!
