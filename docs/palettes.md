# BLE Studio Material 3 palette catalog

All source colors below are copied from the cited upstream palette specifications (or the project’s canonical theme files). Roles are a deterministic Material 3 adaptation: `background`/`surface` use the palette background; `on*` uses foreground; primary/secondary/tertiary use the named accent colors. `surfaceContainerLowest`→`Highest` are the palette’s darkest-to-lightest neutral tiers when available; otherwise they are derived by mixing the base surface toward foreground at 8%, 12%, 16%, 24%, and 32%. Error uses the palette red where available, otherwise `#BA1A1A` (light) / `#FFB4AB` (dark). Every foreground/background pair was selected to preserve WCAG AA (≥4.5:1); for saturated light-theme accents, on-accent is the darkest available foreground and is noted as a compromise where needed.

## Citations by palette

- Catppuccin: https://github.com/catppuccin/palette (official Latte/Mocha palette)
- Nord: https://github.com/nordtheme/nord (official Polar Night/Snow Storm palette)
- Dracula: https://github.com/dracula/dracula-theme/blob/master/README.md (official Dracula and Alucard tables)
- Gruvbox: https://github.com/morhetz/gruvbox (official hard/soft light and dark colors)
- Solarized: https://github.com/altercation/solarized (official base03/base3 and accent palette)
- Tokyo Night: https://github.com/folke/tokyonight.nvim (official Storm/Day/Night colors)
- Rosé Pine: https://github.com/rose-pine/rose-pine-theme and https://rosepinetheme.com/palette (official Dawn/Main palette)
- Everforest: https://github.com/sainnhe/everforest (official light/dark palette)
- Kanagawa: https://github.com/rebelot/kanagawa.nvim (official Lotus/Wave palette)
- One Dark / One Light: https://github.com/atom/one-dark-syntax and https://github.com/atom/one-light-syntax (official Atom themes)
- Monokai Pro: https://monokai.pro/ (official Classic and Light themes)
- Ayu: https://github.com/ayu-theme/ayu-colors (official Light/Mirage/Dark palette)
- Night Owl: https://github.com/sdras/night-owl-vscode-theme (official Night Owl and Light Owl)
- Material Palenight: https://github.com/whizkydee/vscode-material-palenight-theme (official theme colors)
- GitHub: https://github.com/primer/github-syntax (official GitHub Light/Dark syntax colors)
- Horizon: https://github.com/razak17/horizon-theme-vscode (official Horizon themes)
- Synthwave '84: https://github.com/robb0wen/synthwave-vscode (official SynthWave '84; light row is a documented neutral/foreground fallback)
- Zenburn: https://github.com/jnurmine/Zenburn (official dark palette; light row is a documented inverted neutral fallback)
- Cobalt2: https://github.com/wesbos/cobalt2 (official dark palette; light row is a documented neutral/foreground fallback)
- Nightfox: https://github.com/EdenEast/nightfox.nvim (official Dayfox/Nightfox palette)

## Mapping and compromise notes

Material roles are semantic rather than claims that the source theme itself implements Material. For families without an official light variant (Synthwave '84, Zenburn, Cobalt2), the light row is a clearly labeled neutral fallback: source accent hues are retained, while background/foreground are inverted or lightened for readability. Nord’s “light” row maps Snow Storm neutrals with Frost accent colors. In every row `onBackground` and `onSurface` are the source foreground (or a contrast-adjusted near-black/near-white); `onPrimary*`, `onSecondary*`, and `onTertiary*` are chosen by luminance. `outlineVariant` is the source border/comment/selection neutral.

## Machine-readable catalog

(machine-readable table lives in android/app/src/main/kotlin/dev/nphil/blestudio/ui/theme/Palettes.kt)