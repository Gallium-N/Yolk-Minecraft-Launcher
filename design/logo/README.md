# YMCL logo

Concept: a Minecraft-ish isometric block with the yolk sitting in a cracked top face —
"把游戏孵出来", and the yolk is the project's namesake.

| File | Use |
|---|---|
| `ymcl-logo.svg` | master (vector) |
| `ymcl-logo-simple.svg` | no cracks — use at <= 32 px / tray |
| `ymcl-logo.ico` | Windows `nativeDistributions.windows.iconFile` |
| `ymcl-logo-{512,256,128,64,32}.png` | Linux .desktop icon, docs, store |

Palette from `design/tokens` (seed `#EFDBB2`): shell `#FFFCF6`/`#F3E7D3`, yolk `#F2B544`,
ink `#4A3608`, background `#E6C26C → #EFDBB2 → #F2B544`.

Regenerate: `python3 gen_logo.py`, then rasterize with `rsvg-convert -w <size>`.
