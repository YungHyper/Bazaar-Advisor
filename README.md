# Bazaar Advisor

Client-side Fabric mod for Minecraft 26.1, developed by HypedSophie.

## Download and install

Download the latest `.jar` from [GitHub Releases](https://github.com/YungHyper/Bazaar-Advisor/releases/latest). You do not need to compile the source.

1. Install Fabric for Minecraft 26.1 and install Fabric API.
2. Put the downloaded Bazaar Advisor `.jar` into your Minecraft `mods` folder.
3. Launch the Fabric 26.1 profile.

## In-game auction commands

- `/bazad rate <id> <1-5>` rates a flip suggestion in the feedback test build.
- Each suggestion is logged locally to `.minecraft/config/bazaar-advisor/flip-feedback.jsonl`; the log is not uploaded or committed.


Flip suggestions require a recent completed BIN sale for the same normalized item NBT. Per-instance IDs/timestamps and modifier-list ordering are normalized; enchantments, stars, hot-potato upgrades, reforge, gemstones, skins, pet items, and other item values remain in the comparison. The scanner checks five auction pages every five seconds and uses realized sale prices after tax. This is intentionally conservative and can show no flips when there is no recent matching sale. Clicking a chat suggestion opens `/viewauction`; buying remains manual.

## Development

Source and Gradle project files are kept here for maintainers. Players should use the prebuilt JAR attached to each GitHub Release. Pushing a `v*` tag automatically builds and attaches the mod JAR to a GitHub Release.

## Flip feedback test branch

On `test/flip-feedback`, press Insert or run `/bazad` to open the settings screen. Left-click a setting to increase/cycle it; right-click to decrease/cycle backward. Each suggestion is logged to the local Minecraft config JSONL file. Double-click `open-feedback-dashboard.bat` to open the dashboard at `http://127.0.0.1:8766`. Rate flips and add comments in the browser; Refresh is manual so it won't erase a draft while typing. Changes are saved to the same local file, the server binds to `127.0.0.1` only, and nothing is uploaded.
