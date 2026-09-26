# Bazaar Advisor

Client-side Fabric mod for Minecraft 26.1, developed by HypedSophie.

## Download and install

Download the latest `.jar` from [GitHub Releases](https://github.com/YungHyper/Bazaar-Advisor/releases/latest). You do not need to compile the source.

1. Install Fabric for Minecraft 26.1 and install Fabric API.
2. Put the downloaded Bazaar Advisor `.jar` into your Minecraft `mods` folder.
3. Launch the Fabric 26.1 profile.

## In-game auction commands

- `/bazad on` enables BIN flip suggestions.
- `/bazad off` disables suggestions.
- `/bazad minprofit <coins>` sets the minimum estimated net profit. Default: `500000`.
- `/bazad interval <seconds>` changes scan interval.
- `/bazad status` shows the active threshold and last scanned page's result count.
- `/bazad scan` scans the current auction page immediately.

Flip suggestions are estimates from active matching BIN listings after tax, not guaranteed sale prices. Clicking a chat suggestion opens `/viewauction`; buying remains manual.

## Development

Source and Gradle project files are kept here for maintainers. Players should use the prebuilt JAR attached to each GitHub Release. Pushing a `v*` tag automatically builds and attaches the mod JAR to a GitHub Release.
