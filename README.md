# Bazaar Advisor

Client-side Fabric mod for Minecraft 26.1, developed by HypedSophie.

Current version: `0.1.9`

## Build

Install Java 25, then run `gradle clean build` from this project folder. The mod JAR is written to `build/libs/`.

## Auction commands

- `/bazad on` enables BIN flip suggestions.
- `/bazad off` disables suggestions.
- `/bazad minprofit <coins>` sets the minimum estimated net profit.
- `/bazad interval <seconds>` changes scan interval.

Flip estimates compare the cheapest fresh BIN against active matching listings after tax. They are estimates, not guaranteed sale prices. Clicking a chat suggestion opens `/viewauction`; buying remains manual.
