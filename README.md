# ServerCore

The core plugin for **LilMonkeySMP** (`lilmonkeysmp.eagler.host`): an all-in-one plugin for **Spigot / Paper 1.8.8** servers:

- **Maintenance mode**: only ops, chosen ranks and whitelisted players can join.
- **Advanced economy** built on top of **EssentialsX** through Vault. Banks with interest, banknotes, dynamic sell prices, an auction house, bounties, a lottery, daily rewards, payday salaries and a full transaction log.
- **Server essentials**: sidebar scoreboard, auto-announcer and staff chat.

## Requirements

| Plugin | Required? | Why |
|---|---|---|
| [Vault](https://www.spigotmc.org/resources/vault.34315/) | **Yes** (for economy) | Connects ServerCore to the economy and to your permission plugin |
| [EssentialsX](https://essentialsx.net/) (2.19/2.20 for 1.8.8) | **Yes** (for economy) | Stores wallet balances (`/bal`, `/baltop`, `/eco` keep working) |
| A permissions plugin (LuckPerms, PermissionsEx, GroupManager, ...) | Recommended | Lets maintenance mode check **ranks** |
| PlaceholderAPI | Optional | `%placeholders%` in the scoreboard and announcer |

Without Vault/EssentialsX the economy commands are disabled, but maintenance mode, the scoreboard, the announcer and staff chat still work.

## Hosting on Eaglercraft (e.g. eagler.host)

The plugin targets 1.8.8, so it works with EaglercraftX 1.8 browser players. A few things to know:

- **Accounts:** Eaglercraft servers run in offline (cracked) mode, so anyone can join with any username, including a staff member's. Use a login plugin (EaglerXServer's built-in auth, or AuthMe) so nobody can impersonate an op, a staff rank or a maintenance-whitelisted name.
- **IP forwarding:** If players connect through an EaglerX/BungeeCord proxy, turn on IP forwarding (`ip_forward: true` on the proxy, `bungeecord: true` in `spigot.yml`) so bans and the bounty same-IP check see real IPs. ServerCore ignores local/proxy addresses, so bounties still work without it.
- **Voice chat:** Browser players can't install mods, so Simple Voice Chat only works for Java players using its mod. Use **OpenAudioMc** instead: install it next to ServerCore, and players type `/audio`, open the link in a new tab and allow their microphone. The free tier allows 10 people in voice at once. The LuckPerms setup already includes its permissions. ServerCore's `/maintenance` doesn't use the `/mm` alias, so `/mm` stays OpenAudioMc's mic mute.

## Building

```bash
mvn package
```

The jar is written to `target/ServerCore-1.0.0.jar`. Every push also builds automatically on GitHub Actions. Download the jar from the **Build** workflow run's artifacts.

## Installing

1. Put `Vault.jar`, `EssentialsX.jar` and `ServerCore-1.0.0.jar` in `plugins/`.
2. Start the server once. `plugins/ServerCore/` will contain `config.yml`, `messages.yml` and `prices.yml`.
3. Set the ranks allowed during maintenance (`maintenance.allowed-ranks`), and tune the economy settings.
4. Run `/servercore reload` (or restart).

Data is stored in SQLite (`plugins/ServerCore/data.db`) by default. Switch `storage.type` to `mysql` for networks.

---

## Maintenance mode

| Command | Description |
|---|---|
| `/maintenance on [duration] [reason]` | Enable now, e.g. `/maintenance on 2h Updating plugins` (auto-disables after 2h) |
| `/maintenance off` | Disable |
| `/maintenance schedule <delay> [duration] [reason]` | Countdown broadcast, e.g. `/maintenance schedule 10m 1h Map reset` |
| `/maintenance cancel` | Cancel a scheduled maintenance |
| `/maintenance extend <duration>` | Push the end time back |
| `/maintenance reason <text>` | Change the reason shown in the kick screen / server list |
| `/maintenance add\|remove <player>` | Maintenance whitelist |
| `/maintenance list` / `status` | Show whitelist, allowed ranks and status |

**Who can join while it's on:**
1. Server operators
2. Players with `servercore.maintenance.bypass`
3. Members of any rank in `maintenance.allowed-ranks` (checked through Vault, so it works with LuckPerms, PEX, GroupManager, ...). You can also give a rank `servercore.maintenance.rank.<rank>`.
4. Players on the maintenance whitelist

Everyone else is kicked (after `kick-delay-seconds`) and blocked from joining with a custom message. The server list shows a maintenance MOTD with the reason and remaining time. Staff with `servercore.maintenance.notify` see who tried to join. The state survives restarts.

## Economy

Wallet money lives in **EssentialsX** (through Vault), so everything stays in sync with `/bal`, `/baltop`, `/eco` and other plugins. ServerCore adds:

| Feature | Commands | Highlights |
|---|---|---|
| **Payments** | `/pay <player> <amount>`, `/pay confirm`, `/paytoggle` | Replaces Essentials' `/pay` (Essentials defers automatically, or set `pay.override-essentials: false`). Optional tax, cooldown, confirmation for big payments, offline payments, amounts like `2.5k`/`1m`. |
| **Bank** | `/bank` (GUI), `/bank deposit\|withdraw <amount\|all>`, `/bank transfer <player> <amount>`, `/bank upgrade`, `/bank tiers`, `/bank top` | Tiered accounts with capacity limits and **interest**. No interest for AFK players (EssentialsX AFK + own inactivity check). Withdraw/transfer fees. |
| **Banknotes** | `/banknote <amount> [count]`, `/banknote lookup <id>` | Turn money into tradeable paper notes. Each note has a unique id and can be redeemed once. **Duplicated notes are confiscated and staff are alerted.** |
| **Sell shop** | `/sell` (GUI), `/sell hand\|handall\|all`, `/worth [item]` | Prices in `prices.yml`. **Dynamic pricing**: prices drop as the server sells an item and recover over time. Rank sell multipliers. Damaged tools sell for less. |
| **Auction house** | `/ah`, `/ah sell <price>`, `/ah mine`, `/ah claim` | GUI with pages and sorting, listing fee, sales tax, per-rank listing limits, expiry and item collection, blacklist. |
| **Daily reward** | `/daily` | Streak bonus, milestone rewards, rank multipliers. |
| **Payday** | automatic | Salary per rank for every X minutes of *active* playtime. |
| **Bounties** | `/bounty set\|list\|check\|remove` | Pooled bounties, tax, anti-abuse (contributors and same-IP killers can't claim). |
| **Lottery** | `/lottery`, `/lottery buy <n>` | Timed draws, jackpot, house cut, refund if too few players. |
| **Transaction log** | `/transactions [player] [page]` | Every money movement, including EssentialsX `/eco` and `/pay`. |
| **Admin** | `/ecostats`, `/ecoadmin bank\|resetdaily\|sellreset\|purgelogs` | Economy overview of the last 24h (money created vs destroyed), balance fixes. |

**Money sinks** to fight inflation are built in and configurable: payment tax, bank fees, bounty tax, lottery house cut, auction fees/tax, and dynamic sell prices.

## Discord link reward (DiscordSRV)

Players who link their Discord account with DiscordSRV (`/discord link`) get a one-time reward, $100 by default.

1. In `plugins/DiscordSRV/config.yml`, set:
   ```yaml
   MinecraftDiscordAccountLinkedConsoleCommands: ["servercore linkreward %minecraftuuid% %discordid%", "", ""]
   ```
2. Restart the server, or run `/discord reload`.
3. Change the amount, add extra commands (for example a rank or a permission) or turn off the broadcast under `discord-link-reward` in ServerCore's `config.yml`.

Each Minecraft account **and** each Discord account can only get the reward once, so unlinking and relinking, or linking alts to the same Discord, gives nothing. The reward shows up in `/transactions`.

## Backups

ServerCore zips the worlds, important plugin folders (ServerCore, LuckPerms, Essentials, DiscordSRV, LoginSecurity, Jobs, ...) and the server's ops/whitelist/ban lists every 12 hours. They go into the `backups/` folder in the main server directory, and only the newest 2 are kept.

- `/servercore backup` makes one now; `/servercore backup list` lists them.
- While a backup runs, world saving is paused and the zip is written in the background, so the copy is consistent and the server keeps running.
- It checks free disk space first and never fills the disk.
- **The backups are on the same disk as the server.** Download the newest zip from your host's file manager every now and then, so a host problem can't take your backups with it.
- To restore, stop the server and replace the world/plugin folders with the ones from the zip.

Settings are under `backups` in `config.yml`.

## Chat prefixes

ServerCore formats chat with each player's LuckPerms rank prefix, for example `[Owner] Killercats2154: hi`. It reads the prefix through Vault, so LuckPerms and Vault must be installed.

- Change the look under `chat-format` in `config.yml`, and use `/servercore reload` to apply it. Placeholders: `{prefix}`, `{suffix}`, `{displayname}`, `{name}`, `{group}`, `{world}`, `{message}` and PlaceholderAPI placeholders.
- `group-formats` can give a rank its own chat format.
- Players with `essentials.chat.color` (VIP and staff in the rank setup) can use `&` color codes in their messages.
- **Don't install EssentialsX Chat at the same time.** If it's installed, ServerCore leaves chat alone and logs a warning.

## Chat protection

- **Cooldown:** 1.5 seconds between messages.
- **Repeats:** the same message twice within 30 seconds is blocked.
- **Advertising:** IP addresses and server/website addresses are blocked, including tricks like `name . com` or `name(dot)host`. `lilmonkeysmp.eagler.host` is allowed. Staff with `servercore.chatguard.notify` see who tried.
- **CAPS:** messages that are mostly capital letters are turned into lowercase.
- **Word filter:** add words to `chat-guard.blocked-words` and they're replaced with `***`.
- Private messages (`/msg`, `/r`, `/mail`, ...) are checked for advertising and blocked words too.
- Staff with `servercore.chatguard.bypass` aren't affected.

Settings are under `chat-guard` in `config.yml`.

## Other features

- **Scoreboard**: flicker-free sidebar with `{rank}`, `{balance}`, `{bank}`, `{bounty}`, `{lottery}`, `{streak}`, `{online}` and PlaceholderAPI. `/scoreboard` toggles it per player.
- **Announcer**: rotating or random broadcasts.
- **Staff chat**: `/sc <message>` or `/sc` to toggle.

## Rank perks (permissions)

Give these to your ranks in your permissions plugin. They only count when **explicitly granted**, so operators don't get every perk automatically.

| Permission | Effect |
|---|---|
| `servercore.maintenance.bypass` | Join during maintenance |
| `servercore.sell.multiplier.<vip\|mvp\|elite>` | Sell price multiplier (`sell.multipliers`) |
| `servercore.daily.rank.<vip\|mvp\|elite>` | Daily reward multiplier |
| `servercore.payday.rank.<rank>` | Payday salary (`payday.ranks`; everyone has `default`) |
| `servercore.auction.limit.<10\|15\|25\|50>` | More auction listings |
| `servercore.bank.tier.elite` | Unlock the Elite bank tier |
| `servercore.pay.notax`, `servercore.bank.nofee`, `servercore.auction.nofee`, `servercore.banknote.nofee` | Fee exemptions |
| `servercore.bounty.exempt` | Cannot receive bounties |

Staff permissions (`default: op`): `servercore.maintenance`, `servercore.maintenance.notify`, `servercore.staffchat`, `servercore.ecoadmin`, `servercore.ecostats`, `servercore.transactions.others`, `servercore.banknote.lookup`, `servercore.banknote.alerts`, `servercore.bounty.admin`, `servercore.lottery.admin`, `servercore.auction.admin`, `servercore.admin`. `servercore.*` grants everything.

All normal player features are in `servercore.player` (given to everyone by default).
