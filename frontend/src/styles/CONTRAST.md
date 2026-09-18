# Hive contrast audit

Every text/background pair that the Hive design system actually ships, measured
with the WCAG 2.x relative-luminance formula (`(L1 + 0.05) / (L2 + 0.05)`).

**Rules this table enforces**

- Body text and UI labels: **>= 4.5:1** (WCAG 1.4.3 AA). Nothing below that ships.
- Non-text indicators - borders, focus rings, state markers: **>= 3:1** (1.4.11).
- BRANDING_GUIDE.md asks for 7:1 on the yellow/black pairing; the gold-fill +
  black-text combination reaches **12.41:1**, so it clears AAA as well.

---

## 1. The gold constraint

`#FFD700` on `#FFFFFF` is **1.40:1**. That is not a near miss - it is roughly a
third of the AA floor, and no font size or weight rescues it.

Consequence, applied throughout the system:

| Gold is used for                                   | Gold is never used for                      |
| -------------------------------------------------- | ------------------------------------------- |
| Button and badge **fills** (with `#1A1A1A` on top) | Text on white / off-white / light gray      |
| Focus rings and active underlines                  | Icons that carry meaning on a light surface |
| Accent rules, the hex mark, the header bottom edge | Borders that are the only boundary cue      |
| Text **on the near-black chrome** (12.41:1 there)  | Link colour, error colour, body copy        |

The one derived gold that _is_ usable as text on light surfaces is
`--hive-gold-text-on-light: #5C4A00` (8.63:1 on white). It is available for
future use; nothing in the current component set needs it.

---

## 2. Text on light surfaces

| Foreground          | Background        | Ratio       | Level | Where it ships                                      |
| ------------------- | ----------------- | ----------- | ----- | --------------------------------------------------- |
| `#1A1A1A` ink       | `#FFFFFF` surface | **17.40:1** | AAA   | body copy, card text, modal body, chip name, labels |
| `#1A1A1A` ink       | `#F5F5F5` app bg  | **15.96:1** | AAA   | page headings, text directly on the app background  |
| `#1A1A1A` ink       | `#F0F0F0` sunken  | **15.27:1** | AAA   | table headers, modal footer, "neutral" avatar       |
| `#424242` dark gray | `#FFFFFF` surface | **10.05:1** | AAA   | secondary text, hints, chip secondary line          |
| `#424242` dark gray | `#F5F5F5` app bg  | **9.22:1**  | AAA   | page-header eyebrow, shell footer                   |
| `#424242` dark gray | `#F0F0F0` sunken  | **8.82:1**  | AAA   | Draft and Canceled badge labels                     |
| `#0B4F8A` info blue | `#FFFFFF` surface | **8.40:1**  | AAA   | links (`--hive-color-text-link`)                    |
| `#B3261E` error red | `#FFFFFF` surface | **6.54:1**  | AA    | field error text, required asterisk                 |

### The `#757575` exception

| Foreground | Background | Ratio      | Verdict                    |
| ---------- | ---------- | ---------- | -------------------------- |
| `#757575`  | `#FFFFFF`  | **4.61:1** | Passes AA - but see below  |
| `#757575`  | `#F5F5F5`  | **4.23:1** | **FAILS AA** for body text |
| `#757575`  | `#F0F0F0`  | **4.04:1** | **FAILS AA** for body text |

The branding guide lists medium gray `#757575` as "secondary text". On the
off-white `#F5F5F5` app background that is 4.23:1 and does not pass. The system
therefore **promotes secondary text to `#424242`** and restricts `#757575` to:

- **disabled** control text and placeholders (WCAG 1.4.3 exempts inactive
  controls, and a placeholder is never the only label - `<hive-form-field>`
  always renders a real `<label>`);
- **control borders**, where 4.61:1 on white clears the 3:1 non-text floor.

---

## 3. Text on fills

| Foreground    | Background            | Ratio       | Level | Where it ships                               |
| ------------- | --------------------- | ----------- | ----- | -------------------------------------------- |
| `#1A1A1A` ink | `#FFD700` gold        | **12.41:1** | AAA   | primary button, In Progress badge, skip link |
| `#1A1A1A` ink | `#F0C800` gold hover  | **10.75:1** | AAA   | primary button hover                         |
| `#1A1A1A` ink | `#D9B400` gold active | **8.68:1**  | AAA   | primary button pressed                       |
| `#1A1A1A` ink | `#FFF8D6` gold tint   | **16.29:1** | AAA   | secondary/tertiary button hover              |
| `#1A1A1A` ink | `#FF5252` error       | **5.45:1**  | AA    | danger button (guide: "red with black text") |
| `#1A1A1A` ink | `#FF3B3B` error hover | **4.92:1**  | AA    | danger button hover                          |

## 4. Text on the near-black chrome (app header)

