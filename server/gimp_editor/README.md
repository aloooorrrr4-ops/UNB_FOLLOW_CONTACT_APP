# UNB Pro Editor — GIMP bridge

This service is the server-side editing engine for the Android professional editor.

## Architecture

Android sends high-level, allowlisted operations to FastAPI. FastAPI keeps a project/session map and talks to a local GIMP 3 Script-Fu server over its binary TCP protocol. The API never accepts arbitrary Script-Fu from the phone.

Default ports:
- FastAPI staging: 18085
- GIMP Script-Fu: 127.0.0.1:10008 (local only)

## Start

1. Install GIMP 3.x and Python dependencies.
2. Run `./start_gimp_scriptfu.sh`.
3. Run `uvicorn app:app --host 0.0.0.0 --port 18085`.

## Implemented first pass

Project upload, server preview, rotate, flip, brightness, contrast, saturation, desaturate, invert, crop, resize, basic selection operations, flatten, undo/redo using GIMP image snapshots, and export.

The capability endpoint already defines the broader desktop-class tool surface. Additional layer, text, paint, mask, path, channel, resource and GEGL operations are added behind the same allowlisted operation endpoint.
