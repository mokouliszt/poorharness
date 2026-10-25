# Monochrome on true black

Status: accepted (2026-10)

## Context

The interface is mostly text: what the user wrote, what the agent answered,
and the tools it ran in between.

## Decision

Colors are RGB channel tokens in `webui/src/index.css` (`void`, `surface`,
`raised`, `line`, `mute`, `ink`, `paper`, `danger`). White (`paper`) is kept
for what the user wrote and for primary actions; agent output is light gray on
black. IBM Plex Sans/Mono are bundled; Japanese falls back to the system font.

## Consequences

Components use the tokens, never literal colors.
