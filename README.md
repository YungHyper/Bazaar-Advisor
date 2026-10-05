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


Flip suggestions need real completed BIN sales as evidence. The mod keeps a rolling 3-hour history of sales instead of only the last minute, and matches each listing two ways: **EXACT** (same item with identical enchants, reforge, stars, gems, scrolls and other modifiers; pets by type, rarity, level, held item and skin) or **~CORE** (same item and the price-driving modifiers: reforge, stars, rarity upgrades, ultimate enchant, gems, potato books, pet level band), which needs 3+ tightly clustered sales and uses a conservative low-end price. Estimates are also capped by the cheapest other active listing of the same item, so undercutting since the last sale is accounted for, and net proceeds use the real Auction House listing fee and claim tax. Listings are checked from the moment they show up in the API (the newest ones all appear on page 0, which is polled every 2s); the full 45-page sweep only runs when the API data actually refreshes. Clicking a chat suggestion opens `/viewauction`; buying always remains a manual, separate action.

## Development

Source and Gradle project files are kept here for maintainers. Players should use the prebuilt JAR attached to each GitHub Release. Pushing a `v*` tag automatically builds and attaches the mod JAR to a GitHub Release.

## Flip feedback test branch

On `test/flip-feedback`, press Insert or run `/bazad` to open the settings screen. Left-click a setting to increase/cycle it; right-click to decrease/cycle backward. Each suggestion is logged to the local Minecraft config JSONL file. Double-click `open-feedback-dashboard.bat` to open the dashboard at `http://127.0.0.1:8766`. Rate flips and add comments in the browser; Refresh is manual so it won't erase a draft while typing. Changes are saved to the same local file, the server binds to `127.0.0.1` only, and nothing is uploaded. Current build: `0.2.3`. Coverage is thin right after launch and improves as sale history accumulates.

On Hypixel's "BIN Auction View" and "Confirm Purchase" screens, a quick-buy overlay replaces the small slots with one large BUY NOW / CONFIRM PURCHASE target (plus a smaller Cancel), the profit estimate for flips this mod suggested, and an expiry warning. Each mouse click still sends exactly one normal click on the real slot; nothing is clicked for you, and the first 200 ms after a screen opens ignore clicks so a double-click can't confirm by accident. Toggle it with `/bazad quickbuy` or in the settings screen; Esc closes the screen as usual.

New flips play a short note-block tune and show the profit as a large on-screen title. Press the "Open best flip auction" key (default V, rebindable under Controls) to open the most recent suggestion's auction; the purchase itself is always yours. Turn the sound and popup off with `/bazad alert` or in the settings screen. The whole flip chat line is clickable.
