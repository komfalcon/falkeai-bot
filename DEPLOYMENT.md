# FalkeAI — Complete Zero-Cost Azure for Students Deployment Guide

This guide walks you through creating every secret and deploying **FalkeAI** (`falkeai-core` in Rust + `wa-bridge` in Go + **Turso** + **Cloudflare Workers AI**) on your **Azure for Students** subscription at **\$0.00 cost**.

---

## Why It Is 100% Free on Your Azure for Students Account
1. **No Credit Card & No Surprise Charges**: Your **Azure for Students** subscription (shown as `Active` in your Azure Portal) cannot bill you unless you manually upgrade to Pay-As-You-Go.
2. **Free Student Tier Included on Top of \$100 Credit**:
   - **Azure App Service (`B1` Basic Linux Plan)** is included **FREE for 12 months** (750 hours/month = 24/7) under Azure for Students.
   - **Azure Linux VM (`Standard_B1s` / `B2pts_v2` / `B2ats_v2`)** + **64 GB P6 SSD** is also included **FREE for 12 months** (750 hours/month = 24/7).
3. **Zero Manual Server Maintenance**:
   - We added [`.github/workflows/azure-deploy.yml`](.github/workflows/azure-deploy.yml) which builds your Rust + Go Docker image on **GitHub Actions for free** and pushes it to GitHub Container Registry (`ghcr.io`), plus a built-in `:8080` health responder in [`src/main.rs`](src/main.rs).
   - That means you can deploy on **Azure App Service (Web App for Containers)** with **zero Linux VM maintenance**—just like Heroku, you simply `git push` to GitHub and Azure runs it automatically!

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
   - **Step 1 — Get API Token (`AI_API_KEY`)**: Click **Create a Workers AI API Token** (pre-filled with *Workers AI - Read* and *Workers AI - Edit* permissions) and copy the token.
   - **Step 2 — Copy Account ID (`CF_ACCOUNT_ID`)**: Copy the **Account ID** shown right on the same page (`32-character hex string`).
4. Set your model (`AI_MODEL`) to Mistral Small on Cloudflare:
   - **Recommended**: `@cf/mistralai/mistral-small-3.1-24b-instruct`

---

## 3. Secret #5: WhatsApp Pairing Phone (`PAIR_PHONE`)
**Cost:** \$0.
- Your WhatsApp phone number in international format **with digits only** (no `+`, no spaces, no leading zero).
- Example for `+234 911 368 3395`:
  ```text
  2349113683395
  ```

---

## 4. Complete Preset Configuration Table

| Variable | Preset / Value | Purpose |
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
| `WEBSITES_PORT` | `8080` | Tells Azure App Service to probe FalkeAI's built-in `:8080` health check |

---

## 5. Step-by-Step Deployment on Azure for Students

You have two ways to deploy on the **Azure for Students** portal from your screenshot. **Method 1 (Azure App Service `B1` Container)** requires **zero Linux server maintenance**.

### Method 1 (Recommended — Zero Maintenance): Azure App Service (`B1` Free Student Plan)

#### Step A: Push Your Repo to GitHub & Make Package Readable
1. Push this repository to your GitHub account (`main` branch).
2. In your GitHub repo, click the **Actions** tab — you will see **`Build & Push FalkeAI Container (Azure Ready)`** building your Docker image (`ghcr.io/<your-github-username>/falkeai-bot:latest`).
3. Once it finishes (green checkmark), go to your GitHub profile -> **Packages** -> `falkeai-bot` -> **Package settings** -> set visibility to **Public** (or use a GitHub Personal Access Token with `read:packages` in Azure).

#### Step B: Create the Free `B1` Web App for Containers in Azure Portal
1. In your **Azure Portal** (from your screenshot), type **App Services** in the top search bar and click **Create** -> **Web App**.
2. Under **Basics**:
   - **Subscription**: `Azure for Students`
   - **Resource Group**: Click *Create new* -> `aurikrex-rg`
   - **Name**: `falkeai-bot-<yourname>`
   - **Publish**: Select **Container**
   - **Operating System**: **Linux**
   - **Region**: `West Europe` or `East US` (regions with full student quota)
   - **Linux Plan / Pricing Plan**: Click *Explore pricing plans* -> select **Basic B1** (Free for 12 months under Azure for Students — 1.75 GB RAM, 100 ACU).
3. Under **Container**:
   - **Image Source**: Select **Other container registries**
   - **Access Type**: Public (or Private with your GitHub username + PAT)
   - **Registry server URL**: `https://ghcr.io`
   - **Image and tag**: `<your-github-username-lowercase>/falkeai-bot:latest`
4. Click **Review + create** -> **Create**.

#### Step C: Add Environment Variables & Enable Always-On
1. Open your newly created App Service in the Azure Portal.
2. In the left menu, go to **Settings** -> **Environment variables** -> add the variables from Section 4 (`TURSO_DATABASE_URL`, `TURSO_AUTH_TOKEN`, `PAIR_PHONE`, `CF_ACCOUNT_ID`, `AI_API_KEY`, `AI_MODEL`, `REPLY_MODE`, `REPLY_WAIT_MINUTES`, `TAKEOVER_PAUSE_MINUTES`, `WEBSITES_PORT=8080`) and click **Apply**.
3. Go to **Settings** -> **Configuration** -> **General settings**:
   - Set **Always on** to **On** (this guarantees Azure never puts FalkeAI to sleep!).
   - Click **Save**.
4. Go to **Deployment** -> **Deployment Center** -> turn **Continuous Deployment** to **On** so Azure automatically pulls the latest image whenever you `git push`!

#### Step D: View Your 8-Character WhatsApp Pairing Code
1. In your App Service left menu, click **Monitoring** -> **Log stream**.
2. Within ~15 seconds, you will see:
   ```text
   ==============================
   PAIRING CODE: XXXX-XXXX
   WhatsApp > Settings > Linked devices > Link a device > Link with phone number instead
   ==============================
   ```
3. Enter that 8-character code on your phone's WhatsApp.
4. Once `WhatsApp connected (FalkeAI ready)` appears, send `status` to your **"Message yourself"** chat on WhatsApp to confirm!

---

### Method 2 (Alternative): Azure Free Student Ubuntu VM (`Standard_B1s`)
If you prefer a full Ubuntu Linux VM where you can also run other Aurikrex services alongside FalkeAI:
1. In the Azure Portal, search **Virtual machines** -> **Create** -> **Azure virtual machine**.
2. Select:
   - **Subscription**: `Azure for Students`
   - **Image**: `Ubuntu Server 24.04 LTS - x64 Gen2`
   - **Size**: `Standard_B1s` (1 vcpu, 1 GiB memory — Free 750 hrs/month in Azure for Students)
   - **OS Disk**: `64 GiB Standard SSD (P6)` (Free in Azure for Students)
3. SSH into your VM and run:
   ```bash
   sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 git
   git clone https://github.com/<your-username>/aurikrex-bot.git
   cd aurikrex-bot
   cp .env.example .env
   nano .env   # Paste your Turso + Cloudflare + PAIR_PHONE values
   sudo docker compose up -d --build
   sudo docker compose logs -f
   ```
4. Because [`docker-compose.yml`](docker-compose.yml) sets `restart: unless-stopped` and mounts `./data:/app/data`, Docker automatically keeps FalkeAI running 24/7 even across VM reboots!
