---
version: alpha
name: vibeMusic Velvet Encore
description: >-
  Dark-first dual-platform design language for the vibeMusic music app.
  Desktop theme ("Spotify Encore") and mobile theme ("Velvet Night") share one
  brand green (#31c27c), tonal dark surfaces, and restrained motion.
colors:
  primary: "#31c27c"
  primary-hover: "#28a86b"
  primary-deep: "#1a8a4a"
  primary-tint: "rgba(49,194,124,0.12)"
  surface-base: "#121212"
  surface-elevated: "#181818"
  surface-card: "#1f1f1f"
  surface-hover: "#282828"
  on-surface: "#ffffff"
  on-surface-secondary: "#b3b3b3"
  on-surface-tertiary: "#8a8a8a"
  overlay-scrim: "rgba(0,0,0,0.5)"
  mobile-surface-base: "#08080a"
  mobile-surface-elevated: "#111116"
  mobile-card-fill: "rgba(255,255,255,0.04)"
  mobile-glass: "rgba(12,12,16,0.82)"
  mobile-on-surface: "#e8e8ec"
  mobile-on-surface-secondary: "#8a8a92"
typography:
  title-lg:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
    fontSize: 18px
    fontWeight: 600
    lineHeight: 1.35
  body-lg:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
    fontSize: 16px
    fontWeight: 500
    lineHeight: 1.5
  body-md:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
    fontSize: 14px
    fontWeight: 400
    lineHeight: 1.5
  body-sm:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
    fontSize: 13px
    fontWeight: 400
    lineHeight: 1.45
  caption-sm:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
    fontSize: 12px
    fontWeight: 500
    lineHeight: 1.4
  mobile-title:
    fontFamily: "'HarmonyOS Sans', 'PingFang SC', -apple-system, sans-serif"
    fontSize: 18px
    fontWeight: 600
    lineHeight: 1.35
  mobile-body-md:
    fontFamily: "'HarmonyOS Sans', 'PingFang SC', -apple-system, sans-serif"
    fontSize: 14px
    fontWeight: 500
    lineHeight: 1.5
spacing:
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px
  xl: 20px
  page: 16px
rounded:
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px
  xl: 18px
  full: 999px
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    rounded: "{rounded.sm}"
    height: 48px
    padding: 14px
  button-primary-hover:
    backgroundColor: "{colors.primary-hover}"
  button-pill-ghost:
    backgroundColor: "transparent"
    rounded: "{rounded.full}"
    padding: 8px
  input-field:
    backgroundColor: "{colors.surface-elevated}"
    textColor: "{colors.on-surface}"
    typography: "{typography.body-md}"
    rounded: "{rounded.sm}"
    padding: 14px
  card:
    backgroundColor: "{colors.surface-card}"
    rounded: "{rounded.md}"
  modal-dialog:
    backgroundColor: "{colors.surface-card}"
    rounded: "{rounded.lg}"
  toast-success:
    backgroundColor: "{colors.primary}"
  tag-quality:
    backgroundColor: "{colors.primary-tint}"
    rounded: "{rounded.xs}"
  modal-overlay:
    backgroundColor: "{colors.overlay-scrim}"
  page-background:
    backgroundColor: "{colors.surface-base}"
  progress-track:
    backgroundColor: "{colors.surface-hover}"
    rounded: "{rounded.full}"
    height: 4px
  progress-fill:
    backgroundColor: "{colors.primary}"
    height: 4px
  card-mobile:
    backgroundColor: "{colors.mobile-card-fill}"
    rounded: "{rounded.md}"
  bar-glass-mobile:
    backgroundColor: "{colors.mobile-glass}"
---

# vibeMusic Design System

Design language extracted from the shipped `vibemusic-web` codebase. It merges two platform themes into one normative system.

## Overview

vibeMusic is a dark-first music streaming platform. The personality is **nocturnal, focused, and performance-conscious**: near-black tonal surfaces keep attention on album art and controls, and a single heritage green drives every interaction cue.

Two platform themes share this system:

- **Desktop — "Spotify Encore"**: Spotify-style layered neutrals (`#121212` → `#282828`) with QQ-Music heritage green as the sole accent.
- **Mobile — "Velvet Night"**: deeper black (`#08080a`), translucent white-alpha cards, frosted-glass bars, iOS-like easing.

Both converge on **one brand green, `#31c27c`** (unified decision recorded in `main.css`). Visual hierarchy comes from background elevation and the three-tier text scale — not from extra accent colors.

## Colors

A tonal dark neutral ramp plus a single green accent. All desktop text tiers were verified ≥ 4.5:1 against their backgrounds.

- **Primary (`#31c27c`):** The only brand color. Play buttons, active nav, progress fills, focus rings, toasts, links. Hover state `#28a86b`; deep gradient end `#1a8a4a`.
- **Primary tint (`rgba(49,194,124,0.12)`):** Soft wash behind active icons, quality tags, and selected rows.
- **Surface ramp (desktop):** base `#121212`, elevated `#181818`, card `#1f1f1f`, hover `#282828`. Depth = lighter surface, never borders or glow.
- **Mobile surfaces:** base `#08080a`, elevated `#111116`; cards are `rgba(255,255,255,0.04)` fills (hover `0.08`); bars use glass `rgba(12,12,16,0.82)`.
- **Text (desktop):** `#ffffff` primary (19.6:1 on base), `#b3b3b3` secondary (8.9:1), `#8a8a8a` tertiary (5.4:1).
- **Text (mobile):** `#e8e8ec` primary, `#8a8a92` secondary.
- **Scrim (`rgba(0,0,0,0.5)`):** modal and popup backdrops.

## Typography

System font stacks only — no webfonts. Weights stay within 400–600.

- **Headings/titles:** 18px semibold (600).
- **Body:** 14px regular is the default; 16px medium for prominent body and primary-button labels; 13px for dense secondary info.
- **Captions/tags:** 12px medium.
- **Desktop stack:** `-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif` (set globally in `App.vue`).
- **Mobile stack:** `'HarmonyOS Sans', 'PingFang SC', -apple-system, sans-serif` (`--m-font-stack`), sizes 12/14/16/18/20.

## Layout

Desktop is a fixed three-region shell; mobile is a full-height single column.

- **Desktop:** fixed left sidebar **260px**, top bar with `16px 28px` padding, scrollable content area with bottom padding **88px** to clear the player bar.
- **Mobile:** `100dvh` shell with bottom TabBar + mini-player; page gutter **16px** (`--m-space-page`); respect safe areas via `env(safe-area-inset-bottom/top)`.
- **Spacing rhythm:** 4px-based scale (xs 4 → xl 20). Mobile tokens live in `--m-space-*`.

## Elevation & Depth

Depth is conveyed by **tonal layering**, not heavy shadows.

- Desktop shadows are restrained: `--shadow-1: 0 1px 3px rgba(0,0,0,.4)` for small lifts; `--shadow-2: 0 8px 24px rgba(0,0,0,.5)` for modals/popovers. No neon glows.
- Mobile bars and popups use **frosted glass**: `backdrop-filter: blur(16px) saturate(1.2)` over `rgba(12,12,16,0.82)` (opaque fallback `rgba(12,12,16,0.96)`).

## Shapes

Rounded but disciplined; radius scales up slightly on mobile.

- Scale: xs 4 · sm 8 · md 12 · lg 16 · xl 18 · pill/full 999px.
- Buttons and inputs: sm (desktop) → md (mobile save buttons may use 10px between sm/md).
- Cards md; modals lg; mobile large sheets xl.
- Chips, tags, and outline buttons are **pills** (`border-radius: 999px`); avatars, close buttons, and the vinyl progress thumb are circles.

## Components

Patterns observed across `PlayerBar`, `LoginModal`, `TopBar`, `HomeView`, and mobile views.

- **Primary button:** solid `#31c27c`, white label, 48px tall, radius sm; hover `#28a86b`. Used sparingly — one per view region.
- **Pill ghost button:** transparent, 1px `#31c27c` border, green label ("Load more", "Log in"); hover fills solid green with white label.
- **Inputs:** elevated background, no visible border until focus; focus ring/border turns `#31c27c`.
- **Icon buttons:** transparent; hover/active gets a faint tint (`rgba(255,255,255,.06)` neutral or primary-tint when active).
- **Quality tag:** primary-tint background, green text, radius xs, ~9–11px uppercase-feel micro-label.
- **Toast success:** solid green fill, white text.
- **Progress/volume bars:** green fill on a `#282828` track; drag thumb is a 12px circle.
- **Equalizer bars:** 2–3px wide green bars animating via the shared `eq` keyframes — the universal "now playing" signifier.

## Do's and Don'ts

- Do use `#31c27c` as the **only** brand green on both platforms.
- Don't introduce the legacy mobile emerald `#2ee59a`, gold `#f0b90b`, or gradients derived from them — deprecated pending cleanup (`mobile-theme.css` still carries `--m-primary`/`--m-gold`; existing usages migrate toward primary green).
- Don't invent additional accent colors; express hierarchy through the surface ramp and text tiers.
- Do prefer CSS custom properties (`--primary`, `--bg-*`, `--text-*`, `--radius-*`, mobile `--m-*`) over new hardcoded hex values.
- Do maintain WCAG AA (≥ 4.5:1) for body text; note that white-on-green buttons (~2:1) are an accepted existing exception for large/bold labels only.
- Do keep keyboard focus visible: 2px solid `#31c27c` outline with 2px offset (`:focus-visible`).
- Don't add neon glows or long shadow chains; stick to `--shadow-1/--shadow-2`.
- Do honor `prefers-reduced-motion` (globally forced to near-zero durations).
- Do pair every desktop view with its mobile counterpart (`XxxView.vue` ↔ `views/mobile/MXxxView.vue`) using `--m-*` tokens on mobile.
