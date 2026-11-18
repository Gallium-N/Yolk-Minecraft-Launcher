# YMCL logo

**当前定稿**：立方体 + 从最近顶点出发的三条棱构成的 Y（"亮 Y"），深/浅两套，对应 M3 的 dark / light。

几何按真等轴测：`w = 0.866·d`、`h = 0.5·d`，`d = 152`；侧棱高度:顶面半高 = 2:1，所以读作"方"而非扁菱形。
Y 三条边圆头、端点向内收 10%，不戳出轮廓。

| 文件 | 用途 |
|---|---|
| `ymcl-mark-dark.svg` / `ymcl-mark-light.svg` | 矢量主稿（512 viewBox） |
| `ymcl-mark-{dark,light}-{512,256,128,64,48,32}.png` | 位图 |
| `ymcl-mark-dark.ico` | Windows `windows.iconFile` |
| `ymcl-mark-light.ico` | 浅色主题备用 |
| `ymcl-mark-light-512.png` | Linux `linux.iconFile` / `.desktop` Icon |
| `ymcl-mark-light-256.png` | 运行时窗口图标 → 复制为 `composeResources/drawable/ymcl.png` |

配色：深色底 `#17130B`、面 `#3E3320/#332A18/#2A2213`、线框 `#E6C26C@42%`、Y `#F7DA95`；
浅色底 `#FFF8F1`、面 `#FEFAF2/#F7EDDD/#EFDFC6`、线框 `#CBAA61@65%`、Y `#8C5A12`。

改参数：`gen_logo.py` 的 `D_EDGE`/`R`/`THEMES`；栅格化用 `rsvg-convert -w <size>`。
