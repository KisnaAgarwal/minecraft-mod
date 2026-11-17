# Harvest Guardian 1.3.1 — Fabric 26.1

[Download the compiled mod](dist/harvest-guardian-1.3.1.jar).

A large village guardian with farming, food delivery, home patrols, timed slam attacks, sounds and drifting leaves. Requires Minecraft 26.1, Fabric Loader 0.20.1+, Fabric API 0.145.1+26.1, GeckoLib 5.5 for Fabric 26.1, and Java 25+.

## Install

Close Minecraft and replace the old Harvest Guardian jar with `build/libs/harvest-guardian-1.3.1.jar`. Keep only one version of this mod in the mods folder. Do not install the `-sources.jar`.

The model remains at 3.5x scale (about 4.6 blocks tall). Its collision box is 2.1 blocks wide and 4.725 blocks tall, so use open outdoor paths and fields.

## Simple controls — no new screen

Use `/hg follow`, `/hg stay`, `/hg patrol`, or `/hg farm`. Use `/hg home`, `/hg bell`, and `/hg radius 32` to assign its area. `/hg controls` prints optional chat shortcuts. Clicking the mob no longer opens controls, adds supplies or heals it. Normal left-click attacks still behave normally.

- **Follow:** follows the player who selected it. Waits if that player is unavailable or in another dimension.
- **Stay:** stays where it is, defending itself against threats already within slam range.
- **Patrol:** wanders inside the home area, protects it, collects supplies and shares bread.
- **Farm:** approaches nearby farmland, plants, harvests/replants wheat, collects supplies, bakes bread and feeds hungry villagers. Combat takes priority.
- **Set here:** makes your current position the home center.
- **Bind bell:** makes the nearest bell within 16 blocks the home center.
- **Radius - / +:** adjusts the home radius by 8 blocks, from 8 to 64. Default: 24.

Home, mode, keeper and supplies survive world reloads. A new Guardian uses its initial position as home. Patrol and farm work stay inside the home area; Follow can leave it. If it changes dimensions, reassign home before using Patrol/Farm there.

## Feeding, supplies and farming

There is no backpack screen. Supplies are stored internally and shown in the chat status.

- Hold wheat seeds and run `/hg feed` to add one seed. Sneak while running it to add the held stack.
- Bone meal heals 10 health; bread heals 6; wheat, carrots, potatoes or beetroot heal 3. One item is consumed in survival.
- Hold crops or food and run `/hg feed` to heal. Sneak while running it to store the held stack instead. If already fully healed, it stores one item.
- New Guardians start with eight loaves. Existing saved bread is migrated into real item stacks.
- Farm mode consumes one seed per planting/replanting. It uses vanilla wheat drops, collects them, and can reserve a harvested seed for replanting.
- Three stored wheat become one bread. Crafting stops if the result will not fit. Overflow harvest drops remain in the world. Stored supplies normally drop when the Guardian dies.
- Farming requires the mob-griefing game rule to be enabled. Prepare reachable farmland inside the home radius and feed some seeds before selecting Farm.
- Supported stored supplies include seeds, wheat, bread, carrots, potatoes, beetroot, bone meal, pumpkins and melon slices. Automatic planting and harvesting currently handle wheat.

## Combat

The slam raises both arms, holds a visible wind-up, then deals damage at impact (about 0.65 seconds). The impact creates ground debris and a dust ring, deals 15 damage, and knocks nearby threats back. It has a roughly three-second cooldown after impact. The Guardian scans once per second for visible hostile mobs inside its protection area and follows them in Patrol/Farm mode. Impact targets must be within 3.2 blocks, visible, and inside the allowed protection area. Villagers, the keeper and allied entities are excluded. Survival players who attack an unowned/other player's Guardian can provoke retaliation.

## Commands

The `/hg` demonstration commands require cheats/operator permission and select the nearest Guardian within 32 blocks. The `/hg` controls require cheats/operator permission.

| Command | Action |
| --- | --- |
| `/hg summon` | Spawn in open space three blocks east of you. |
| `/hg controls` | Show the simple chat controls. |
| `/hg feed` | Heal or supply the Guardian using the item in your main hand. |
| `/hg status` | Show health, bread, mode, radius and home. |
| `/hg follow`, `/hg stay`, `/hg patrol`, `/hg farm` | Select behavior. |
| `/hg home`, `/hg bell`, `/hg radius 32` | Assign home/bell/radius. |
| `/hg showcase`, `/hg stop` | Preview six animations, or end the preview. |
| `/hg animation walk` | Preview a clip: idle, walk, plant_seed, give_bread, attack_slam, player_agro. |
| `/hg plant` | Work on nearby farmland using real seeds/harvest drops inside the home area. |
| `/hg bread` | Deliver one stored loaf to a nearby villager. |
| `/hg refill` | Operator-only demonstration refill, up to 64 bread if space allows. |
| `/hg slam` | Queue the timed slam against nearby monsters; respects cooldown. |
| `/hg aggro` | Harmless aggro animation preview. |

## Build and verification

Run `.\gradlew.bat build` in PowerShell with Java 25+ available. The wrapper downloads Gradle 9.8.2. Build output is in `build/libs/`.

The build runs 21 checks against Minecraft's real item/container classes: recipe conservation, failed-consumption rollback, full storage, seed reservation, overflow, and slot-preserving save/load. Run them separately with `.\gradlew.bat verifyBackpack`.

Version 1.3.1 compiled and passed those checks. The new chat controls, navigation and timed combat still need in-game testing.

## Model assets

Current game assets are under `src/main/resources/assets/harvestguardian/`: `geckolib/models/entity/harvest_guardian.geo.json`, `geckolib/animations/entity/harvest_guardian.animation.json`, and `textures/entity/harvest_guardian.png`. Root Blockbench files preserve the original export; the packaged animation file includes later improvements.
