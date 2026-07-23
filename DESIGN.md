# Potaty Design System

## Product context

- **What this is:** A source-grounded studio that turns prompts, transcripts, and GitHub repositories into editable ASCII diagrams.
- **Who it is for:** Developers, technical leads, product teams, consultants, and anyone who needs a diagram they can verify rather than merely admire.
- **Core promise:** Every generated diagram should feel hand-composed, remain useful as plain text, and make uncertainty visible.
- **Project type:** Open-source web application with a generation studio, evidence workbench, and manual ASCII editor.

## Aesthetic direction

- **Direction:** Technical field notebook meets a calm laboratory console.
- **Mood:** Deliberate, tactile, quiet, and exact. The interface should feel like a serious instrument whose personality comes from typography, spacing, and ASCII craft rather than decoration.
- **Decoration:** Intentional. Fine grid lines, registration marks, paper-like tonal shifts, and one hand-built SVG mark. No ornamental gradients, glassmorphism, glowing blobs, or stock illustration.
- **Composition:** Asymmetric and workspace-first. Input and source context occupy the narrow side; the diagram is the visual centre of gravity.
- **Reference:** The `output_sample/` images define the target restraint, density, and monochrome-diagram quality. They are inspiration, not assets to reproduce.

### Safe choices

- Keep the generated diagram in a true monospace face and preserve whitespace exactly.
- Keep primary actions and status visible throughout long-running generation.
- Use familiar source, canvas, and inspector regions in the result workbench.

### Deliberate risks

- Avoid the default bright SaaS palette. A mineral neutral palette makes the ASCII output the hero and reduces visual fatigue.
- Present evidence and uncertainty as first-class product material, not as a hidden diagnostics drawer.
- Let the landing screen behave like a working instrument immediately, rather than a marketing page in front of the tool.

## Typography

- **Display / UI:** `Samsung Sharp Sans`, then `SamsungOne`, the dynamic-subset `Pretendard Variable`, `Noto Sans KR`, and the platform sans-serif fallback.
- **ASCII / code / data:** JetBrains Mono, self-hosted from `src/main/resources/fonts` under the SIL Open Font License.
- **Numerals:** Use tabular numerals for confidence, progress, timing, and cost.
- **Weights:** 400 body, 600 controls, 700 display. Do not simulate unavailable weights.
- **Rendering:** `font-synthesis: none`, antialiasing enabled, body letter spacing `-0.01em`, labels `0.02em`.

### Samsung Sharp Sans licensing

Samsung Sharp Sans is a proprietary brand typeface. This Apache-2.0 repository must not redistribute font binaries without an explicit redistribution licence. The CSS therefore prefers an installed or deployment-provided licensed copy and falls back cleanly. Maintainers who hold a valid webfont licence may provide the WOFF2 files at deploy time as documented in `src/main/resources/fonts/samsung-sharp-sans/README.md`.

## Color

The dark theme is the primary product surface. Light mode is supported for the legacy editor and exported artifacts, but the generation studio should remain a low-glare workspace.

| Token | Value | Use |
|---|---:|---|
| Ink 950 | `#0d100e` | Page background |
| Ink 900 | `#121613` | Main work surface |
| Ink 850 | `#171c18` | Elevated surface |
| Ink 800 | `#1e241f` | Controls and hover |
| Chalk 50 | `#f2efe4` | Primary text and ASCII strokes |
| Chalk 200 | `#d8d4c8` | Secondary text |
| Chalk 500 | `#918f87` | Muted labels |
| Sage 400 | `#98aa8f` | Primary action / grounded state |
| Sage 300 | `#b5c3ae` | Primary hover |
| Terracotta 400 | `#c87a61` | Warning / inferred state |
| Sky 400 | `#8eabb4` | Informational state |
| Rose 400 | `#d47a78` | Error / destructive state |
| Line | `rgba(242,239,228,.12)` | Dividers |

Color is semantic and scarce. A generated ASCII diagram is always monochrome; color belongs to UI state, evidence, and focus.

## Spacing and shape

- **Base unit:** 4px.
- **Scale:** 4, 8, 12, 16, 20, 24, 32, 40, 48, 64.
- **Density:** Comfortable at input, compact in the workbench.
- **Radius:** 4px controls, 8px panels, 12px major surfaces, full only for status dots and compact pills.
- **Borders:** 1px neutral rules. Elevated surfaces use tonal separation before shadows.
- **Shadows:** Only for overlays and floating menus; never for every card.

## Layout

- **Wide desktop:** 12-column shell. Input 4 columns, preview 8 columns. Result workbench uses 240px source rail, flexible canvas, and 288px inspector.
- **Laptop:** Inspector becomes an overlay/drawer below 1120px.
- **Mobile:** Single column, sticky action footer, horizontal output scrolling, 44px minimum touch targets.
- **Max marketing/input width:** 1440px. The workbench may use the full viewport.
- **Safe areas:** Respect `env(safe-area-inset-*)` on mobile.

## Motion

- **Approach:** Intentional and functional.
- **Micro:** 100–140ms for hover and press.
- **Short:** 180–240ms for tabs, panels, and disclosure.
- **Medium:** 320–420ms for studio-to-workbench transitions.
- **Easing:** `cubic-bezier(.22, 1, .36, 1)` for entering, `ease-in` for exit.
- **Reduced motion:** All non-essential transforms and animation stop under `prefers-reduced-motion: reduce`.

## Interaction principles

1. The three source paths, prompt, transcript, and GitHub, are equally obvious on first load.
2. File drops always have a keyboard-equivalent file picker.
3. Generation exposes stages, elapsed time, cancellation affordance, and an actionable failure state.
4. Results expose ASCII first, Mermaid second, then evidence, confidence, warnings, and export.
5. Copy and download actions provide visible status without toast spam.
6. Secrets are never placed in persistent browser storage. Development credentials use session storage only.
7. No control is icon-only unless it has a visible tooltip and accessible name.

## Accessibility

- Target WCAG 2.2 AA contrast and visible focus on every interactive element.
- Use semantic buttons, labels, tabs, status regions, and dialogs.
- Generation status uses `role="status"`; failures use `role="alert"`.
- The ASCII result has an accessible text label and remains selectable.
- Never communicate grounded/inferred/error state by color alone.
- Zoom and horizontal scroll must not trap keyboard focus.

## Asset rules

- Prefer deterministic, hand-authored SVG for the product mark and interface diagrams.
- Do not embed third-party logos unless their licence and trademark use are documented.
- Raster generative artwork is unnecessary for the core product and should not compete with the ASCII output.

## Decisions log

| Date | Decision | Rationale |
|---|---|---|
| 2026-07-10 | Adopt the technical field-notebook direction | It continues the MonoSketch sample character while giving Potaty a distinct, trustworthy face. |
| 2026-07-10 | Make Samsung Sharp Sans deployment-provided | Preserve the requested type direction without putting proprietary font binaries in an Apache-2.0 repository. |
| 2026-07-10 | Keep ASCII as the primary result surface | Plain-text fidelity and editability are the product's clearest differentiation. |
