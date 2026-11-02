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
| [LoginSecurity](https://www.spigotmc.org/resources/loginsecurity.19362/) or AuthMe | **Yes** on Eaglercraft | Without one, anyone can join with your name. ServerCore locks staff accounts until one runs (see [Server check](#server-check-and-login-protection)) |
| [GriefPrevention](https://www.spigotmc.org/resources/griefprevention.1884/) (an older release that supports 1.8) | Recommended | Land claims with a golden shovel |
| [CoreProtect](https://www.spigotmc.org/resources/coreprotect.8631/) (an older release that supports 1.8) | Recommended | Block logs and rollbacks |
| [NoCheatPlus](https://ci.codemc.io/job/Updated-NoCheatPlus/job/Updated-NoCheatPlus/) | Recommended | Anti-cheat |
| NuVotifier | Optional | Receives votes for `/vote` |
| Tebex | Optional | Web store, see [Store](#store-tebex) |

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

## Vote rewards (`/vote`)

Players vote on server lists (TopEaglerServers is set up already) and get $250 per vote by default. `/vote` shows the clickable vote links and the player's vote count, and `/vote top` shows this month's top voters. Votes for offline players are saved and paid when they next join.

Vote sites tell the server about a vote through **NuVotifier**, so it has to be installed next to ServerCore:

1. Put [NuVotifier](https://github.com/NuVotifier/NuVotifier/releases) (the Bukkit/Spigot jar) in `plugins/` and restart once.
2. Open the Votifier port in your host's firewall/panel. It is `port` in `plugins/Votifier/config.yml` (8192 by default). On a shared host (eagler.host and similar) ask them for an extra port and put it there.
3. On the vote site's server settings, enter your server's IP, that Votifier port and the public key from `plugins/Votifier/rsa/public.key` (one long line, no header). Use the site's Votifier test button to check it.
4. Run `/vote test <player>` in game to check the rewards without voting.

Change the money, add console commands (`{player}`, `{uuid}`, `{service}`), add more vote links or turn off the broadcast/join reminder under `votes` in `config.yml`. Vote money shows up in `/transactions`.

## Player heads on Eaglercraft (`/headfix`)

**The problem:** an Eaglercraft (browser) client only draws a player head's skin if the head stores a player UUID. On an offline-mode server, Spigot fills in a head made from a name (`/skull <name>`, menus, HeadDB's player heads) by asking Mojang. For cracked names that fails, so the head has no UUID and shows Steve. Sometimes it finds a premium account with the same name and shows a stranger's skin.

**The fix:** ServerCore remembers every player who joins: their UUID, their skin (including SkinsRestorer skins) and whether they play on Eaglercraft or Java. It then:
- fills in Spigot's head cache, so **every new head made from a name is correct instantly**, whichever plugin makes it;
- repairs **existing** heads in inventories, ender chests, chests, dropped items, item frames, armor stands and placed heads as chunks load;
- **permanent first:** if a player has their own skin URL (a SkinsRestorer `/skin` skin), all their heads use it, so they show the right skin **even when the owner is offline**, for Eaglercraft and Java players. Heads also update when a player changes their skin.
- players whose skin only exists in their browser fall back to their **live browser skin**, which Eaglercraft can only get from eagler.host's gateway while they're online (Steve/Alex when offline). No server plugin can store a browser-only skin, so these players get a one-time tip: set a skin with `/skin <premium name>` or `/skin url <link to a png>` to make their head permanent.

Heads that belong to someone else are never touched: premium heads with a different UUID, decorative heads with a custom texture, and Mojang's `MHF_` menu heads.

| Command | What it does |
|---|---|
| `/headfix status` | Whether the fix is active and what it detected |
| `/headfix check` | Inspect the held or targeted head: who it belongs to, and what Eaglercraft and Java will draw |
| `/headfix fix [hand\|inv\|area <chunks>]` | Repair heads right now |
| `/headfix give <player> [amount]` | Get a correct head of a player |
| `/headfix who` | Who is on Eaglercraft and who is on Java |

**What it can't fix:** decorative heads with a skin URL (HeadDB etc.) are downloaded for Eaglercraft players by eagler.host's gateway, and no server plugin can reach that. If `/headfix check` says "skin from the URL" but the head still shows Steve on Eaglercraft, eagler.host's skin downloads are off.

Settings are under `head-fix` in `config.yml`. `skin-source: url` makes heads always use the skin URL instead of the live browser skin.

## Lag Guard (`/lagguard`)

Keeps the server smooth on a small host with browser players:
- **Ground item clearing** every 10 minutes, with warnings at 60, 10 and 3 seconds. It never removes named or lored items (QualityArmory guns, renamed gear), banknotes, enchanted items, heads, diamonds and other valuables, display items, items dropped in the last 3 minutes, or anything near a recent death.
- **Spawn caps:** no more mobs from breeding, spawners or eggs once a chunk has 24 of that mob (32 chickens) or 48 animals. Existing mobs are never killed. Spawns from other plugins are never capped.
- **Emergency mode:** if TPS stays below 15 for a minute, items are cleared early, natural mob spawning pauses, and staff get the busiest chunks. Admins can click a chunk to teleport there safely. It switches off again once TPS is back above 18.
- `/lagguard status` shows TPS, memory and entities; `/lagguard chunks` shows the busiest chunks; `/lagguard sweep [now]` clears items.
- New scoreboard placeholders: `{tps}` and `{client}` (Eaglercraft or Java).

Settings are under `lag-guard` in `config.yml`.

## Backups

ServerCore zips the worlds, important plugin folders (ServerCore, LuckPerms, Essentials, DiscordSRV, LoginSecurity, Jobs, ...) and the server's ops/whitelist/ban lists every 12 hours. They go into the `backups/` folder in the main server directory, and only the newest 2 are kept.

- `/servercore backup` makes one now; `/servercore backup list` lists them.
- While a backup runs, world saving is paused and the zip is written in the background, so the copy is consistent and the server keeps running.
- It checks free disk space first and never fills the disk.
- **The backups are on the same disk as the server.** Download the newest zip from your host's file manager every now and then, so a host problem can't take your backups with it.
- To restore, stop the server and replace the world/plugin folders with the ones from the zip.

Settings are under `backups` in `config.yml`.

### Off-site backups (Discord)

Backups on the server's disk die with the server. ServerCore can send every backup to a private Discord channel too:

1. In Discord, make a channel only you (or admins) can see. Open its settings > **Integrations** > **Webhooks** > **New Webhook** > **Copy Webhook URL**.
2. Paste it as `backups.discord.webhook-url` in `config.yml` and run `/servercore reload`.
3. Run `/servercore backup` to test it.

Discord allows 10 MB per file, so bigger backups are split into numbered parts (`backup-....zip.001`, `.002`, ...). To restore, download every part into one folder and open the `.001` file with [7-Zip](https://www.7-zip.org/) (or run `cat backup.zip.* > backup.zip`). Backups over `max-upload-mb` (250 MB) aren't uploaded; Discord gets a notice instead. The upload runs in the background after world saving has resumed.

## Server check and login protection

`/servercore check` shows whether a login plugin is running and which recommended plugins are installed (Vault, EssentialsX, LuckPerms, GriefPrevention, CoreProtect, NoCheatPlus, NuVotifier, DiscordSRV, Tebex). The console prints the same warnings at startup.

Eaglercraft servers run in offline mode, so **without a login plugin anyone can join with your name**. If ServerCore can't find one (LoginSecurity, AuthMe, nLogin, JPremium, ...), it **locks staff accounts**: ops and anyone with `servercore.staff` or `servercore.maintenance.bypass` can't join until a login plugin is installed. Normal players can still join. Settings are under `security.login-check`:
- `mode: warn` lets staff join with a warning instead, `mode: off` turns the check off.
- If players log in on the proxy (EaglerXServer auth on BungeeCord), set `auth-on-proxy: true`.

## Grief protection and anti-cheat

These are separate plugins; ServerCore's rank setup already gives staff their permissions. Put the jars in `plugins/`, restart, then run `servercore setupranks` in the console to add the new permissions.

| Plugin | What players do | What staff do |
|---|---|---|
| **GriefPrevention** | Get a golden shovel and right-click two corners to claim land. `/trust <player>` lets friends build. Claim blocks grow with playtime. | Mods: `/ignoreclaims`, `/deleteclaim`, `/claimslist <player>`. Admins: `/adminclaims`, `/adjustbonusclaimblocks`, `/transferclaim`. |
| **CoreProtect** | - | Helpers: `/co i` (click a block to see who changed it), `/co lookup`. Mods: `/co rollback u:<player> t:1d r:20`, `/co restore`. |
| **NoCheatPlus** | - | Helpers see cheat alerts (`/ncp notify` toggles them). Mods: `/ncp info <player>`, `/ncp inspect <player>`. |

- GriefPrevention's claim data (`GriefPreventionData`) and NoCheatPlus's config are in the backups. CoreProtect's database is left out on purpose because it gets very big.
- Eaglercraft players lag a bit more than Java players. If NoCheatPlus flags normal movement, raise the lag compensation in its `config.yml` instead of turning checks off.
- Pair NoCheatPlus alerts with `/punish <player>` (the **Hacking** button).

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

## Punishments

| Command | Who | What it does |
|---|---|---|
| `/punish <player>` | helper+ | Menu with preset reasons (hacking, x-ray, griefing, spam, toxicity, advertising, bad name/build, ban evasion). Each shows how many times the player did it before and what they get this time. |
| `/punish <player> <preset> [note]` | helper+ | Same as clicking the button (works from the console) |
| `/punish <player> warn\|kick\|mute\|tempban\|ban [time\|perm] <reason>` | by rank | Manual punishment, e.g. `/punish Steve tempban 3d Griefing at spawn` |
| `/warn <player> <reason>` | helper+ | Warning |
| `/unpunish <player> ban\|mute [note]` | mod+ (unmute), admin (unban) | Lift a ban or mute |
| `/phistory <player> [page]` | helper+ | Every punishment, who gave it, and whether it's still active |
| `/alts <player>` | mod+ | Other accounts that joined from the same IP; banned ones in red |

- **Escalation:** each preset has steps, e.g. hacking is 7 days, then 30 days, then permanent. Edit them under `punishments.presets`.
- **Who can give what:** helpers warn, kick and mute, moderators tempban, admins ban permanently. Buttons a rank can't use show a barrier.
- **Ban evasion:** when someone joins from the same IP as a banned account, staff get an alert. Set `ban-evasion: block` to stop them joining. Shared proxy addresses are ignored.
- Mutes block chat and private messages (`muted-blocked-commands`).
- Paste a Discord webhook URL in `punishments.discord-webhook-url` to log every punishment to a staff channel.
- Use `/punish` instead of EssentialsX's `/ban`, `/tempban` and `/mute`, so everything shows in the history.

## Spawn protection

No building, breaking, fire, explosions, PvP or damage within 48 blocks of spawn, without WorldGuard. Builders and admins (`servercore.spawnprotection.bypass`) can still build. Stand where the centre should be and run `/servercore setspawnprotection` to move it. Settings are under `spawn-protection`.

## Rules

New players see the rules and a clickable **[ACCEPT]** button. Until they accept (or type `/rules accept`), they can't move, chat, or use commands other than login commands. `/rules` shows them any time. Change the rules under `rules.lines`, and raise `rules.version` to make everyone accept again. ServerCore's `/rules` replaces EssentialsX's.

## Combat-log protection

Hitting or being hit by a player puts both in combat for 15 seconds. In combat you can't use `/spawn`, `/home`, `/tpa`, `/warp`, `/back`, `/fly` and similar commands, and flying stops. **Logging out in combat kills you**, so your items drop. Staff kicks and bans don't count. The scoreboard can show it with `{combat}`. Settings are under `combat-tag`.

## Crates and keys

Keys are stored in the database, so they can't be duplicated or faked, and they can be given to offline players.

| Command | What it does |
|---|---|
| `/crate` | Your crates and keys. Left-click opens, right-click shows the rewards. |
| `/crate open\|preview <crate>` | Open a crate or see its rewards and chances |
| `/crate givekey <player\|all> <crate> [amount]` | Give keys (admin or console: votes, store, events) |
| `/crate takekey <player> <crate> [amount]` | Take keys away |
| `/crate set <crate>` / `/crate remove` | Turn the block you look at into a crate (right-click to open, left-click for rewards), or remove it |

Three crates come ready: **Vote** (money, iron, food, diamonds, a chance at a Rare key), **Rare** and **Legendary** (enchanted gear, god apples). Change them under `crates.types`.

**Give a Vote key for every vote:** new installs do this already. On an existing server, change `votes.commands` in `config.yml` to:
```yaml
  commands: ['crate givekey {player} vote 1']
```

## Store (Tebex)

1. Make a free store at [tebex.io](https://www.tebex.io/), choose Minecraft (Java), and install the Tebex plugin (if it doesn't load on 1.8.8, use the older BuycraftX plugin). Run `/tebex secret <key>` in the console.
2. Put the store link in `store.url` in ServerCore's `config.yml`. Players open it with `/store` (or `/buy`, `/donate`).
3. Package commands to use in Tebex (run when the player is online or offline):

| Package | Command |
|---|---|
| VIP / MVP / Elite rank | `lp user {username} parent add vip` (or `mvp`, `elite`) |
| Crate keys | `crate givekey {username} legendary 3` |
| Money | `eco give {username} 10000` |

Tebex has to follow Minecraft's EULA: sell cosmetics, ranks with convenience perks and keys, but not gameplay advantages that only paying players can get.

## Other features

- **Scoreboard**: flicker-free sidebar with `{rank}`, `{balance}`, `{bank}`, `{bounty}`, `{lottery}`, `{streak}`, `{online}`, `{combat}` and PlaceholderAPI. `/scoreboard` toggles it per player.
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
| `servercore.punish.exempt` | Cannot be punished by other players |
| `servercore.rules.exempt` | Never asked to accept the rules |
| `servercore.combattag.bypass` | Never combat tagged |

Staff permissions (`default: op`): `servercore.staff`, `servercore.punish`, `servercore.punish.mute`, `servercore.punish.tempban`, `servercore.punish.ban`, `servercore.punish.unban`, `servercore.punish.unmute`, `servercore.punish.history`, `servercore.punish.alts`, `servercore.punish.notify`, `servercore.spawnprotection.bypass`, `servercore.crate.admin`, `servercore.maintenance`, `servercore.maintenance.notify`, `servercore.staffchat`, `servercore.ecoadmin`, `servercore.ecostats`, `servercore.transactions.others`, `servercore.banknote.lookup`, `servercore.banknote.alerts`, `servercore.bounty.admin`, `servercore.lottery.admin`, `servercore.auction.admin`, `servercore.admin`. `servercore.*` grants everything.

All normal player features are in `servercore.player` (given to everyone by default).
