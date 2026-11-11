# Coloured Lighting (Fabric 1.20.1, client-side)

Coloured block light + a cheap "fake raytracing" colour-bleed, applied while chunks are meshed.
Built on top of the Fabric template (Loom / Yarn / Loader versions are the template's).

## Build
    ./gradlew build          # -> build/libs/coloured-lighting-<version>.jar
    ./gradlew runClient      # test in a dev client

Use the same JDK the template's CI uses (JDK 25 builds fine, the output targets Java 17).

## What it does
1. **Coloured light** - every luminous block gets a light colour (built-in table for vanilla blocks,
   otherwise auto-derived from the block's texture). Vanilla's own 0-15 light levels are traced
   back to the emitter, so hues blend where lights overlap.
2. **Colour bleed** - for each vertex, the solid blocks around it reflect their average colour onto the
   surface (red wool next to a stone floor tints the floor slightly red).
3. Both are multiplied into the vertex colour **at chunk-build time**, so there is no per-frame cost.

## Caches
| Cache | Key | Value | Invalidated |
|---|---|---|---|
| texture stats | texture id | average + emissive colour | resource reload |
| block-state stats | BlockState | average + emissive colour, bleed hue, light colour | resource reload |
| light-colour field | cell (per 16^3 section int[4096]) | hue + level | live level changes, every 10 s, world change |

## Commands
`/colouredlighting reload | toggle | stats`

## Config - config/coloured-lighting.json
`lightStrength`, `bounceStrength` (0 turns the bleed pass off), `skyLightWeight`, `saturationBoost`,
`minChannel`, `cacheMaxSections`, `defaultEmitterColour`, and `emitters` for per-block overrides:

    "emitters": { "minecraft:torch": "#FF8800" }

## Sodium
With Sodium 0.5.13 (mc 1.20.1) installed, the colour pass runs inside Sodium's terrain mesher:
`SodiumBlockRendererMixin` hooks `BlockRenderer#writeGeometry` and multiplies each vertex's RGB in
Sodium's colour array. Brightness and AO are unaffected. The mixin only loads when Sodium is present.
Build setup: the Sodium API is resolved from Modrinth's Maven at compile time (`modCompileOnly`, not bundled in the released jar).

## Known limits
* Only tested by static type-check against Sodium 0.5.13's API. Not yet run in game.
* Embeddium and Iris are untested.
* Blocks with custom Fabric Rendering API models (not vanilla JSON models) are not tinted.
* Fluids, entities, block entities and items are not tinted.
* Vertex colour can only darken, so coloured light is slightly dimmer than white light of equal level.
