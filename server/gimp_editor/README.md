# UNB Pro Editor — GIMP bridge

This service is the server-side editing engine for the Android professional editor.

## Architecture

Android sends high-level, allowlisted operations to FastAPI. FastAPI keeps project sessions and talks to a local GIMP 3 Script-Fu server over its binary TCP protocol. The API never accepts arbitrary Script-Fu from the phone.

Default ports:
- FastAPI staging: 18085
- GIMP Script-Fu: 127.0.0.1:10008 (local only)

## Start

1. Install GIMP 3.x and Python dependencies.
2. Run `./start_gimp_scriptfu.sh`.
3. Run `uvicorn app:app --host 0.0.0.0 --port 18085`.

## Connected editor surface

The current bridge exposes real GIMP operations for:

- Project upload, preview, export, undo and redo.
- Image rotate, flip, crop and resize.
- Selections: all, none, invert, rectangle, ellipse, feather, grow and shrink.
- Layers: add, delete, duplicate, rename, visibility, opacity, blend mode, merge and flatten.
- Groups: create group and move layers into a group.
- Masks: create white/black/alpha/selection/grayscale masks, discard or apply.
- Channels: create, remove, visibility and opacity.
- Paths: create, remove, visibility and text-to-path.
- Text: add text, edit text, size and color.
- Painting: paintbrush, pencil, eraser, fill and stroke selection.
- Color operations: brightness, contrast, saturation, desaturate, invert, levels, curves, threshold, posterize, color balance and equalize.
- GEGL filters: gaussian blur, unsharp mask, noise reduction, bloom, emboss, edge, oilify, pixelize, mosaic, motion blur and color temperature.
- GIMP resources: fonts, brushes, gradients, patterns and palettes.

## Android panels

The Android client now has live panels for:
- Layers
- Channels
- Paths
- GIMP resources
- Tool properties
- History

Phone mode keeps these panels collapsible so the canvas remains the main surface. Tablet, landscape and desktop-style layouts keep the inspector visible.

## Next integration batch

The remaining desktop-class surface includes deeper path editing, channel-to-selection workflows, advanced masks, guides/grid/snap, more blend modes, remaining paint engines such as clone/heal/smudge/dodge-burn, gradient editing, additional GEGL filters, richer text controls, full export/save-as UI, keyboard/mouse refinements, and persistent project recovery.
