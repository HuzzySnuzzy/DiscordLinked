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

- **Linked**: every linked player with their Minecraft head, Discord avatar, names, UUID/ID and online status.
  Buttons to **remove a link**, **suspend** (days + hours) or **unsuspend**. All three need a reason.
- **Logs**: every event, newest first, filterable (admin actions, suspensions, joins, chat, commands, denied
  logins, website logins) and searchable by player or admin.

Suspended players are kicked if online and can't join until the suspension ends, even with
`discordlinked.bypass`. Every admin action is logged with a timestamp and who did it.

### Setting it up

1. Developer Portal → your app → **OAuth2**: copy the **Client Secret** (reset it if needed), and under
   **Redirects** add `<web-public-url>/callback`, e.g. `http://123.45.67.89:8080/callback`.
2. In `config.properties` set `web-enabled=true`, `web-port`, `web-public-url`, `discord-client-secret` and
   `admin-role-id`.
3. Open the port in your firewall / host panel, restart the proxy, and visit `web-public-url`.

Over plain `http://` the login cookie isn't encrypted in transit. For anything beyond testing, put the panel
behind HTTPS (e.g. a reverse proxy such as Caddy or nginx, with `web-bind-address=127.0.0.1`) and use the
`https://` address as `web-public-url`.

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

Admin command visibility can be changed in Server Settings → Integrations → your bot.

In game / console (`discordlinked.admin`): `/dlink lookup <player>`, `/dlink unlink <player>`.

## Building

Requires Java 21.

```
./gradlew build        # Windows: gradlew.bat build
```

The plugin jar is `build/libs/DiscordLinked-0.3.0.jar`. Every push also builds on GitHub Actions; download
the jar from the run's **Artifacts** section.

## Setup

1. Create an application at https://discord.com/developers/applications, open **Bot**, reset and copy the token.
2. Under **OAuth2 → URL Generator** tick `bot` and `applications.commands`, plus the `Manage Roles`,
   `Send Messages` and `Embed Links` permissions. Open the URL and add the bot to your server.
3. Drop the jar in Velocity's `plugins/` folder and start the proxy once. It creates
   `plugins/discordlinked/config.properties` and `messages.properties`.
4. Fill in `bot-token` and `guild-id` (and optionally `linked-role-id`, `log-channel-id`), then restart.
   If you use a linked role, drag the bot's role above it in Server Settings → Roles.
