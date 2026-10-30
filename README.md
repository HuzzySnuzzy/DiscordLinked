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

## Community: interactions & credits (DiscordLinkedFAD)

**DiscordLinkedFAD** is a second companion plugin for your **Paper or Folia** servers (1.21.4 or newer).
Put `DiscordLinkedFAD-x.y.z.jar` in the `plugins/` folder of every Minecraft server behind the proxy. The
proxy (this plugin, 0.10.0 or newer) keeps the credits, so they're the same on every server.

**Interactions.** `/pet`, `/hug`, `/kiss`, `/boop`, `/nuzzle`, `/cuddle`, `/pounce`, `/stepon`, `/lick`,
`/nom`, `/meow`, `/bark`, `/purr`, `/howl`, `/wag`, `/blush` and `/growl` out of the box. Each picks a random
line, shown to everyone on every server, and plays a sound and particles where it happens (`/pounce` makes you
leap at them). For example `/kiss Bob` might show

> Bob's face lights up when Alice presses their lips against theirs, mwahhhh!~

and in Discord (in `fad-channel-id`, or the chat channel), with the linked accounts as @mentions:

> @Bob's face lights up when @Alice presses their lips against theirs, mwahhhh!~

- Everything about them is in `plugins/DiscordLinkedFAD/config.yml`: add your own (each becomes a command),
  change the lines, sounds, particles, aliases and cooldown. Run `/discordlinkedfad reload` after editing;
  brand-new commands need a server restart. Use the same `config.yml` on every server.
- The same interactions are **slash commands in Discord** (`/hug @someone`), shown in game as
  `[Discord] …`. If the member they picked is online, the sound and particles play where they are.
- `/interactions` lists them; **`/interactions off`** stops anyone picking you, in game and on Discord
  (`/interactions enabled:False` on Discord).
- Mentions don't ping anyone unless `fad-discord-ping=true`.
- Per-interaction permission: `discordlinkedfad.interact.<name>` (everyone has them unless you take one away).
  `discordlinkedfad.cooldown.bypass` skips the cooldown.

**Credits.** Call them anything: `credits-name` / `credits-name-plural` in `config.properties`, or on the
website's Settings page (e.g. Pawbuck / Pawbucks). Players earn them for:

- **Playing**: `credits-playtime-amount` for every `credits-playtime-minutes` of *active* play (default 5 per
  15 minutes). AFK time doesn't count: after `afk-minutes` (in DiscordLinkedFAD's config.yml, default 5)
  without looking around, chatting or using commands, a player is AFK. AFK pools and water streams don't count
  as activity.
- **Advancements**: the first time a player makes one (on any server), by its kind: task 10, goal 25,
  challenge 50 by default. Only advancements announced in chat count, so recipe unlocks never do.
  Advancements are also announced in Discord (`fad-discord-advancements`).

`/credits`, `/credits <player>` and `/credits top` in game; `/credits [player]` and `/leaderboard` in
Discord. Admins can give or take credits on a player's page (with a reason; it's logged).

**On the website.** The **Community** tab (for everyone) has two parts. **Leaderboards**: totals and the top
100 by credits, microcredits, credits earned, active playtime, login streak, advancements, or interactions given
or received, with a podium for the top three and your own place even when you're further down. **Interactions**:
recent ones and every interaction command with how often it's used. Each player page shows their balance,
playtime, advancements and favourite interactions; admins also see AFK time and the credit history. Interactions, advancements and credit changes are in the Logs (**Interactions, advancements &
credits** filter); interactions count as chat for `chat-log-retention-days`.

**Daily login rewards and streaks.** The first join of each day (server time) pays `daily-reward-base`
credits plus `daily-reward-per-day` for every day of the player's streak after the first, capped at
`daily-reward-max` (defaults 5, +2 a day, up to 30). Joining every day keeps the streak going; missing a day
starts it again at 1. Every profile on the website shows the streak (always in days) and the best one so far,
and the badge gets wilder the longer it is: plain, then orange, glowing, animated fire (14+), blue flame
(30+), rainbow (60+) and a crowned, shining rainbow from 100 days. From `streak-announce-days` (default 3) on,
the Discord join message shows it: ":green_circle: **Huzzeh** joined the server :fire: 4 day streak".

**Shop.** Players spend credits on Minecraft items on the website's **Shop** page:

- Items are laid out like the creative inventory (Building Blocks, Colored Blocks, Natural Blocks, Functional
  Blocks, Redstone, Tools & Utilities, Combat, Food & Drinks, Ingredients), with a search box. Hovering an item
  shows its price for one; holding **Shift** shows the price for a stack (64, or 16 for things that stack to 16).
