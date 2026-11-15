#!/usr/bin/env python3
"""Send ONE real XDND v5 file drop to an X11/XWayland window (python-xlib only).

Usage: python3 tools/xdnd-drop.py <window-title-substring|hex-id> <file-path>

Source side of the XDND protocol:
  own XdndSelection -> XdndEnter -> XdndPosition -> XdndDrop
  -> answer the target's SelectionRequest (text/uri-list) -> XdndFinished.
AWT's java.awt.dnd.DropTarget receives real X events; no Java import function
is called from here, so this exercises drag-and-drop, not the import function.

Prints a full DND_ event log to stdout. Exit 0 iff the target replied
XdndStatus(accept=1) AND requested the URI (i.e. the drop was accepted).
An accept=0 reply with XdndFinished is an honest "rejected by target" result.
"""
import os
import sys
import time
import urllib.parse
from Xlib import X, display, Xatom
from Xlib.protocol import event
from Xlib.ext import xtest

D = display.Display()
ROOT = D.screen().root


def atom(name):
    return D.intern_atom(name)


def find_window(spec):
    """Find the shallowest XdndAware window matching title/class/id."""
    aware = atom("XdndAware")
    hits = []

    def walk(w):
        try:
            nm = w.get_wm_name() or ""
            cls = w.get_wm_class() or ()
            aw = w.get_full_property(aware, Xatom.ATOM)
            if aw and (spec.lower() in nm.lower()
                       or any(spec.lower() in c.lower() for c in cls)
                       or hex(w.id) == spec):
                hits.append(w)
            for c in w.query_tree().children:
                walk(c)
        except Exception:
            pass

    walk(ROOT)
    return hits[0] if hits else None


def send_cm(dest, msg_type, data):
    dest.send_event(event.ClientMessage(
        window=dest, client_type=msg_type, data=(32, [d & 0xFFFFFFFF for d in data])))
    D.sync()


def main():
    if len(sys.argv) < 3:
        raise SystemExit("usage: xdnd-drop.py <window|hex-id> <file-path>")
    spec, path = sys.argv[1], os.path.abspath(sys.argv[2])
    target = find_window(spec)
    if target is None:
        raise SystemExit("DND_FAIL window-not-found spec=" + spec)
    g = target.get_geometry()
    print("DND_TARGET window=%s name=%r geometry=%dx%d+%d+%d"
          % (hex(target.id), target.get_wm_name(), g.width, g.height, g.x, g.y))
    print("DND_FILE %s" % path, flush=True)

    # Put the real pointer inside the window: AWT resolves the embedded drop
    # site from the pointer/root coordinates in XdndPosition.
    t = target.translate_coords(ROOT, g.width // 2, g.height // 2)
    cx, cy = max(0, t.x), max(0, t.y)
    xtest.fake_input(D, X.MotionNotify, x=cx, y=cy)
    D.sync()
    time.sleep(0.2)
    qp = ROOT.query_pointer()
    rx, ry = qp.root_x, qp.root_y
    print("DND_POINTER warp=(%d,%d) query=(%d,%d) child=%s"
          % (cx, cy, rx, ry, hex(qp.child.id) if qp.child else None), flush=True)

    src = ROOT.create_window(-10, -10, 10, 10, 0, X.CopyFromParent)
    sel = atom("XdndSelection")
    src.set_selection_owner(sel, X.CurrentTime)
    D.sync()
    if D.get_selection_owner(sel).id != src.id:
        raise SystemExit("DND_FAIL cannot-own-XdndSelection")

    uri = ("file://" + urllib.parse.quote(path) + "\r\n").encode("utf-8")
    plain = (path + "\n").encode("utf-8")
    types = [atom("text/uri-list"), atom("text/plain;charset=utf-8"), atom("UTF8_STRING")]
    xenter, xpos, xdrop = atom("XdndEnter"), atom("XdndPosition"), atom("XdndDrop")
    xstatus, xfinished = atom("XdndStatus"), atom("XdndFinished")
    leave = atom("XdndLeave")
    action = atom("XdndActionCopy")
    now = 1

    send_cm(target, xenter, [src.id, 5 << 24] + types)
    print("DND_SENT XdndEnter version=5 types=%s" % [D.get_atom_name(a) for a in types], flush=True)
    time.sleep(0.12)
    # version 5 -> target reads XdndActionList or defaults to COPY; root coords in data[2].
    send_cm(target, xpos, [src.id, 0, (rx << 16) | (ry & 0xFFFF), now, action])
    print("DND_SENT XdndPosition root=(%d,%d) action=XdndActionCopy" % (rx, ry), flush=True)
    time.sleep(0.25)
    send_cm(target, xdrop, [src.id, 0, now, 0, 0])
    print("DND_SENT XdndDrop", flush=True)

    accepted = False
    answered = 0
    finished = False
    deadline = time.time() + 8
    while time.time() < deadline:
        while D.pending_events():
            e = D.next_event()
            if e.type == X.ClientMessage:
                if e.client_type == xstatus:
                    words = e.data[1] if isinstance(e.data, tuple) else list(e.data.data32)
                    bit = words[1] & 1
                    accepted = accepted or bit == 1
                    print("DND_RECV XdndStatus accept=%d" % bit, flush=True)
                elif e.client_type == xfinished:
                    finished = True
                    print("DND_RECV XdndFinished", flush=True)
            elif e.type == X.SelectionRequest:
                tgt = D.get_atom_name(e.target)
                prop = e.property or e.target
                data = plain if tgt in ("UTF8_STRING", "TEXT", "STRING", "text/plain;charset=utf-8") else uri
                req = D.create_resource_object("window", e.requestor.id)
                req.change_property(prop, e.target, 8, list(data))
                req.send_event(event.SelectionNotify(
                    time=e.time, requestor=e.requestor, selection=e.selection,
                    target=e.target, property=prop))
                D.sync()
                answered += 1
                print("DND_RECV SelectionRequest target=%s -> %dB" % (tgt, len(data)), flush=True)
        if finished and answered:
            break
        time.sleep(0.03)
    send_cm(target, leave, [src.id, 0, 0, 0, 0])

    print("DND_RESULT accepted=%s selectionRequests=%d finished=%s uri=%s"
          % (accepted, answered, finished, uri.decode().strip()), flush=True)
    # Exit 0 only when the target actually accepted the drop and pulled the URI.
    sys.exit(0 if (accepted and answered) else 1)


if __name__ == "__main__":
    main()
