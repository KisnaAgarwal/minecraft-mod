# Harvest Guardian (Fabric 26.1)

Download the compiled mod: [harvest-guardian-1.1.0.jar](dist/harvest-guardian-1.1.0.jar). See installation instructions below; Fabric API and GeckoLib are also required.

Starter Java mod project for the Harvest Guardian Blockbench model. It targets Minecraft 26.1, Fabric Loader 0.20.1, Fabric API 0.145.1+26.1, Java 25, and GeckoLib 5.5.

## Included

- Blockbench model and texture in GeckoLib's expected asset directories.
- Six exported clips: idle, walk, planting seeds, giving bread, slam attack, and player agro.
- A 0.6-block-wide, 1.35-block-tall collision box matched to the exported model (about 21 pixels tall), with a matching shadow.
- Slower movement, a continuous walking loop with both arms, and walking-animation speed that follows movement speed.
- Dedicated goals for approaching mature wheat within 8 blocks and hungry villagers within 12 blocks. Combat takes priority over these jobs; farming and gifting briefly stop walking.
- A close-range slam attack with a small damage and knockback pulse against nearby monsters.
- Periodic replanting of mature wheat on farmland.
- A small saved bread supply; the guardian occasionally drops a loaf near a nearby villager for the villager to pick up.
- `/summon harvestguardian:harvest_guardian` to spawn one in a test world.
- Sounds for footsteps, leaf-rustling ambience, planting, bread, slam, aggro, hurt and death. These reuse Minecraft audio; no extra sound pack is needed.

The current AI is a first pass. It does not yet implement the concept sheet's path repair, compost, irrigation, tree planting, lantern placement, or village-boundary patrol systems. Bread is a finite starting supply of eight loaves; crop yields are left as normal Minecraft drops.

## Build

From this directory in PowerShell, run `.\gradlew.bat build`. The included wrapper downloads Gradle 9.8.2 automatically; no separate Gradle installation is required. Use a JDK 25 or newer supported by Gradle (this build used JDK 26). If Java is not found, set `JAVA_HOME` to your JDK installation directory before running the command. Internet access is needed on the first build.

Compilation and packaging passed on 2026-11-18 with Gradle 9.8.2 and Java 26.0.2.1, targeting Java 25 bytecode. Version 1.0.0 was confirmed working in-game by the user. This update's commands, navigation and sounds still need an in-game check. There is no automated gameplay test suite.

## Install the compiled mod

Close Minecraft and replace the old Harvest Guardian jar with `build/libs/harvest-guardian-1.1.0.jar` (the file without `-sources`). Keep only one Harvest Guardian version in the mods folder. Keep Fabric API 0.145.1+26.1 and GeckoLib 5.5 for Fabric 26.1 installed. Use Fabric Loader 0.20.1 or newer compatible with Minecraft 26.1.

Existing guardians receive the corrected collision box when the world loads. Their old saved movement speed is migrated once to the new default. The exported model's visual size is preserved; the oversized invisible hitbox was the mismatch.

## Demonstration commands

Enable cheats or use operator permission. Commands target the nearest living Guardian within 32 blocks in the same dimension. `/harvestguardian` is the full form of `/hg`. Tab completion lists the available commands and animations.

| Command | Result |
| --- | --- |
| `/hg help` | Show command help. |
| `/hg summon` | Spawn one 3 blocks east of you if there is enough space. |
| `/hg status` | Show the selected guardian's health and bread supply. |
| `/hg showcase` | Preview all six animations and their sounds over 15 seconds. AI pauses, then resumes. No items, crops or combat damage are produced. |
| `/hg stop` | Stop the preview and resume normal AI. |
| `/hg animation walk` | Preview one animation in place for 2 seconds. Options: `idle`, `walk`, `plant_seed`, `give_bread`, `attack_slam`, `player_agro`. |
| `/hg plant` | Harvest/replant mature wheat within 3 blocks, or supply a seed to empty farmland. Reports missing farmland if there is nowhere to plant. |
| `/hg bread` | Consume one stored loaf and drop it beside a villager within 5 blocks of the Guardian. Normal villager pickup rules apply. |
| `/hg refill` | Set its bread supply to 64 so you can repeat the demonstration. |
| `/hg slam` | Play the slam and damage/knock back monsters in the area around its feet (about 2.5 blocks). Players and villagers are excluded. |
| `/hg aggro` | Preview the aggro animation and sound without making the Guardian attack you. |

For a quick presentation, stand near the Guardian and run `/hg showcase`. For real farming, prepare empty farmland or mature wheat beside it and run `/hg plant`. For bread, place a villager nearby, run `/hg refill`, then `/hg bread`. `/hg animation ...` works without those surroundings. The walk preview stays in place; normal AI handles actual navigation.

## Replace the Blockbench exports

The root `.bbmodel` and JSON files preserve the original Blockbench exports. The files in `src/main/resources` are the current game assets, including the improved walking animation in version 1.1.0.

Use these resource paths when replacing or re-exporting assets:

- `src/main/resources/assets/harvestguardian/geckolib/models/entity/harvest_guardian.geo.json`
- `src/main/resources/assets/harvestguardian/geckolib/animations/entity/harvest_guardian.animation.json`
- `src/main/resources/assets/harvestguardian/textures/entity/harvest_guardian.png`

The animation controller looks for the names `animation.idle`, `animation.walk`, `animation.plant_seed`, `animation.give_bread`, `animation.attack_slam`, and `animation.player_agro`.
