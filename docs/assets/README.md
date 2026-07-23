# README and repository assets

This directory contains the public-facing visuals used by Potaty's README and GitHub repository settings. The assets follow [the Potaty design system](../../DESIGN.md): quiet technical field notes, a mineral-neutral palette, semantic color, and ASCII as the visual centre of gravity.

## Asset inventory

| File | Dimensions | Role | Source |
|---|---:|---|---|
| `readme-hero.svg` | 1280×640 | README opening image | Hand-authored SVG |
| `readme-pipeline.svg` | 1200×360 | Source-to-artifact trust flow | Hand-authored SVG |
| `qa/final-prompt-desktop.png` | 1440×1000 | Real prompt-result evidence | Production-bundle browser QA |
| `qa/final-transcript-desktop.png` | 1440×1000 | Real transcript-result evidence | Production-bundle browser QA |
| `qa/final-prompt-mobile.png` | 390×844 | Real responsive-result evidence | Production-bundle browser QA |
| `github-social-preview-art.jpg` | 1774×887 | Text-free social-card artwork | OpenAI built-in image generation, then JPEG-optimized |
| `github-social-preview.svg` | 1280×640 | Editable social-card composition | Hand-authored SVG over the generated artwork |
| `github-social-preview.png` | 1280×640 | Upload-ready GitHub social preview | Chromium render of the SVG composition |

`github-social-preview.png` is below GitHub's 1 MiB upload limit. After the repository is public, a maintainer can upload it under **Settings → Social preview**. The checked-in file does not change repository settings by itself.

## Accessibility and rendering

- Every README image has meaningful alternative text at its call site.
- The two README SVGs include their own `<title>` and `<desc>` metadata.
- Text and diagrams remain high-contrast without relying on color alone.
- SVGs prefer Samsung Sharp Sans only when it is locally installed or deployment-provided. No proprietary font binary is embedded or redistributed.
- The social preview uses a solid background so it remains legible when a sharing platform ignores transparency.

## Generated-art provenance

The text-free social artwork was generated once with the built-in OpenAI image tool. It contains no product wordmark, readable text, people, customer data, or third-party logo. All product copy, the Potaty mark, and the final layout are deterministic SVG overlays.

<details>
<summary>Generation prompt</summary>

```text
Use case: stylized-concept
Asset type: GitHub repository social preview accent artwork
Primary request: Create an original, restrained editorial illustration showing three bounded source artifacts—a prompt sheet, a transcript strip with small timestamp-like tick marks, and a repository file tree—converging through precise connector lines into one clean ASCII-style box-and-arrow diagram.
Scene/backdrop: solid near-black technical field-notebook surface with a very fine orthogonal grid; no gradient.
Style/medium: flat 2D matte ink and paper-cut geometry, technical field notebook meets calm laboratory console, exact and quiet, not playful, not 3D.
Composition/framing: wide 2:1 landscape. Keep the left 40 percent calm and mostly empty for a separate deterministic title overlay. Place the source-to-diagram composition in the right 60 percent with generous safe margins.
Color palette: #0d100e near-black, #f2efe4 chalk, #98aa8f sage, #c87a61 terracotta, #8eabb4 sky. Use color sparingly and semantically.
Materials/textures: subtle paper grain and dry ink only.
Text: none. Do not render any readable letters, words, numbers, code, or logos; use abstract horizontal marks and simple symbols only.
Constraints: no people, no devices, no product UI mockup, no branding, no watermark, no third-party marks.
Avoid: gradients, glassmorphism, glow, neon, decorative blobs, stock illustration style, busy detail, pseudo-text, illegible AI lettering.
```

</details>

## Maintenance rules

- Keep source SVG and rendered PNG dimensions stable unless every README call site is reviewed.
- Re-render `github-social-preview.png` after changing its SVG or source artwork.
- Use synthetic data only. Never turn a customer diagram, private repository, real transcript, token, or production log into a README asset.
- Record any new third-party asset and its licence in [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md).
- Project-created assets are distributed under the repository's [Apache License 2.0](../../LICENSE).
