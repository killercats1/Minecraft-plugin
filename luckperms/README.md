# LuckPerms setup

This sets up a full rank ladder for the server: permissions for **ServerCore**, **EssentialsX**, **WorldEdit** and **Simple Voice Chat**, chat prefixes, weights and promotion tracks.

## Ranks

| Rank | Weight | Inherits | Prefix | What they get |
|---|---|---|---|---|
| `default` | 1 | - | gray name | All ServerCore player features, EssentialsX basics: homes, /tpa, /back, /msg, /mail, warps, the `starter` kit, /bal, /baltop, voice chat (listen + speak) |
| `vip` | 10 | default | `[VIP]` | x1.1 sell price, x1.25 daily reward, $500 payday, 10 auction listings, `vip` kit, /hat, /workbench, color chat, **voice chat groups** |
| `mvp` | 20 | vip | `[MVP]` | x1.25 sell price, x1.5 daily reward, $1000 payday, 15 auction listings, `mvp` kit, /enderchest, /feed, /nick |
| `elite` | 30 | mvp | `[Elite]` | x1.5 sell price, x2 daily reward, $2000 payday, 25 auction listings, **Elite bank tier**, no auction fees, `elite` kit, /heal, /fly |
| `builder` | 40 | default | `[Builder]` | Joins during maintenance, gamemode, fly, speed, time/weather, teleport, WorldEdit |
| `helper` | 50 | default | `[Helper]` | Joins during maintenance, staff chat, maintenance join alerts, /helpop alerts, kick, mute, $1500 staff payday, voice chat groups |
| `moderator` | 60 | helper | `[Mod]` | Tempban/unban, jail, vanish, socialspy, invsee, teleport, other players' transactions, banknote dupe alerts and lookup, cancel auction listings |
| `admin` | 80 | moderator | `[Admin]` | Maintenance control, /ecoadmin, /ecostats, permanent bans, /give, /eco, warps/spawn setup, all kits, `/servercore reload`, WorldEdit, voice chat connection tests (`voicechat.admin`) |
| `developer` | 90 | admin | `[Dev]` | Full LuckPerms access |
| `owner` | 100 | developer | `[Owner]` | Everything (`*`) |

Tracks: `donor` (default > vip > mvp > elite) and `staff` (default > helper > moderator > admin > developer > owner). For example, `lp promote Steve donor` moves Steve one rank up the donor ladder.

All staff ranks and `builder` can join while maintenance mode is on. They hold `servercore.maintenance.bypass`, and their rank names are also in `maintenance.allowed-ranks` in ServerCore's config.

## How to apply it

1. Install **LuckPerms** (the normal Bukkit jar supports 1.8.8) and **Vault**, then restart.
2. In the server **console**, run:
   ```
   servercore setupranks <YourName>
   ```
   ServerCore runs every command from [`setup-commands.txt`](setup-commands.txt) one at a time, which takes under a minute, and then makes you owner. Leave out the name to skip the owner step.
   - Some hosting consoles (eagler.host, for example) join pasted lines into one command, so pasting the file doesn't work there. This command avoids that.
   - `lp creategroup default` will say the group already exists. That's expected.
   - It's safe to run again; existing ranks are just updated.
3. If you skipped the name, give yourself owner: `lp user <YourName> parent set owner`
4. Give ranks with `lp user <name> parent set vip`, or use the tracks: `lp promote <name> donor`.
5. Check the result in the web editor with `lp editor`. You can tweak anything there.

## Simple Voice Chat

| Node | Who has it | What it does |
|---|---|---|
| `voicechat.listen` | everyone | Hear other players |
| `voicechat.speak` | everyone | Talk |
| `voicechat.groups` | VIP and up, helper and up | Create or join voice groups (private channels) |
| `voicechat.admin` | admin and up | Test other players' voice connections |

By default Simple Voice Chat lets everyone use groups. This setup makes groups a donor/staff perk instead. To let everyone use groups, run `lp group default permission set voicechat.groups true`. To mute someone's voice, run `lp user <name> permission set voicechat.speak false`.

## Things to match in other configs

- **EssentialsX `config.yml`**:
  - Add `mvp` and `elite` under `sethome-multiple`. The default file only has `default`, `vip` and `staff`. Example: `default: 3`, `vip: 5`, `mvp: 8`, `elite: 12`, `staff: 15`.
  - Create kits named `starter`, `vip`, `mvp` and `elite` (or rename the `essentials.kits.<name>` nodes).
- **Chat prefixes** need a chat formatter that reads Vault prefixes, such as **EssentialsX Chat** with `{PREFIX}{DISPLAYNAME}` in its format. The prefix also shows in the ServerCore scoreboard through `{rank}`.
- **ServerCore `config.yml`**: the multipliers, salaries and listing limits above come from `sell.multipliers`, `daily.rank-multipliers`, `payday.ranks` and `auction.limit-permissions`. Change the numbers there and the rank permissions keep working.
