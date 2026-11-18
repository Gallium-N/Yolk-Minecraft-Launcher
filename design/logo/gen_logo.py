#!/usr/bin/env python3
"""YMCL mark v3 — option 1 (dark cube, lit Y) with true-cube proportions, light + dark themes.

Geometry: a real isometric cube, so the side edges are as tall as the top-face half-height ×2
(w = 0.866·d, h = 0.5·d). That is what makes it read "square" instead of a squashed diamond.
"""
import os

OUT = os.path.dirname(os.path.abspath(__file__))
S = 512
R = 112
M = 26

# cube: d = edge length on screen, w = d*cos30, h = d*sin30
D_EDGE = 152
W = round(D_EDGE * 0.866)      # 132
H = round(D_EDGE * 0.5)        # 76
CX = S // 2
CY = (S - (2 * H + D_EDGE)) // 2 + H     # centre the silhouette vertically

THEMES = {
    "dark": dict(
        bg="#17130B",
        top="#3E3320", left="#2A2213", right="#332A18",
        wire="#E6C26C", wire_op="0.42", wire_w=6,
        y="#F7DA95", y_w=26,
        shadow="#000000", shadow_op="0.35",
    ),
    "light": dict(
        bg="#FFF8F1",
        top="#FEFAF2", left="#F7EDDD", right="#EFDFC6",
        wire="#CBAA61", wire_op="0.65", wire_w=6,
        y="#8C5A12", y_w=26,
        shadow="#8A6A1E", shadow_op="0.10",
    ),
}


def geom():
    A = (CX, CY - H)
    B = (CX + W, CY)
    C = (CX, CY + H)
    D = (CX - W, CY)
    A2 = (CX, CY - H + D_EDGE)
    B2 = (CX + W, CY + D_EDGE)
    C2 = (CX, CY + H + D_EDGE)
    D2 = (CX - W, CY + D_EDGE)
    return A, B, C, D, A2, B2, C2, D2


def pts(*ps):
    return " ".join(f"{float(x):.2f},{float(y):.2f}" for x, y in ps)


def build(theme):
    t = THEMES[theme]
    A, B, C, D, A2, B2, C2, D2 = geom()
    s = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{S}" height="{S}" viewBox="0 0 {S} {S}">',
         f'<defs><linearGradient id="y" gradientUnits="userSpaceOnUse" x1="110" y1="120" x2="400" y2="430">'
         f'<stop offset="0%" stop-color="{t["y"]}"/>'
         f'<stop offset="100%" stop-color="{t["y"]}" stop-opacity="0.86"/></linearGradient></defs>',
         f'<rect x="{M}" y="{M}" width="{S - 2 * M}" height="{S - 2 * M}" rx="{R}" fill="{t["bg"]}"/>']

    # soft contact shadow under the cube
    s.append(f'<ellipse cx="{CX}" cy="{C2[1] + 26}" rx="{W - 26}" ry="16" '
             f'fill="{t["shadow"]}" opacity="{t["shadow_op"]}"/>')

    # faces
    s.append(f'<polygon points="{pts(D, C, C2, D2)}" fill="{t["left"]}"/>')
    s.append(f'<polygon points="{pts(C, B, B2, C2)}" fill="{t["right"]}"/>')
    s.append(f'<polygon points="{pts(A, B, C, D)}" fill="{t["top"]}"/>')

    # silhouette + the two far top edges as a light wireframe (no diagonals through the body)
    s.append(f'<g fill="none" stroke="{t["wire"]}" stroke-opacity="{t["wire_op"]}" '
             f'stroke-width="{t["wire_w"]}" stroke-linejoin="round" stroke-linecap="round">'
             f'<polygon points="{pts(A, B, B2, C2, D2, D)}"/>'
             f'<path d="M {A[0]} {A[1]} L {C[0]} {C[1]}"/></g>')

    # the Y: three edges leaving the near vertex
    def inset(p0, p1, k):
        """Pull p0 towards p1 by k (0..1) of the edge length."""
        return (p0[0] + (p1[0] - p0[0]) * k, p0[1] + (p1[1] - p0[1]) * k)

    d0 = inset(D, C, 0.10); b0 = inset(B, C, 0.10); c20 = inset(C2, C, 0.10)
    s.append(f'<g stroke="url(#y)" stroke-width="{t["y_w"]}" stroke-linecap="round" fill="none">'
             f'<path d="M {d0[0]:.1f} {d0[1]:.1f} L {C[0]} {C[1]}"/>'
             f'<path d="M {C[0]} {C[1]} L {b0[0]:.1f} {b0[1]:.1f}"/>'
             f'<path d="M {C[0]} {C[1]} L {c20[0]:.1f} {c20[1]:.1f}"/></g>')
    s.append("</svg>")
    return "".join(s)


if __name__ == "__main__":
    for theme in ("dark", "light"):
        name = f"ymcl-mark-{theme}"
        with open(os.path.join(OUT, name + ".svg"), "w", encoding="utf-8") as f:
            f.write(build(theme))
        print("wrote", name + ".svg", "W,H,depth =", W, H, D_EDGE)
