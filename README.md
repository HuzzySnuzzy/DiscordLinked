# DiscordLinked

A Velocity proxy plugin that runs a Discord bot. Players must link a Discord account before they can join,
and their activity is recorded for admins.

## How linking works

1. An unlinked player connects and is kicked with a 6-character code (valid 10 minutes by default).
2. In your Discord server they run `/link <code>`.
3. They reconnect and get in. If `linked-role-id` is set, they also get that role.

Players with the `discordlinked.bypass` permission skip the check.

## What gets tracked

Stored in `plugins/discordlinked/data.db` (SQLite): links/unlinks, joins (with IP), leaves, server switches,
chat and commands (both can be turned off). Links, unlinks, joins and leaves are also posted to the
optional log channel (IPs are not posted).

## Chat bridge

Set `chat-channel-id` in `config.properties`, then turn on either direction independently:

- `minecraft-to-discord=true` sends in-game chat, joins and leaves to the channel.
- `discord-to-minecraft=true` shows messages from the channel to everyone in game. This needs
  **Message Content Intent** switched on in the Developer Portal (your app → Bot), or the bot won't log in.

Chat that another plugin blocks (mutes, filters) is not sent to Discord. Mentions like `@everyone` never ping.

## Admin website

A web panel, built into the plugin, where admins log in with Discord. Only members with `admin-role-id` get in,
and the role is checked again every 5 minutes while they're logged in.

- **Linked**: every linked player with their Minecraft head, Discord avatar, names, UUID/ID and status
  (online with server, or offline with when they were last seen, e.g. "Offline · 3 days ago").
  Sort by name, online, recently seen, link date, suspended or admins. Names are **gold** for admins
  (their Discord account has `admin-role-id`) and **dark red** for suspended players.
- **Find any player**: the search box in the top bar finds everyone who has ever connected, linked or not,
  by Minecraft name, UUID, Discord name or Discord ID.
- **Player page** (click any name): profile with a NameMC link, Discord details, current suspension,
  staff notes, full suspension history, IP addresses with other accounts seen on the same IP, and the
  player's complete activity log.
- **Actions**: kick (online players), suspend (days + hours), edit a suspension (new length and reason),
  unsuspend, add a staff note, remove a link. All need a reason or note text.
- **Inventory** (player page, and the Inventory button on Moderate cards):
  - **Live**: the player's inventory, armour, offhand and ender chest with item icons, stack sizes,
    durability, enchantments, custom names and lore, plus what's inside shulker boxes; also their health,
    food, level, game mode and position. Refreshes every few seconds while open. Online players only;
    opening it is logged (the refreshes aren't).
  - **Snapshots**: a full copy of the inventory is saved when a player **dies** (with the death message),
    **joins**, **quits** or **changes world**, and whenever an admin clicks **Save snapshot**. Preview any
    snapshot and **restore** it (reason required, optionally including the ender chest). The player's
    current items are saved as a "Before restore" snapshot first, so a restore can be undone. If the player
    is offline, the restore is applied when they next join. Restores are logged and posted to the log
    channel. `inventory-snapshots-per-player` (default 100) sets how many are kept.
  - Needs the companion plugin. Item icons are loaded by your browser from the `minecraft-textures`
    package on jsDelivr.
- **Settings** (the gear icon, only visible to one person): change the plugin's settings from the browser:
  linking, Discord channels and roles, chat bridge, logging and how long logs are kept, snapshot limits and
  age, spy distance, website and Google Sheets. Changes are written into `config.properties` (comments kept)
  and most apply immediately; ones marked "needs restart" apply after the next proxy restart. Every change
  is logged. The bot token and client secret only show as set / not set and can't be changed here. A Data
  section shows the database size and has **Clean up now** and **Compact database** buttons.
  Only the Discord server's owner can see Settings, unless `web-owner-id` names someone else.
- **Retention**: `chat-log-retention-days` (default 90) and `log-retention-days` (default forever) delete
  old logs, and `inventory-snapshot-retention-days` old snapshots. Cleanup runs hourly.
- **Live updates**: every page updates as things happen (new log rows appear instantly; the Linked,
  Moderate and player pages refresh when someone joins, leaves, is suspended, and so on). The "Live" dot in
  the top bar shows the connection.
- **Logs**: every event, newest first, filterable and searchable, with **Download CSV** and
  **Export to Google Sheets** buttons (also on each player page for just that player).

Suspended players are kicked if online and can't join until the suspension ends, even with
`discordlinked.bypass`. Unlinked players can be suspended too (e.g. alts), which stops them joining once
they link. Every admin action is logged with a timestamp and who did it.

When `dm-players=true`, players get a Discord DM when they're suspended, kicked, unsuspended, their
suspension is edited, or staff unlink them. Edit or turn off each message (`discord.dm.*`) in
`messages.properties`. Players who block DMs from server members won't get them; that's only logged.
Notes are never sent to players.

### Moderate page & Spy

**Moderate** shows everyone online right now as a grid of cards (head, name, server, ping, Discord name),
filterable by server, with **Spy**, **Kick** and **Suspend** buttons.

**Spy** puts *your own* Minecraft character in spectator mode next to the player:

- Your Discord account must be linked, and you must be online on the server with that account.
  If the player is on a different server, you're moved there first and back afterwards.
- You must stay within `spy-max-distance` blocks (default 1000) of them. Going further, changing world or
  server, or the player leaving ends the session.
- You're hidden from the player you're spying on, and the action bar shows the distance and time.
- While a session runs, the website is locked to a session panel with **End session**, **Kick** and
  **Suspend**. Kick and suspend end the session, and "(observed via spy for 4m 12s)" is added to your
  reason automatically.
- When the session ends you're put back exactly as you were: location, game mode and flight. This also
  happens if you disconnect, or if the server crashes mid-session (fixed the next time you join).
- Every session's start and end, with its length and why it ended, is in the logs (**Spy sessions**
  filter) and the Discord log channel.