- It works like a Minecraft inventory: you hold a stack on the mouse. In the item list (like the creative tabs)
  left-click picks one up (again for more), right-click a full stack, shift-click sends a stack straight to the
  chest. In the order chest below, left-click picks up / puts down / merges / swaps, right-click picks up half
  or puts down one, holding a button and dragging across slots spreads the stack evenly (right button: one per
  slot), double-click gathers matching stacks, shift-click sends a slot back. Esc or clicking outside puts the
  held stack back. You can also press on an item and let go over a slot. On phones: tap to pick up and tap a
  slot to put down, or tap an item and use **+1** / **+64**.
- **Check out** takes the credits and puts the order in the player's inventory as **one chest holding the
  items** (named "Shop order"). They pay for the chest too. They must be online on a server with
  DiscordLinkedFAD and have a free inventory slot; otherwise nothing is charged. Every order (and every refused
  one) is in the Logs (**Shop orders** filter) and in the player's credit history.
- Only items that exist in your servers' Minecraft version are offered. Things that can't be had in survival,
  or only make sense with extra data (spawn eggs, operator blocks, enchanted books, potions, maps) aren't sold.
- Prices are balanced against what players earn (by default 20 credits per hour of active play): about what
  it takes to gather the item yourself, e.g. a stack of cobblestone ≈ 2, a stack of oak logs 16, an iron ingot
  1.2, a diamond 8, a diamond pickaxe 26, an elytra 600. They're generated by `tools/shop_prices.py` from a
  table of raw resource values plus recipes.
