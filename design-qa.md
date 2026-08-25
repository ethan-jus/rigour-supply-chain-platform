# Sales Check-in Product Design QA

## Scope

- Reference: `/Users/ethan/.codex/generated_images/01a032c2-a8fd-7cd0-9402-b40bcb449cf7/exec-ed0841e1-efc8-43e9-ad77-99052be11af1.png`
- Implemented page: `services/rigour-sales-work-service/sales-work-service/src/main/resources/static/sales-checkin/`
- Tested viewport: 426 x 926 CSS pixels
- Tested state: Beijing, salesperson selected, synthetic readable location resolved, three nearby stores loaded, first registered store selected
- Implementation capture: `/tmp/rigour-sales-checkin-option1-local-selected-final-v2.png`
- Side-by-side comparison: `/tmp/rigour-sales-checkin-design-comparison-passed.png`

## Interaction QA

- City and salesperson selectors work; salesperson options display names only.
- Successful positioning shows a readable address, collection time, and accuracy without exposing raw coordinates as the primary UI.
- Nearby registered stores can be selected directly.
- Nearby unregistered Amap POIs open the prefilled store-enrichment flow.
- A missing store can be added without maintaining a second equal-level workflow.
- The selected nearby row is highlighted without rendering a duplicate selected-store card.
- Recording removal pauses playback, clears the audio source, reloads the player, revokes the object URL, and removes the file from form state.
- Static JavaScript syntax and DOM ID contracts passed; the tested browser console had no warnings or errors.

## Visual Comparison History

The first comparison found P2 density mismatches: the hero, located-state button, and nearby rows were too tall, which delayed the core customer and visit fields. The implementation was tightened with a shorter solid-color hero, a compact reposition link after location succeeds, denser nearby-store rows, reduced repeated section headings, and a single selected-row state.

The final same-width comparison found no actionable P0, P1, or P2 differences.

## Fidelity Surfaces

- Typography: compact native sans-serif hierarchy matches the selected direction; field labels and values remain readable on a phone.
- Spacing and density: the core flow fits more actions above the fold while preserving touch targets.
- Color: deep teal brand header, white cards, neutral borders, and restrained status colors match the selected direction.
- Shape: flat cards and modest radii are consistent across location, store, media, and consent areas.
- Assets and image quality: the reference contains no photographic assets. No placeholder, fake raster asset, handcrafted SVG, emoji icon, or CSS illustration was introduced.
- Copy: labels prioritize action language and readable addresses; privacy details remain available without dominating the primary flow.

## Intentional Differences

- City and salesperson are side by side instead of stacked to shorten the anonymous mobile form.
- Decorative line icons from the visual reference are not approximated with text symbols or fake drawings; clear text labels and native controls retain the affordances.
- The public page does not show first-visit or revisit counts. Those counts are available only in the protected admin list and CSV because exposing them through anonymous store/salesperson identifiers would disclose visit-history aggregates.
- Required media and consent steps can extend below the first viewport because they are functional and compliance constraints, not decorative content.

## Result

final result: passed
