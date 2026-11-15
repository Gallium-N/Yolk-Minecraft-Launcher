#!/usr/bin/env python3
"""YMCL logo v2 — a Minecraft-ish cube with the yolk inside, seen through a cracked top face."""
import os

OUT = os.path.dirname(os.path.abspath(__file__))
S = 512

PRIMARY_D = "#E6C26C"
YOLK = "#F2B544"
YOLK_HI = "#FFD98C"
YOLK_LO = "#D08A1E"
CREAM = "#FFFCF6"
SHELL = "#F3E7D3"
SHELL_D = "#DCCDB2"
SHELL_DD = "#C3B093"
INK = "#4A3608"

CX, TOP = 256, 152
HW, HH, DEPTH = 130, 74, 98

A = (CX, TOP)
B = (CX + HW, TOP + HH)
C = (CX, TOP + 2 * HH)
D = (CX - HW, TOP + HH)
A2 = (CX, TOP + DEPTH)
B2 = (CX + HW, TOP + HH + DEPTH)
C2 = (CX, TOP + 2 * HH + DEPTH)
D2 = (CX - HW, TOP + HH + DEPTH)


def defs():
    return f"""<defs>
  <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0%" stop-color="{PRIMARY_D}"/>
    <stop offset="50%" stop-color="#EFDBB2"/>
    <stop offset="100%" stop-color="{YOLK}"/>
  </linearGradient>
  <linearGradient id="top" x1="0" y1="0" x2="0.2" y2="1">
    <stop offset="0%" stop-color="{CREAM}"/>
    <stop offset="100%" stop-color="{SHELL}"/>
  </linearGradient>
  <linearGradient id="left" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0%" stop-color="{SHELL}"/>
    <stop offset="100%" stop-color="{SHELL_D}"/>
  </linearGradient>
  <linearGradient id="right" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0%" stop-color="{SHELL_D}"/>
    <stop offset="100%" stop-color="{SHELL_DD}"/>
  </linearGradient>
  <radialGradient id="yolk" cx="0.38" cy="0.32" r="0.75">
    <stop offset="0%" stop-color="{YOLK_HI}"/>
    <stop offset="65%" stop-color="{YOLK}"/>
    <stop offset="100%" stop-color="{YOLK_LO}"/>
  </radialGradient>
  <clipPath id="topFace">
    <polygon points="{A[0]},{A[1]} {B[0]},{B[1]} {C[0]},{C[1]} {D[0]},{D[1]}"/>
  </clipPath>
</defs>"""


def build(simple: bool = False):
    p = []
    # ground shadow
    p.append(f'<ellipse cx="{CX}" cy="{C2[1] + 30}" rx="{HW - 30}" ry="17" fill="{INK}" opacity="0.13"/>')

    # cube faces
    p.append(f'<polygon points="{D[0]},{D[1]} {C[0]},{C[1]} {C2[0]},{C2[1]} {D2[0]},{D2[1]}" fill="url(#left)"/>')
    p.append(f'<polygon points="{C[0]},{C[1]} {B[0]},{B[1]} {B2[0]},{B2[1]} {C2[0]},{C2[1]}" fill="url(#right)"/>')
    p.append(f'<polygon points="{A[0]},{A[1]} {B[0]},{B[1]} {C[0]},{C[1]} {D[0]},{D[1]}" fill="url(#top)"/>')

    g = ['<g clip-path="url(#topFace)">']
    # yolk seen through the top face
    yx, yy, yr = CX, TOP + HH, 46 if not simple else 52
    g.append(f'<circle cx="{yx}" cy="{yy}" r="{yr + 10}" fill="{INK}" opacity="0.18"/>')
    g.append(f'<circle cx="{yx}" cy="{yy}" r="{yr}" fill="url(#yolk)"/>')
    g.append(f'<circle cx="{yx - 20}" cy="{yy - 22}" r="13" fill="#FFEFC6" opacity="0.85"/>')

    if not simple:
        # cracks: short jagged strokes radiating from the hole, all inside the top face
        strokes = [
            [(yx - 40, yy - 26), (yx - 62, yy - 40), (yx - 82, yy - 34)],
            [(yx + 38, yy - 28), (yx + 60, yy - 42), (yx + 80, yy - 36)],
            [(yx - 8, yy - 48), (yx - 18, yy - 62), (yx - 4, yy - 74)],
        ]
        for s in strokes:
            pts = " ".join(f"{x},{y}" for x, y in s)
            g.append(f'<polyline points="{pts}" fill="none" stroke="{INK}" stroke-width="8" '
                     f'stroke-linecap="round" stroke-linejoin="round" opacity="0.75"/>')
    # rim around the hole, clipped to the top face so it never spills onto the sides
    g.append(f'<circle cx="{yx}" cy="{yy}" r="{yr + 3}" fill="none" stroke="{SHELL_DD}" stroke-width="5" opacity="0.9"/>')
    g.append('</g>')
    p.extend(g)

    # cube edges for definition
    p.append(f'<polygon points="{A[0]},{A[1]} {B[0]},{B[1]} {C[0]},{C[1]} {D[0]},{D[1]}" '
             f'fill="none" stroke="{SHELL_DD}" stroke-width="3" opacity="0.55"/>')
    p.append(f'<polyline points="{D[0]},{D[1]} {D2[0]},{D2[1]} {C2[0]},{C2[1]} {C[0]},{C[1]}" '
             f'fill="none" stroke="{SHELL_DD}" stroke-width="3" opacity="0.5"/>')
    return "\n".join(p)


def svg(inner):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{S}" height="{S}" viewBox="0 0 {S} {S}">'
            f'{defs()}<rect x="22" y="22" width="{S-44}" height="{S-44}" rx="112" ry="112" fill="url(#bg)"/>'
            f'{inner}</svg>')


if __name__ == "__main__":
    for name, simple in (("ymcl-logo", False), ("ymcl-logo-simple", True)):
        with open(os.path.join(OUT, name + ".svg"), "w", encoding="utf-8") as f:
            f.write(svg(build(simple)))
        print("wrote", name + ".svg")