- **Gifts**: next to Check out, pick **Gift to …** to send the chest to another player instead. Only players
  who are on the Minecraft server right now can get gifts (the buyer doesn't need to be on). The chest is
  named "Gift from <buyer>" and the receiver is told in chat.
- The settings owner controls it: **Shop open** and **Price multiplier (%)** (`shop-enabled`,
  `shop-price-percent`) on the Settings page, and on the Shop page **Edit items** turns single items off or
  gives them their own price percentage.

**Market: selling for microcredits.** Players sell items in game for **microcredits**, a second currency with
its own leaderboard, made for people building farms and factories. Needs DiscordLinked and DiscordLinkedFAD
0.16.0 or newer.
- `/sell` opens a double chest: put items in and close it (or click **Sell now**) to sell them. The bottom row
  shows what it's all worth as you go; **Cancel** gives everything back. Leaving the game, dying or teleporting
  with it open gives everything back too, so nothing is sold by surprise.
- `/sellhand` sells what you hold, `/sellhand all` every one of that item in your inventory (not armour or the
  off hand). `/worth` shows what the held item sells for.
- Items sell for **10%** of their Shop price (`sell-price-percent`, at most 100, so buying from the Shop and
  selling back never pays). Only plain items sell: anything enchanted, renamed, damaged, filled or with potion
  effects is given back.
- **1,000 microcredits = 1 credit** (`microcredits-per-credit`). Sell prices follow the rate, so changing it
  doesn't change what an item is worth in credits. `/exchange <amount|all>` in game, or Shop → **Sell &
  exchange** on the website, turns microcredits into credits. It only goes that way.
- `/mct` (`/microcredittop`) and `/mct` in Discord show the leaderboard. It ranks by what players hold right now
  (`microcredits-leaderboard=balance`, so exchanging costs rank) or by everything ever sold (`earned`).
- Optional **dropping prices** (`sell-price-drop`, off by default): the more of one item everyone sells, the less
  it's worth. It sells for half once `sell-price-drop-after` credits' worth of it was sold (default 100), and
  recovers halfway every `sell-price-recovery-hours` (default 24). A sale pays the average price over the
  amount, so splitting a sale up doesn't pay more.
- Every Monday, last week's top sellers are posted to the interactions channel (`market-weekly-post`).
- On the website, Shop → **Sell & exchange** has exchanging and every item's sell price, and the leaderboard is
  under Community → Leaderboards → Microcredits. Player pages show their microcredits and rank; staff also see
  the sale history. The settings owner can turn selling off for single items or give them their own sell
  percentage in the Shop's **Edit items**. Sales and exchanges are in the Logs (**Sales & exchanges** filter).
- If another plugin (like EssentialsX) already has `/sell` or `/worth`, rename DiscordLinkedFAD's commands in
  its config.yml, or use `/discordlinkedfad:sell`.

**Groups: sharing microcredits.** Players team up on the website (Community → **Groups**):

- Anyone linked can make a group and name it anything (up to 32 characters; names are unique). A player is in
  one group at most.
- The leader invites players by Minecraft name; they're told in game and accept or decline on the website.
  `group-max-members` (default 10, 0 = no limit) caps the size.
- Members share one balance: everything they earn by selling goes into the group, and any member can exchange
  it for credits (in game or on the website). Their own microcredits move into the group when they join.
- The leader sees the **group log**: who sold, exchanged, joined and left, with each change and the balance
  after it. The leader can also rename the group, hand the lead to someone else, remove members and end it.
- Money moves fairly: leaving, or being removed, takes an equal share out (balance ÷ members). Ending a group
  splits the whole balance equally across its members (anything that doesn't divide evenly goes to the leader).
- On the microcredit leaderboard (website, `/mct` in game and in Discord) a group is one place, with its
  members' heads. Profiles show "shared in <group>" under their microcredits.
- Admins see every group's log and can **take a group down** (with a reason): it ends and its balance is
  split equally across the members, who are told why. Everything is in the Logs (**Groups** filter).
- `groups-enabled=false` stops new groups and members; existing groups keep their balance.

**Cosmetics.** Shop → **Cosmetics** on the website sells things that aren't items, for credits only. They're
bought once and kept; players switch them on and off under **Your unlocks** on their own profile (click your
name in the top bar). One of each kind is on at a time, and a new purchase is switched on straight away. Needs
DiscordLinkedFAD 0.18.0 or newer on the Minecraft servers (0.20.0 for auras and particle looks).

- **Prefixes** in front of the player's name in chat and the tab list, with `&` colour codes (`&b[Builder]`,
  `&6&l[Tycoon]`, hex as `&#ff88cc`). With **LuckPerms** on a server they're LuckPerms prefixes, so every chat
  and tab plugin that shows LuckPerms prefixes shows them. They're temporary (never saved in LuckPerms) and get
  priority `cosmetics.prefix-priority` (DiscordLinkedFAD's config.yml, default 150). LuckPerms shows the
  highest-priority prefix: put it above your rank prefixes to replace them while it's on, or below them to only
  show it for players without one (or set up LuckPerms' prefix stacking to show both). Without LuckPerms the
  prefix goes in front of the player's display name.
- **Trails**: particles behind the player while they walk (hearts, flames, music notes, cherry petals, snow,
  …). **Auras**: particles around them while they stand still (shown after half a second without moving).
  Nothing shows while they're in spectator mode, invisible or vanished.
- Trails and auras are bought per particle type, and buying one gives every look of it: next to it under **Your
  unlocks**, one menu picks the look (**Default**, the particle's own look, or cloud, ring at the feet, halo, orbit, helix or wings, each still,
  spinning, pulsing (wings flap) or rising). Particles Minecraft can colour, **Coloured dust** and **Potion
  swirls**, also get a colour menu (white, rainbow, a preset or any custom colour); every other particle always
  keeps its own colour. Changes show in game straight away, and the
  previews on the website use the same shapes. `cosmetics.trail-interval-ticks` (how often they're drawn,
  default 2) and `cosmetics.trail-particles` (how many particles a cloud has) are in DiscordLinkedFAD's config.yml.
- **Profile cards**: a picture behind the player's name in the Players list, fading out to the right, and at
  the top of their profile. The settings owner makes them: Shop → Cosmetics → **Edit** → **New profile card**,
  drop in a PNG, JPEG or GIF (up to 8 MB) and set a price. Pictures are re-saved (only the pixels are kept) and
  shrunk to at most 1600×900; they're stored in `plugins/discordlinked/cards/` and only shown to logged-in
  visitors.
- **KeepInventory** (Quality of life): players keep their items and XP when they die, but can't hurt other
  players while it's on (hits, arrows, tridents, their pets, fire, harmful potions). It starts working
  `keep-inventory-delay-minutes` after it's switched on (default 5), so it can't be switched on in the middle
  of a fight.
- The shop starts with a few prefixes, nine trails, eight auras (including coloured dust and potion swirls) and KeepInventory; they're only examples. In **Edit**
  (Shop → Cosmetics → **Edit cosmetics**, or **Edit cosmetics** in Settings → Shop) the settings owner adds
  new ones, changes names, prefix texts, trail particles and prices, takes things off sale (owners keep them)
  or deletes them (everyone who paid gets their credits back). A player's profile has **Give for free** and **Take away** for
  admins. Purchases are in the credit history and everything is in the Logs (**Cosmetics** filter).
- `cosmetics-enabled=false` stops new purchases; what players already have keeps working.

## Website

A web panel, built into the plugin, where people log in with Discord. Roles are checked again every 5 minutes
while they're logged in.

- **Everyone in your Discord server** (`web-member-access=true`, the default) gets the community view:
  **Home** (who's online and on which server, member and staff counts, newest members, recently online),
  **Players** (the list and simple profiles: Minecraft and Discord identity, member since, online or last seen,
  credits, playtime and advancements), **Community** (leaderboards, groups and interactions) and the **Shop** (items,
  cosmetics, and selling & exchanging), the last two when DiscordLinkedFAD's features are on. Nothing administrative: no IPs, notes, suspensions, inventories,
  logs, AFK time or credit history. The server enforces this, not just the page.
- **Admins** (`admin-role-id`) get the admin view below. The **On duty** switch in the top bar (on by
  default) flips them to the community view and back; switching is logged.
- IP addresses are blurred for admins until you hover (or tap) them, so they don't show on a shared or
  streamed screen.

- **Players**: everyone who has played, linked or not, also from before DiscordLinked was installed
  (DiscordLinkedSpy sends each server's player data, names and first and last time played, to the proxy when
  someone joins that server, at most every 12 hours). Each row has the Minecraft head, Discord avatar, names,
  UUID/ID, status (online with server, or offline with when they were last seen, e.g. "Offline · 3 days
  ago"), login streak and the action buttons. Show everyone, online, linked, not linked, staff or suspended
  players; sort by recently seen, online, name, join date, longest streak, admins or suspended; filter by name
  or ID. Names are **gold** for admins (their Discord account has `admin-role-id`) and **dark red** for
  suspended players. People who used the website with Discord but never linked a Minecraft account are in the
  list too, as **Website only** (under Everyone and Not linked), with when they first and last visited.
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
- **Live updates**: every page updates as things happen (new log rows appear instantly; the Players, Home,
  Moderate, Community, Shop and player pages refresh when someone joins, leaves, is suspended, and so on). The "Live" dot in the top bar shows the connection. Pages ask the server for news with long
  polling, which works through the automatic https address (Cloudflare quick tunnels can't carry
  Server-Sent Events) and any other proxy.
- **On the website**: next to Online/Offline, players who have this website open right now get an
  "On the website" status (lists and player pages, updated live; it clears about 45 seconds after they close it).
- **Your own address**: Settings → **Your own address** puts the website on your own domain, e.g.
  `dla.example.nl`, instead of an address that changes on every restart (the part before the domain is up to
  you). Type it in and click **Connect with Cloudflare**; open the link that appears, log in, pick the domain and
  click Authorize. The plugin then creates a permanent Cloudflare tunnel and the DNS record by itself, and
  switches over without downtime (the old address keeps working for a minute and a half, and **Open the new
  address** logs you in there). The domain's DNS must be on Cloudflare (free plan is fine). **Change address**
  moves it, **Disconnect** removes the tunnel and goes back to the automatic address. If the tunnel ever can't
  connect, the website falls back to an automatic address so you're never locked out. The tunnel's files are in
  `plugins/discordlinked/cloudflare/` (keep them private). Prefer the Cloudflare dashboard? Make a tunnel there
  (Zero Trust → Networks → Tunnels) with the public hostname `<web-domain-prefix>.<web-domain>` and service
  `http://localhost:<web-port>`, and put its token in `web-tunnel-token`.
- **Teleports**: on a player page, **Teleport to them** puts your own Minecraft character where they are (or,
  if they're offline, where they logged out) and **Teleport to their bed** at their bed or respawn anchor,
  moving you to their server first if needed. Needs DiscordLinkedSpy 0.14+ on the servers; logout spots are
  saved from then on (older ones come from the server's own player data). Logged as **Teleport**.
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

1. Download **DiscordLinked** from the GitHub Actions run; the zip contains three jars.
2. `DiscordLinked-x.y.z.jar` goes in Velocity's `plugins/` folder as before.
3. `DiscordLinkedSpy-x.y.z.jar` goes in the `plugins/` folder of **every Paper, Purpur or Folia server**
   behind the proxy (1.20.4 or newer). It has no config. On Folia it schedules everything on each player's
   own region thread, so the admin and the player can be in different regions. It also tells the proxy
   who has ever played on that server, for the website's Players list.
4. `DiscordLinkedFAD-x.y.z.jar` (optional) goes next to it for interactions and credits; see
   [Community](#community-interactions--credits-discordlinkedfad).

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

The same button works with **Your own address** (or a `web-tunnel-token` tunnel): set `discord-client-secret`
(Developer Portal → OAuth2 → Client Secret), add `https://<your address>/callback` under OAuth2 → Redirects
(Settings → Your own address shows it with a copy button), and restart the proxy. It can't work on the
automatic trycloudflare address, because Discord only sends people back to addresses listed there.

### Discord message log (settings owner only)

A read-only log of every Discord channel the bot can see. Turn it on with **Discord message log** in
Settings → Logging & data (or the button on its page, `message-log-enabled`) and restart the proxy. From then on
the bot saves every new message (Discord doesn't give bots old history). It only listens: it never posts,
reacts or leaves anything in those channels. Channels the bot can't see aren't logged, so leave it out of
channels you don't want logged.

Settings → **Discord message log** lists the channels. Pick one to read it like in Discord: newest at the
bottom, **Load older messages** at the top, and a search box for the whole channel. Edited messages keep their
first version, and deleted messages stay, marked as deleted. Only the settings owner can open the page.

- Needs **Message Content Intent** (Developer Portal → your app → Bot → Privileged Gateway Intents), or the bot
  can't log in with the log turned on.
- The log goes back `message-log-retention-days` (default 7; older messages are deleted, 0 = forever).
- Attachments are saved as links; Discord's attachment links stop working after a while.
- Tell your members the server logs messages (e.g. in the rules channel).

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
| `/dla` | everyone | One-time login link for the website |
| `/credits [player]`, `/leaderboard` | everyone | Credits (with DiscordLinkedFAD) |
| `/hug @someone`, `/kiss`, `/meow`… | everyone | Interactions from DiscordLinkedFAD's config.yml |
| `/interactions enabled:<True/False>` | everyone | Let others pick you for interactions, or not |
| `/mct [player]` | everyone | Microcredit leaderboard (with selling on) |

Slash commands only work in the **command channels** (`command-channel-ids`, Settings → Discord → Command
channels, several separated by commas). Empty = the chat bridge channel; if there's no chat channel either,
anywhere. Threads count as their channel, and admins (the admin role or Timeout Members permission) can use
commands anywhere. Elsewhere the bot answers, only to that person, which channels to use.

Admin command visibility can be changed in Server Settings → Integrations → your bot.

In game / console (`discordlinked.admin`): `/dlink lookup <player>`, `/dlink unlink <player>`.

In game with DiscordLinkedFAD: every interaction (`/hug <player>`…), `/interactions [off|on]`,
`/credits [player|top]`, `/sell`, `/sellhand [all]`, `/worth`, `/mct`, `/exchange <amount|all>`, and
`/discordlinkedfad reload` (op).

`/dla` (in Discord and in game) is the way to the website, for everyone: a one-time login link. Admins get the
admin view; with `web-member-access` on, everyone else in the Discord server gets the community view, linked to
Minecraft or not. Players who haven't linked Discord get the website's address in game and log in there with
Discord (or, without `discord-client-secret`, are told to run `/dla` in Discord).

A few seconds after joining, players see their credits (and microcredits, when selling is on) and a clickable
**[/dla]** reminder for the website. On the first join of the day the daily reward message already shows their
credits, so the balance line is skipped unless there are microcredits to show. Change or turn off these lines
(`minecraft.join.*`, empty = off) in `messages.properties`.

## Building

Requires Java 21.

```
./gradlew build        # Windows: gradlew.bat build
```

The jars are `build/libs/DiscordLinked-0.21.1.jar` (Velocity), `spy-paper/build/libs/DiscordLinkedSpy-0.21.1.jar`
and `fad-paper/build/libs/DiscordLinkedFAD-0.21.1.jar` (Paper/Folia). Every push also builds on GitHub Actions;
download them from the run's **Artifacts** section.

## Setup

1. Create an application at https://discord.com/developers/applications, open **Bot**, reset and copy the token.
2. Under **OAuth2 → URL Generator** tick `bot` and `applications.commands`, plus the `Manage Roles`,
   `Send Messages` and `Embed Links` permissions. Open the URL and add the bot to your server.
3. Drop the jar in Velocity's `plugins/` folder and start the proxy once. It creates
   `plugins/discordlinked/config.properties` and `messages.properties`.
4. Fill in `bot-token` and `guild-id` (and optionally `linked-role-id`, `log-channel-id`), then restart.
   If you use a linked role, drag the bot's role above it in Server Settings → Roles.