Spy and the inventory viewer need the small companion plugin on the Minecraft servers, because Velocity
itself can't teleport players, change game modes or see inventories:

1. Download **DiscordLinked** from the GitHub Actions run; the zip contains two jars.
2. `DiscordLinked-x.y.z.jar` goes in Velocity's `plugins/` folder as before.
3. `DiscordLinkedSpy-x.y.z.jar` goes in the `plugins/` folder of **every Paper, Purpur or Folia server**
   behind the proxy (1.20.4 or newer). It has no config. On Folia it schedules everything on each player's
   own region thread, so the admin and the player can be in different regions.

The proxy and the companion plugin talk over Minecraft's plugin messaging channel, and the proxy drops any
such message sent by a player's client, so players can't trigger it. As with any Velocity network, make
sure your backend servers can't be joined directly, bypassing the proxy.

### Setting it up

1. Set `admin-role-id` in `config.properties` (right-click the role → Copy Role ID) and restart.
2. Run **`/dla`** in Discord or in game. Admins get a one-time login link (works once, expires in 5
   minutes). That's it.

By default (`web-access=tunnel`) the panel gets a free **https://** address from a Cloudflare quick
tunnel: the plugin downloads Cloudflare's `cloudflared` program into `plugins/discordlinked/bin/` on first
start and runs it. No ports to open, no domain, no certificates. The panel itself only listens on
`127.0.0.1`. Things to know:

- The address (`https://<random-words>.trycloudflare.com`) changes whenever the proxy restarts, so
  everyone logs in again; `/dla` always gives the current address.
- Cloudflare offers quick tunnels free and without an account, but without an uptime guarantee.
- If your host doesn't allow running programs, the console says so; use `web-access=direct` instead.

With `web-access=direct` the panel is served over plain http on `web-port` (open it in your firewall). Set
`web-public-url` if you put it behind your own HTTPS reverse proxy/domain. In this mode you can also add a
"Log in with Discord" button: set `discord-client-secret` and add `<web-public-url>/callback` under
Developer Portal → OAuth2 → Redirects.

### Google Sheets export (optional)

The **Download CSV** button needs no setup: open the file in Google Sheets with File → Import.
For one-click export straight into a spreadsheet:

1. Go to https://console.cloud.google.com, create a project (any name), then open
   **APIs & Services → Library**, search **Google Sheets API** and click **Enable**.
2. **APIs & Services → Credentials → Create credentials → Service account**. Give it any name and click
   **Done** (no roles needed). Open it, go to **Keys → Add key → Create new key → JSON**. A file downloads.
3. Put that file in `plugins/discordlinked/` as `google-service-account.json`. Treat it like a password.
4. Create a Google spreadsheet, click **Share**, and add the service account's email (the `client_email`
   in the file, ending in `.iam.gserviceaccount.com`) as an **Editor**.
5. Copy the spreadsheet ID from its URL (`docs.google.com/spreadsheets/d/<ID>/edit`) into
   `google-sheets-spreadsheet-id` in `config.properties`, and restart the proxy.

Each export adds a new tab named after the filter and time. Up to 50,000 rows per export.

## Customizing messages

Every message players or Discord users see is in `plugins/discordlinked/messages.properties`.

- `minecraft.*` keys use [MiniMessage](https://docs.advntr.dev/minimessage/format.html), with placeholders like `<player>`.
- `discord.*` keys use Discord markdown, with placeholders like `{player}`.
- Leave a log or bridge message empty to turn it off (e.g. `discord.bridge.join=`).
- Keys missing from your file use the built-in default, so updating the plugin won't break your file.

Restart the proxy after editing.

## Commands

Discord:

| Command | Who | What |
|---|---|---|
| `/link <code>` | everyone | Link your Minecraft account |
| `/unlink` | everyone | Unlink your own account |
| `/whois [player] [user]` | Moderate Members | Show a link |
| `/history <player>` | Moderate Members | Last 20 tracked events |
| `/forceunlink <player>` | Moderate Members | Remove someone's link |
| `/dla` | admin role | One-time login link for the admin website |

Admin command visibility can be changed in Server Settings → Integrations → your bot.

In game / console (`discordlinked.admin`): `/dlink lookup <player>`, `/dlink unlink <player>`.

`/dla` (in Discord and in game): a one-time login link for the admin website. Anyone can run it; only
members whose Discord account has `admin-role-id` get a link.

## Building

Requires Java 21.

```
./gradlew build        # Windows: gradlew.bat build
```

The plugin jar is `build/libs/DiscordLinked-0.9.0.jar`. Every push also builds on GitHub Actions; download
the jar from the run's **Artifacts** section.

## Setup

1. Create an application at https://discord.com/developers/applications, open **Bot**, reset and copy the token.
2. Under **OAuth2 → URL Generator** tick `bot` and `applications.commands`, plus the `Manage Roles`,
   `Send Messages` and `Embed Links` permissions. Open the URL and add the bot to your server.
3. Drop the jar in Velocity's `plugins/` folder and start the proxy once. It creates
   `plugins/discordlinked/config.properties` and `messages.properties`.
4. Fill in `bot-token` and `guild-id` (and optionally `linked-role-id`, `log-channel-id`), then restart.
   If you use a linked role, drag the bot's role above it in Server Settings → Roles.