| Foreground     | Background        | Ratio       | Level | Where it ships                     |
| -------------- | ----------------- | ----------- | ----- | ---------------------------------- |
| `#FFFFFF`      | `#1A1A1A`         | **17.40:1** | AAA   | wordmark, nav hover, user slot     |
| `#F0F0F0`      | `#1A1A1A`         | **15.27:1** | AAA   | inactive nav links                 |
| `#FFD700` gold | `#1A1A1A`         | **12.41:1** | AAA   | **active** nav link                |
| `#F0F0F0`      | `#2B2B2B` raised  | **12.42:1** | AAA   | nav link on hover background       |
| `#FFD700` gold | `#2B2B2B` raised  | **10.09:1** | AAA   | active nav link on hover           |
| `#BDBDBD`      | `#1A1A1A`         | **9.26:1**  | AAA   | secondary chip text on dark        |
| `#FFFFFF`      | `#2B2B2B` raised  | **14.15:1** | AAA   | ghost button hover in the header   |
| `#FFFFFF`      | `#3D3D3D` pressed | **10.86:1** | AAA   | ghost button pressed in the header |

Everything in this table below the wordmark row is reached through the
`.hive-surface-inverse` context class (`_tokens.scss`, section 11) rather than a
per-component "on dark" modifier: the class re-points `--hive-color-text`,
`--hive-color-text-secondary`, `--hive-color-surface`, the two ghost-interaction
fills and the focus ring at their on-ink values for the whole subtree, so the
user chip and the sign-out button in the header inherit these ratios without
knowing where they were placed.

## 5. Status badges (all five contract statuses)

| Status      | Text      | Background | Ratio       | Shape cue (redundant with colour) |
| ----------- | --------- | ---------- | ----------- | --------------------------------- |
| Draft       | `#424242` | `#F0F0F0`  | **8.82:1**  | dotted circle + **dashed** border |
| Todo        | `#1A1A1A` | `#FFFFFF`  | **17.40:1** | hollow circle + solid ink border  |
| In Progress | `#1A1A1A` | `#FFD700`  | **12.41:1** | half-filled circle                |
| Completed   | `#1B5E20` | `#E8F5E9`  | **7.00:1**  | check mark                        |
| Canceled    | `#424242` | `#F0F0F0`  | **8.82:1**  | multiplication cross              |

Draft and Canceled share a palette on purpose (both are "not live work"), which
is exactly why the **text label is mandatory** and the glyphs differ. No badge is
ever rendered without its label.

## 6. Alerts / toasts

| Variant | Body text on tint      | Ratio       | Accent (heading, icon, border) | Ratio      |
| ------- | ---------------------- | ----------- | ------------------------------ | ---------- |
| info    | `#1A1A1A` on `#E7F1FB` | **15.23:1** | `#0B4F8A` on `#E7F1FB`         | **7.35:1** |
| success | `#1A1A1A` on `#E8F5E9` | **15.47:1** | `#1B5E20` on `#E8F5E9`         | **7.00:1** |
| error   | `#1A1A1A` on `#FFEBEE` | **15.22:1** | `#B3261E` on `#FFEBEE`         | **5.72:1** |

Each variant also ships a distinct glyph and a visually hidden severity prefix
("Information:", "Success:", "Error:"), so severity survives greyscale.

---

## 7. Non-text contrast (WCAG 1.4.11, 3:1 floor)

| Element                         | Colours                | Ratio       | Verdict                         |
| ------------------------------- | ---------------------- | ----------- | ------------------------------- |
| Input / select border           | `#757575` on `#FFFFFF` | **4.61:1**  | pass                            |
| Secondary button border         | `#1A1A1A` on `#FFFFFF` | **17.40:1** | pass                            |
| Focus ring - **gold band only** | `#FFD700` on `#FFFFFF` | **1.40:1**  | **would fail alone**            |
| Focus ring - ink hairline       | `#1A1A1A` on `#FFFFFF` | **17.40:1** | pass - carries the ring         |
| Focus ring on dark chrome       | `#FFD700` on `#1A1A1A` | **12.41:1** | pass (gold alone is fine there) |

This is the reason `--hive-focus-ring` is a **two-tone** ring:

```
0 0 0 2px <surface>   /* breathing room */
0 0 0 5px #FFD700     /* the brand band - visible, not load-bearing */
0 0 0 6px #1A1A1A     /* the hairline that actually satisfies 1.4.11 */
```

The user sees a gold focus ring, as the branding guide asks for
("Focus states: Yellow border with shadow"), and the indicator still clears 3:1
against any light background. Under `forced-colors: active` the whole ring is
replaced by the system `Highlight` colour.

Decorative-only colours, exempt from 1.4.11 because they are never the sole way
to perceive a component: `#E0E0E0` card hairlines, `#F0F0F0` dividers, the gold
accent bar on cards and the page-header rule.

---

## 8. Colour is never the only channel

| Information            | Channel 1 | Channel 2                | Channel 3             |
| ---------------------- | --------- | ------------------------ | --------------------- |
| Task status            | colour    | **text label** (always)  | shape glyph           |
| Alert severity         | colour    | glyph                    | hidden text prefix    |
| Active navigation item | gold text | gold underline           | `aria-current="page"` |
| Invalid form field     | red text  | warning glyph + "Error:" | `aria-invalid="true"` |
| Required field         | red `*`   | "(required)" for AT      | `aria-required`       |
| Loading button         | spinner   | "loading" for AT         | `aria-busy="true"`    |

---

## Reproducing these numbers

```js
const lin = (c) => ((c /= 255) <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
const L = (hex) => {
  const h = hex.replace('#', '');
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16));
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
};
const ratio = (fg, bg) => (Math.max(L(fg), L(bg)) + 0.05) / (Math.min(L(fg), L(bg)) + 0.05);
```
