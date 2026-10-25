# Icons are generated, then committed

Status: accepted (2026-10; revised in 0.5.0 for the hanging-figure artwork)

## Context

The launcher artwork (`assets/icon-source.png`) is a finished icon: grayscale
art on a near-black rounded tile with a lit border. Android draws its own icon
shape, and needs adaptive and monochrome layers, a status-bar icon and web
logos.

## Decision

`tools/make-icons.py` drops the tile and keeps the art: everything outside a
rounded box around it is cleared, and the rope is continued up past the tile's
top so the figure hangs from the edge of whatever shape the launcher uses.
Layers are white with alpha from luminance (at or below a black point of 24 is
transparent); the same layer is the Android 13+ monochrome icon. The status-bar
icon is the figure alone (what touches the vertical centre line), solid, since
the circuit lines are noise at 24 dp. The favicon is the artwork as drawn. The
script is run after the artwork changes and its PNGs are committed; the build
does not regenerate them.

## Consequences

Artwork with a different composition needs the constants at the top of the
script re-measured ([`docs/maintenance.md`](../maintenance.md)).
