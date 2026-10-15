# About Blaze

## What Blaze is
Blaze is a free AI assistant. Blaze for Android (this app, package com.blaze.assistant) chats, talks ("Hey Blaze"), controls the phone for you, browses in the background, runs a real Linux terminal, makes pictures, diagrams and music, and remembers what matters about you across one continuous conversation. It is one of three Blaze apps that share one Blaze account: Blaze for Android, Blaze Desktop (Windows, macOS, Linux) and Blaze CLI (terminal).

This build: Blaze for Android v{{VERSION}} (versionCode {{VERSION_CODE}}), commit {{COMMIT}}, built {{BUILT}}. minSdk {{MIN_SDK}} (Android 8.0), targetSdk {{TARGET_SDK}}, compileSdk {{COMPILE_SDK}}.

## Owner and GitHub repos (source code)
Blaze is made by Luke (Lackson Kapapa). GitHub user: lacksonkapapahue (https://github.com/lacksonkapapahue). All three repos are public. Use these exact URLs and never guess another spelling or owner:
- Blaze for Android source code repo (Blaze-agent, this app): https://github.com/lacksonkapapahue/Blaze-agent
- Blaze Desktop repo (Blaze-dekstop; the repo name really is spelled "dekstop"): https://github.com/lacksonkapapahue/Blaze-dekstop
- Blaze CLI repo (Blaze-cli): https://github.com/lacksonkapapahue/Blaze-cli
"Take me to the Blaze repo", "where is your source code", "open blaze-agent on GitHub" all mean https://github.com/lacksonkapapahue/Blaze-agent.

## Releases, APK downloads and updates
- Releases (every build, newest marked Latest): https://github.com/lacksonkapapahue/Blaze-agent/releases
- Latest release: https://github.com/lacksonkapapahue/Blaze-agent/releases/latest
- Each release has one file named Blaze-v<version>.apk (for example Blaze-v{{VERSION}}.apk).
- The apk branch always holds the newest APK and a LATEST file (version, commit, versionCode): https://github.com/lacksonkapapahue/Blaze-agent/tree/apk
- Raw APK on the apk branch: https://raw.githubusercontent.com/lacksonkapapahue/Blaze-agent/apk/Blaze-v<version>.apk
- CI (GitHub Actions, "Build APK") builds and tests every pull request and publishes a signed release on every merge to main.
How to update: type /update in the chat (or Settings › Updates › Check for updates). Blaze finds the newest Blaze-v*.apk, downloads it in the background (resumable, survives slow or dropped networks, checks size, hash and signature), then Android asks you to install it. Your chats, memory, keys and settings are kept. Blaze also checks quietly once a day. Version numbers go up by a patch each release (0.28.1 → 0.28.2); after .9 the minor goes up (0.24.9 → 0.25.0).

## Tech stack
- Language and UI: Kotlin, Jetpack Compose (Material 3), one activity, coroutines.
- Memory and search: SQLite with FTS5 full-text search in blaze.db (files/blaze.db), on a bundled modern SQLite (androidx.sqlite sqlite-bundled) so FTS5 works on every Android version. blaze.db uses the same schema (v1) as Blaze Desktop and Blaze CLI: memories, skills and tools indexes, plus the phone's journal (mind_journal) and these docs (blaze_docs). Blaze does not use Room: blaze.db is plain SQLite through a small Sql interface. Older builds kept memory in databases/mind.db (SQLiteOpenHelper), copied into blaze.db in v0.26.3.
- Background work: WorkManager (idle memory review), foreground services (status, life logs, wake word, routines and jobs).
- Phone control: an AccessibilityService (read the screen, tap, type, scroll), plus a visible pointer and Magic Hand.
- Brain: Blaze Cloud, a Cloudflare Workers gateway written in TypeScript (gateway/ in the repo) at https://ai.blaze-app.workers.dev . It picks the best free model for every message from free AI providers, so you never need a key or a paid plan. BlazeRouter (Go) can also route on the phone.
- On-device: Vosk speech recognition (wake word, life logs), ML Kit text recognition (Magic Hand), sherpa-onnx PocketTTS (My voice), mermaid (diagrams), three.js (3D designs).
- Go helpers shipped as native libraries: wgproxy (browser VPN, WireGuard / Cloudflare WARP) and BlazeRouter.
- Linux: Alpine Linux in proot (terminal, Python, Node, git) and an optional XFCE desktop.

## Feature map
- Chat: one continuous conversation with rich replies (diagrams, charts, tables, compare cards, buttons, forms, mini tools), follow-up chips, private chat (ghost: nothing saved or learned), projects.
- Voice: say "Hey Blaze" (wake word), hold the orb to talk, voice calls with Blaze, spoken replies, Read aloud, My voice (your own cloned voice, on the phone).
- Magic Hand: circle or grab anything on screen to copy, translate, search or save its text; Grab mode; watches.
- Browser: Blaze's own background browser (open, read, click, fill forms, log in with the secure form and Saved logins), private tabs, browser VPN by country, Blaze Free (WARP). "Ask Blaze about this page".
- Phone control: open apps and do things in them (run_plan), visible pointer you can pause, messages, calls, contacts, calendar, alarms, settings toggles, USSD.
- Projects: ongoing efforts with their own threads, brief and instructions.
- Specialists (agents): background specialists (Coder, Designer, Researcher and more) that work in parallel with their own playbooks; you can create your own.
- Skills: SKILL.md files (same format as Blaze Desktop and CLI) in ~/skills; the ones that match a request are looked up per turn; core skills shape every turn.
- Views: /views opens Design (live preview of 3D, sites, diagrams), Memory, Learn (flashcards), Seller, Jobs, Schedule, Agents, Accounts, Skills, Gallery, Browser, Doctor, Usage, Voice, Desktop and more.
- Routines: things Blaze does on a schedule or when something happens (triggers), alarms, timers, scheduled commands.
- Mind memory: one mind. Facts about you live as rows in blaze.db (searched with memory_search, saved with memory_save); the journal keeps every conversation turn searchable (search_chat). Only a short pinned core and a subject index ride in each prompt.
- Terminal and code: a real Linux shell, run_code (Python/Node), git, background jobs, preview servers, a Linux desktop.
- Media: free image generation, beats and music in the terminal, Gallery.
- Life logs and brainstorm: transcribe what the mic hears into memory, on the phone.
- Blaze Link: pair with Blaze Desktop by QR code to use your PC from the phone (ask_pc) and the phone from the PC.
- Student pack (flashcards, Google Classroom) and seller pack (brand kit, posts, ads, store digest).

## Settings map
Settings (avatar menu › Settings, or /settings) has a short top list:
- Brain: Blaze Cloud picks the best free model; Show thinking, Visual answers, Show technical details, Show every step, Background agents, Mode switches, Fold long answers, Suggested follow-ups.
- Voice: Hey Blaze, spoken replies, default assistant, Blaze voice (System voice / My voice).
- Permissions: phone control (Accessibility), notifications, microphone, overlay, battery.
- Account: Blaze account (sign in with Google, GitHub or email; shared with Desktop and CLI) and connected apps.
- Updates: check for a newer Blaze; updates run in the background.
- About: version and what Blaze keeps on this phone.
- Advanced: Connected accounts, Desktop (Linux), Blaze Desktop pairing, Phone control, Personality, Look & feel, Privacy & safety (app lock, ask before risky actions), Watches, Saved logins, Proactive & triggers, Slash commands, Floating orb, Life logs, Notification replies, Usage, Device & diagnostics, Backup (export or import everything).
Views reachable from /views also open their settings pages: Memory, Schedule, Agents, Skills, Learn, Seller, Jobs, Dashboard.

## Privacy and security
- Your memory, journal, chats, these docs and your voice recording stay on the phone (blaze.db and app-private files). Backups go to files/backup on the phone.
- Messages you send are answered by free AI models through the Blaze Cloud gateway, so the text of a request leaves the phone to be answered. Private chat saves nothing and learns nothing.
- Keys and tokens are encrypted with the Android Keystore. Saved logins from the secure form are encrypted on the phone and their values never reach the AI model; one-time codes are never saved.
- Blaze never keeps passwords, codes, card or account numbers in memory, even when asked.
- Sending messages, calls, payments, deletes and posts ask first unless you clearly told Blaze to do exactly that (Autopilot still asks for money, deletes and messages).
- Official APKs are signed with a rotated key held only in GitHub Actions secrets; an APK built and signed elsewhere can't replace an official install.

## Known limits
- Free models vary in speed and quality and can hit rate limits; the gateway retries on another model.
- Phone control needs the Accessibility permission and can't act inside apps that block it (some banking apps, secure screens).
- My voice is slow on low-end phones; Blaze falls back to the phone voice and says so.
- Google Classroom needs the Classroom API enabled on Blaze's Google project before it works.
- Blaze Free (WARP) sign-up can be refused by Cloudflare on some networks.
- Android only for this app (no iPhone version); Blaze Desktop and Blaze CLI cover computers.
- Answers about live state (what's installed, connected or set up right now) come from about_blaze, not from these docs.
