---
title: FalkeAI Bot
emoji: 🦅
colorFrom: blue
colorTo: indigo
sdk: docker
app_port: 8080
---

# Aurikrex WhatsApp Bot — FalkeAI (Rust Core + Go `whatsmeow` Bridge + Turso)

**FalkeAI** is Falcon's personal AI assistant, digital representative, and Aurikrex Learning Systems assistant on WhatsApp, built on a low-memory (~30–40 MB RAM) **Rust + Go Hybrid** architecture with zero Node.js:

- **Rust Core (`falkeai-core`)**:
  - Waits **5 minutes** on unread private chats so Falcon can reply first; if Falcon does not open or reply within 5 minutes, FalkeAI steps in and **first introduces itself** with:
    > *"Hey! I'm Falcon's Personal Symbiotic Agent. I work alongside him, keeping conversations moving and handling things in his absence. He's unavailable at the moment, but I'm here. What's up?"*
  - On **subsequent messages** in that session, FalkeAI responds naturally **without** introducing itself again.
  - **Read-Without-Reply Suppression**: If Falcon opens/reads a message on his phone or desktop and chooses **not** to reply, FalkeAI cancels the 5-minute timer and does **not** continue the conversation.
  - **When Falcon returns**: Stops responding automatically and lets Falcon take over.
  - **Per-Person Chat Pattern Learning**: Learns and mirrors the exact pattern by which Falcon chats with *each individual person* (`owner_style_pattern` + `sample_owner_replies` in `contact_memory` — including slang/particles like *"ohh"*, *"omo"*, *"boss"*, sentence length, and tone).
  - Hands over to Falcon (`"I'll leave this one for Falcon."`) and alerts your `"Message yourself"` chat whenever a message requires Falcon's personal judgment, commitments, or unverified facts.
  - Maintains a **persistent 3-layer memory** in **Turso** (`contact_memory`, `owner_memory`, `learning_episodes`) and continuously learns from your manual replies and self-chat instructions.
  - Moderates **Aurikrex Community** groups: allows admin links and Aurikrex product links (`aurikrex.com`, `aurikrex.tech`), notifies you on a single non-safelisted link without deleting it, and immediately deletes all recent messages + removes the member if they send multiple non-safelisted links in under a minute.
- **Thin Go Bridge (`bridge/wa-bridge` via `whatsmeow`)**:
  - Spawned and supervised by `falkeai-core` over `stdin`/`stdout` NDJSON.
  - Handles only WhatsApp Multi-Device encryption, pairing codes, message send/revoke (`BuildRevoke` delete-for-everyone), and group participant updates.

## Setup

### 1. Turso Database (free tier)
1. Create a database on [Turso](https://turso.tech) (`turso db create aurikrex-bot`).
2. Put the `libsql://...` URL in `TURSO_DATABASE_URL`.
3. Create an auth token (`turso db tokens create aurikrex-bot`) and put it in `TURSO_AUTH_TOKEN`.
   *(For local development/testing, you can also set `TURSO_DATABASE_URL=file:local.db` or `:memory:`.)*

### 2. AI Key (Cloudflare Workers AI Free Tier — 10,000 Neurons/Day)
Set `CF_ACCOUNT_ID`, `AI_API_KEY`, and `AI_MODEL=@cf/mistralai/mistral-small-3.1-24b-instruct` (or any OpenAI-compatible provider).

### 3. Deploy on Heroku (GitHub Student Developer Pack — $0 Cost)
1. Set your Heroku stack to container (`heroku stack:set container -a falkeai-bot`).
2. Add config vars from `.env.example` (`TURSO_DATABASE_URL`, `TURSO_AUTH_TOKEN`, `PAIR_PHONE`, `CF_ACCOUNT_ID`, `AI_API_KEY`, `AI_MODEL`).
3. Push and enable the `worker` process (`git push heroku main && heroku ps:scale worker=1:basic -a falkeai-bot`). See [`DEPLOYMENT.md`](DEPLOYMENT.md) for the full step-by-step guide.

## Control FalkeAI from WhatsApp
Open **"Message yourself"** and send:

| Command | What it does |
|---|---|
| `remember <fact>` | Store a permanent global fact or standing instruction in FalkeAI's memory |
| `remember <number>: <fact>` | Attach a permanent note to a specific contact (e.g. `remember 0803...: Co-founder at X`) |
| `learn <rule>` | Teach FalkeAI a communication or decision preference |
| `memories` / `memories <name/no>` | View global memories & learned lessons, or a specific contact's profile |
| `forget <id or keyword>` | Delete matching memories or lessons |
| `pause <no>` / `resume <no>` | Manually pause or resume FalkeAI in a specific private chat |
| `block <no>` / `unblock <no>` / `blocked` | Ignore or un-ignore numbers in private chats |
| `mode everyone` / `mode unsaved` | Reply to everyone (after 5-min wait) or only to numbers NOT saved in your contacts |
| `guard <group>` / `unguard <group>` / `guarded` | Enable or opt out of group link moderation (Aurikrex Community groups are guarded automatically) |
| `allow <site.com>` / `disallow <site.com>` / `safelist` | Manage allowed domains (`aurikrex.tech` and `aurikrex.com` are always allowed) |
| `bookings` | Latest 10 class bookings |
| `flagged` | Latest 10 chats waiting for Falcon |
| `status` | Online status, reply mode, saved contacts, bookings, flags, and memory counts |
| `help` | Command list |

## Run Rust Tests
`cargo test`
