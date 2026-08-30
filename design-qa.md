# Ghost Terminal image-to-code QA

source visual truth: `C:\Users\tatsuya\.codex\generated_images\01a04bc2-eecb-79b2-bacf-571ba221dfa3\exec-083414bb-1806-478e-8211-ef53ab311924.png`

implementation screenshots:

- `C:\Users\tatsuya\Documents\sourcecode\razio\artifacts\ui-terminal-final\16-final-top.png`
- `C:\Users\tatsuya\Documents\sourcecode\razio\artifacts\ui-terminal-final\17-final-lower.png`
- `C:\Users\tatsuya\Documents\sourcecode\razio\artifacts\ui-terminal-final\19-final-details-devpanels.png`

## Capture and normalization

- Source: 853 × 2048 px mockup. It includes the terminal content but no Android system bars.
- Implementation: Pixel 10 Pro, Android 17, serial `56101FDCH006CX`, 1280 × 2856 physical px, override density 408 dpi. Native Compose has no CSS viewport; the semantic app window was `[0,204]–[1280,2764]` and system bars were treated as external chrome.
- The implementation is a vertically scrolling native screen, so top, output/footer, and expanded-details states were captured separately. The source and all three implementation captures were opened together in the final comparison pass. The chart, slider, output, and details regions were used as focused comparisons.
- State: `Vintage speaker` selected, RAZIO `ON`, DynamicsProcessing `Active`, details collapsed for `16`/`17`, details expanded for `19`, analyzer idle, Hiss/Crackle off.

## Final comparison evidence

- Layout: the header, six-item rail, terminal readout, curve panel, six frequency-boundary controls, output block, reset, details disclosure, and footer follow the source order. The six tabs fit in one row and `Vintage speaker` is no longer clipped.
- Typography: terminal-facing labels use the platform monospace family, reduced tab sizing, and cyan/amber hierarchy. Android's built-in monospace is smoother than the mock's bitmap/pixel lettering; this is a P3 polish difference, not a readability or layout failure.
- Spacing and rhythm: rectangular borders, compact rows, fine ticks, and the output/reset/details grouping were checked in `17-final-lower.png`. Expanded development values use the same fine-line slider treatment in `19-final-details-devpanels.png`.
- Colors/tokens: the green-black background, cyan grid/curve, amber active state/knobs, and lime section headings remain legible over the generated CRT texture. The output meter is intentionally shown idle in the capture (`解析待ち`), while the mock shows an active example.
- Imagery: `ghost_terminal_texture.png` is a generated raster background containing only binary rain, scanlines, and vignette; no UI labels or controls are baked into it. It is cropped to fill the app and does not replace interactive Compose content.
- Copy and interactions: `Vintage speaker`, six Japanese boundary labels, `RESET // プリセット初期値に戻す`, `DETAILS / 開く`/`閉じる`, `PEAK`, Hiss/Crackle, spectrum, and engine panels were present in the UI tree. Tapping the first frequency `＋` changed `180 Hz → 190 Hz`; tapping reset restored `180 Hz`.

## Comparison history

1. Baseline comparison (`artifacts/ui-audit-detail/01-current-top.png`, `02-current-sliders.png`, `03-current-details.png`) found actionable P2 drift: no CRT texture, clipped `Vintage speaker`, thick rounded Material sliders, and development panels visible in the collapsed main flow. The implementation added the generated texture, reduced the six-tab labels to 8sp, replaced frequency and development sliders with fine ticked tracks, moved Noise/Spectrum/Engine under Details, and changed reset/details controls to rectangular terminal buttons.
2. Revised comparison used the source plus `16-final-top.png`, `17-final-lower.png`, and `19-final-details-devpanels.png` in one visual input. No actionable P0/P1/P2 findings remained. The remaining differences are explicit product constraints or P3 polish: six editable frequency boundaries instead of the mock's three illustrative rows, the real preset curve's `-48 dB` axis, Android system chrome, idle meter state, and the lack of bitmap corner markers.

## Implementation checklist

- [x] Source and implementation captures opened together.
- [x] Six tabs fit and remain semantic/clickable.
- [x] Six frequency sliders and `−` / `＋` controls are visible and functional.
- [x] Curve, boundary positions, and values update together.
- [x] Reset restores the selected preset defaults.
- [x] Details expands non-frequency tuning plus Noise/Spectrum/Engine panels.
- [x] Unit tests and debug APK build passed in workflow `c6f13df1980811bbbe8484b64f4358b9`.
- [x] Pixel install, UI tree, interaction, audio-effect, FGS, and filtered logcat checks passed.

final result: passed
