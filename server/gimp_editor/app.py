#!/usr/bin/env python3
from __future__ import annotations

import os
import re
import json
import time
import uuid
import socket
import struct
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, UploadFile, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

APP_NAME = "UNB Pro Editor GIMP Bridge"
DATA_DIR = Path(os.getenv("UNB_EDITOR_DATA", "/var/lib/unb-pro-editor"))
GIMP_HOST = os.getenv("UNB_GIMP_HOST", "127.0.0.1")
GIMP_PORT = int(os.getenv("UNB_GIMP_PORT", "10008"))
MAX_HISTORY = int(os.getenv("UNB_EDITOR_HISTORY", "20"))

DATA_DIR.mkdir(parents=True, exist_ok=True)

app = FastAPI(title=APP_NAME, version="1.0.0-alpha")


class ScriptFuError(RuntimeError):
    pass


class ScriptFuClient:
    """Thread-safe client for GIMP 3 Script-Fu server protocol."""

    MAGIC = b"G"

    def __init__(self, host: str, port: int, timeout: float = 60.0):
        self.host = host
        self.port = port
        self.timeout = timeout
        self._sock: socket.socket | None = None
        self._lock = threading.RLock()

    def close(self):
        with self._lock:
            if self._sock is not None:
                try:
                    self._sock.close()
                except OSError:
                    pass
                self._sock = None

    def _connect(self):
        if self._sock is not None:
            return
        sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        sock.settimeout(self.timeout)
        self._sock = sock

    def _recv_exact(self, count: int) -> bytes:
        if self._sock is None:
            raise ConnectionError("GIMP socket is not connected")
        data = bytearray()
        while len(data) < count:
            chunk = self._sock.recv(count - len(data))
            if not chunk:
                raise ConnectionError("GIMP Script-Fu server closed the connection")
            data.extend(chunk)
        return bytes(data)

    def call(self, script: str) -> str:
        payload = script.encode("utf-8")
        if len(payload) > 65535:
            raise ValueError("Script-Fu command exceeds protocol limit")

        with self._lock:
            for attempt in range(2):
                try:
                    self._connect()
                    assert self._sock is not None
                    self._sock.sendall(self.MAGIC + struct.pack(">H", len(payload)) + payload)

                    header = self._recv_exact(4)
                    if header[0:1] != self.MAGIC:
                        raise ScriptFuError("Invalid response magic from GIMP")

                    is_error = header[1] != 0
                    body_len = struct.unpack(">H", header[2:4])[0]
                    body = self._recv_exact(body_len).decode("utf-8", errors="replace")

                    if is_error:
                        raise ScriptFuError(body.strip() or "GIMP returned an error")
                    return body.strip()
                except ScriptFuError:
                    raise
                except (OSError, ConnectionError):
                    self.close()
                    if attempt == 1:
                        raise
            raise ConnectionError("Unable to reach GIMP")


sf = ScriptFuClient(GIMP_HOST, GIMP_PORT)


def sf_string(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')


def sf_int(script: str) -> int:
    raw = sf.call(script).strip()
    match = re.search(r"-?\d+", raw)
    if not match:
        raise ScriptFuError(f"Expected integer from GIMP, got: {raw}")
    return int(match.group(0))


def active_drawable(image_id: int) -> int:
    raw = sf.call(f"(car (gimp-image-get-selected-drawables {image_id}))")
    match = re.search(r"#\(\s*(-?\d+)", raw)
    if not match:
        raise ScriptFuError("No selected drawable in project")
    return int(match.group(1))


def parse_vector_ids(raw: str) -> list[int]:
    match = re.search(r"#\(([^)]*)\)", raw or "")
    if not match:
        return []
    return [int(x) for x in re.findall(r"-?\d+", match.group(1))]


def sf_float(script: str) -> float:
    raw = sf.call(script).strip()
    match = re.search(r"-?(?:\d+(?:\.\d*)?|\.\d+)", raw)
    if not match:
        raise ScriptFuError(f"Expected float from GIMP, got: {raw}")
    return float(match.group(0))


def requested_layer(image_id: int, params: dict[str, Any]) -> int:
    layer_id = params.get("layer_id")
    return int(layer_id) if layer_id is not None else active_drawable(image_id)


def scriptfu_color(value: Any) -> str:
    text = str(value or "#000000").strip()
    if re.fullmatch(r"#[0-9a-fA-F]{6}", text):
        return f"'({int(text[1:3], 16)} {int(text[3:5], 16)} {int(text[5:7], 16)})"
    if re.fullmatch(r"[A-Za-z]+", text):
        return f'"{sf_string(text)}"'
    raise HTTPException(400, "اللون يجب أن يكون #RRGGBB أو اسم لون بسيط")


def scriptfu_points(points: Any) -> tuple[str, int]:
    if not isinstance(points, list) or len(points) < 4 or len(points) % 2 != 0:
        raise HTTPException(400, "النقاط يجب أن تكون قائمة x,y وبها نقطتان على الأقل")
    vals = []
    for item in points:
        vals.append(float(item))
    return "(list->vector '(" + " ".join(f"{v:.3f}" for v in vals) + "))", len(vals)


BLEND_MODES = {
    "normal": "LAYER-MODE-NORMAL",
    "multiply": "LAYER-MODE-MULTIPLY",
    "screen": "LAYER-MODE-SCREEN",
    "overlay": "LAYER-MODE-OVERLAY",
    "difference": "LAYER-MODE-DIFFERENCE",
    "darken": "LAYER-MODE-DARKEN-ONLY",
    "lighten": "LAYER-MODE-LIGHTEN-ONLY",
}


GEGL_FILTERS = {
    "gaussian_blur": ("gegl:gaussian-blur", {
        "std-dev-x": ("float", 2.0, 0.0, 500.0),
        "std-dev-y": ("float", 2.0, 0.0, 500.0),
    }),
    "unsharp_mask": ("gegl:unsharp-mask", {
        "std-dev": ("float", 1.0, 0.0, 100.0),
        "scale": ("float", 1.0, 0.0, 20.0),
        "threshold": ("float", 0.0, 0.0, 1.0),
    }),
    "noise_reduction": ("gegl:noise-reduction", {
        "iterations": ("int", 4, 1, 32),
    }),
    "bloom": ("gegl:bloom", {
        "threshold": ("float", 0.8, 0.0, 1.0),
        "softness": ("float", 0.5, 0.0, 1.0),
        "radius": ("float", 10.0, 0.0, 500.0),
        "strength": ("float", 1.0, 0.0, 20.0),
    }),
    "emboss": ("gegl:emboss", {
        "azimuth": ("float", 30.0, 0.0, 360.0),
        "elevation": ("float", 45.0, 0.0, 180.0),
        "depth": ("int", 10, 1, 100),
    }),
    "edge": ("gegl:edge", {
        "amount": ("float", 1.0, 0.0, 10.0),
    }),
    "oilify": ("gegl:oilify", {
        "mask-radius": ("int", 4, 1, 64),
    }),
    "pixelize": ("gegl:pixelize", {
        "size-x": ("int", 10, 1, 1000),
        "size-y": ("int", 10, 1, 1000),
    }),
    "mosaic": ("gegl:mosaic", {
        "tile-size": ("float", 15.0, 1.0, 500.0),
        "tile-height": ("float", 4.0, 0.0, 100.0),
    }),
    "motion_blur": ("gegl:motion-blur-linear", {
        "length": ("float", 10.0, 0.0, 1000.0),
        "angle": ("float", 0.0, -360.0, 360.0),
    }),
    "color_temperature": ("gegl:color-temperature", {
        "intended-temperature": ("float", 6500.0, 1000.0, 12000.0),
        "actual-temperature": ("float", 6500.0, 1000.0, 12000.0),
    }),
}


def layer_list(image_id: int) -> list[dict[str, Any]]:
    ids = parse_vector_ids(sf.call(f"(gimp-image-get-layers {image_id})"))
    layers: list[dict[str, Any]] = []
    for layer_id in ids:
        name = sf.call(f"(car (gimp-item-get-name {layer_id}))").strip().strip('"')
        visible_raw = sf.call(f"(car (gimp-item-get-visible {layer_id}))").lower()
        opacity = sf_float(f"(car (gimp-layer-get-opacity {layer_id}))")
        width = sf_int(f"(car (gimp-item-get-width {layer_id}))")
        height = sf_int(f"(car (gimp-item-get-height {layer_id}))")
        group_raw = sf.call(f"(car (gimp-item-is-group-layer {layer_id}))").lower()
        parent_id = 0
        try:
            parent_id = sf_int(f"(car (gimp-item-get-parent {layer_id}))")
        except Exception:
            parent_id = 0
        layers.append({
            "id": layer_id,
            "name": name,
            "visible": ("#t" in visible_raw or "true" in visible_raw),
            "opacity": opacity,
            "width": width,
            "height": height,
            "is_group": ("#t" in group_raw or "true" in group_raw),
            "parent_id": parent_id,
            "mask": mask_info(layer_id),
        })
    return layers



def resource_list(proc_name: str, filter_text: str = "") -> list[dict[str, Any]]:
    filt = sf_string(filter_text)
    raw = sf.call(f'({proc_name} "{filt}")')
    ids = parse_vector_ids(raw)
    out: list[dict[str, Any]] = []
    for resource_id in ids:
        try:
            name = sf.call(f"(car (gimp-resource-get-name {resource_id}))").strip().strip('"')
        except Exception:
            name = str(resource_id)
        out.append({"id": resource_id, "name": name})
    return out


def channel_list(image_id: int) -> list[dict[str, Any]]:
    ids = parse_vector_ids(sf.call(f"(gimp-image-get-channels {image_id})"))
    out: list[dict[str, Any]] = []
    for channel_id in ids:
        name = sf.call(f"(car (gimp-item-get-name {channel_id}))").strip().strip('"')
        visible_raw = sf.call(f"(car (gimp-item-get-visible {channel_id}))").lower()
        opacity = sf_float(f"(car (gimp-channel-get-opacity {channel_id}))")
        out.append({
            "id": channel_id,
            "name": name,
            "visible": ("#t" in visible_raw or "true" in visible_raw),
            "opacity": opacity,
        })
    return out


def path_list(image_id: int) -> list[dict[str, Any]]:
    ids = parse_vector_ids(sf.call(f"(gimp-image-get-paths {image_id})"))
    out: list[dict[str, Any]] = []
    for path_id in ids:
        name = sf.call(f"(car (gimp-item-get-name {path_id}))").strip().strip('"')
        visible_raw = sf.call(f"(car (gimp-item-get-visible {path_id}))").lower()
        out.append({
            "id": path_id,
            "name": name,
            "visible": ("#t" in visible_raw or "true" in visible_raw),
        })
    return out


def mask_info(layer_id: int) -> dict[str, Any] | None:
    try:
        mask_id = sf_int(f"(car (gimp-layer-get-mask {layer_id}))")
    except Exception:
        return None
    if mask_id <= 0:
        return None
    return {
        "id": mask_id,
        "name": sf.call(f"(car (gimp-item-get-name {mask_id}))").strip().strip('"'),
        "visible": True,
    }


@dataclass
class ProjectState:
    project_id: str
    image_id: int
    folder: Path
    source_name: str
    created_at: float = field(default_factory=time.time)
    undo: list[int] = field(default_factory=list)
    redo: list[int] = field(default_factory=list)
    lock: threading.RLock = field(default_factory=threading.RLock)


projects: dict[str, ProjectState] = {}
projects_lock = threading.RLock()


class OperationRequest(BaseModel):
    operation: str = Field(min_length=1, max_length=80)
    params: dict[str, Any] = Field(default_factory=dict)


def project_or_404(project_id: str) -> ProjectState:
    with projects_lock:
        state = projects.get(project_id)
    if state is None:
        raise HTTPException(404, "المشروع غير موجود أو انتهت جلسة السيرفر")
    return state


def delete_gimp_image(image_id: int):
    try:
        sf.call(f"(gimp-image-delete {int(image_id)})")
    except Exception:
        pass


def clear_stack(stack: list[int]):
    while stack:
        delete_gimp_image(stack.pop())


def push_undo(state: ProjectState):
    snapshot = sf_int(f"(car (gimp-image-duplicate {state.image_id}))")
    state.undo.append(snapshot)
    clear_stack(state.redo)
    while len(state.undo) > MAX_HISTORY:
        delete_gimp_image(state.undo.pop(0))


def preview_path(state: ProjectState) -> Path:
    return state.folder / "preview.png"


def render_preview(state: ProjectState) -> str:
    """Export through a duplicate so preview generation never changes project metadata."""
    out = preview_path(state)
    duplicate = sf_int(f"(car (gimp-image-duplicate {state.image_id}))")
    try:
        safe = sf_string(str(out))
        sf.call(f'(gimp-file-save RUN-NONINTERACTIVE {duplicate} "{safe}")')
    finally:
        delete_gimp_image(duplicate)
    return f"/api/editor/projects/{state.project_id}/preview?v={int(time.time() * 1000)}"


def operation_catalog() -> dict[str, list[str]]:
    return {
        "file": ["open", "save", "export"],
        "edit": ["undo", "redo"],
        "selection": ["select_all", "select_none", "invert_selection", "select_rectangle", "select_ellipse",
                      "feather", "grow", "shrink"],
        "image": ["resize", "crop", "rotate", "flip_horizontal", "flip_vertical"],
        "layers": ["add_layer", "delete_layer", "duplicate_layer", "rename_layer", "visibility",
                   "opacity", "blend_mode", "merge_visible", "flatten"],
        "colors": ["brightness", "contrast", "hue_saturation", "levels", "curves", "color_balance",
                   "threshold", "posterize", "desaturate", "invert"],
        "paint": ["pencil", "paintbrush", "airbrush", "eraser", "fill", "stroke_selection"],
        "text": ["add_text", "edit_text", "font", "font_size", "text_color"],
        "filters": ["gaussian_blur", "unsharp_mask", "noise_reduction", "bloom", "emboss",
                    "edge", "oilify", "pixelize", "mosaic", "motion_blur", "drop_shadow",
                    "color_temperature", "gegl"],
        "resources": ["brushes", "fonts", "gradients", "patterns", "palettes"],
        "advanced": ["channels", "paths", "masks", "guides", "groups", "resources"]
    }


@app.get("/health")
def health():
    connected = False
    detail = ""
    try:
        result = sf.call("(+ 2 3)")
        connected = "5" in result
        detail = result
    except Exception as exc:
        detail = str(exc)

    return {
        "ok": True,
        "service": APP_NAME,
        "gimp_scriptfu": connected,
        "gimp_host": GIMP_HOST,
        "gimp_port": GIMP_PORT,
        "detail": detail,
        "projects": len(projects),
    }


@app.get("/api/editor/capabilities")
def capabilities():
    return {
        "ok": True,
        "engine": "GIMP 3 Script-Fu/PDB",
        "catalog": operation_catalog(),
        "implemented_now": [
            "rotate", "flip_horizontal", "flip_vertical",
            "brightness", "contrast", "saturation",
            "desaturate", "invert", "crop", "resize",
            "select_all", "select_none", "invert_selection", "select_rectangle", "select_ellipse",
            "feather", "grow", "shrink", "flatten",
            "add_layer", "delete_layer", "duplicate_layer", "rename_layer", "visibility",
            "opacity", "blend_mode", "merge_visible",
            "add_text", "edit_text", "font_size", "text_color",
            "paintbrush", "pencil", "eraser", "fill", "stroke_selection",
            "gaussian_blur", "unsharp_mask", "noise_reduction", "bloom", "emboss",
            "edge", "oilify", "pixelize", "mosaic", "motion_blur", "color_temperature",
            "add_group", "move_to_group", "add_mask", "remove_mask", "apply_mask",
            "add_channel", "delete_channel", "channel_visibility", "channel_opacity",
            "add_path", "delete_path", "path_visibility", "text_to_path",
            "levels", "curves", "threshold", "posterize", "color_balance", "equalize"
        ],
        "architecture": "allowlisted PDB bridge; no arbitrary remote script execution",
    }


@app.post("/api/editor/projects")
async def create_project(image: UploadFile = File(...)):
    data = await image.read()
    if not data:
        raise HTTPException(400, "الملف فارغ")
    if len(data) > 80 * 1024 * 1024:
        raise HTTPException(413, "حجم الصورة أكبر من الحد الحالي 80MB")

    project_id = uuid.uuid4().hex
    folder = DATA_DIR / project_id
    folder.mkdir(parents=True, exist_ok=True)

    ext = Path(image.filename or "image.png").suffix.lower()
    if ext not in {".png", ".jpg", ".jpeg", ".webp", ".tif", ".tiff", ".bmp", ".gif"}:
        ext = ".png"

    source = folder / f"source{ext}"
    source.write_bytes(data)

    try:
        image_id = sf_int(
            f'(car (gimp-file-load RUN-NONINTERACTIVE "{sf_string(str(source))}"))'
        )
        sf.call(f"(gimp-image-undo-enable {image_id})")
    except Exception as exc:
        raise HTTPException(503, f"تعذر فتح الصورة في GIMP: {exc}")

    state = ProjectState(
        project_id=project_id,
        image_id=image_id,
        folder=folder,
        source_name=image.filename or source.name,
    )

    with projects_lock:
        projects[project_id] = state

    try:
        preview_url = render_preview(state)
    except Exception as exc:
        delete_gimp_image(image_id)
        with projects_lock:
            projects.pop(project_id, None)
        raise HTTPException(500, f"تم فتح الصورة لكن فشل إنشاء المعاينة: {exc}")

    return {
        "ok": True,
        "project_id": project_id,
        "preview_url": preview_url,
        "source_name": state.source_name,
    }


@app.get("/api/editor/projects/{project_id}")
def get_project(project_id: str):
    state = project_or_404(project_id)
    with state.lock:
        width = sf_int(f"(car (gimp-image-get-width {state.image_id}))")
        height = sf_int(f"(car (gimp-image-get-height {state.image_id}))")
        layers = layer_list(state.image_id)
        return {
            "ok": True,
            "project_id": project_id,
            "image_id": state.image_id,
            "width": width,
            "height": height,
            "layers": layers,
            "undo_depth": len(state.undo),
            "redo_depth": len(state.redo),
        }


@app.get("/api/editor/projects/{project_id}/layers")
def get_layers(project_id: str):
    state = project_or_404(project_id)
    with state.lock:
        return {"ok": True, "layers": layer_list(state.image_id)}


@app.get("/api/editor/projects/{project_id}/channels")
def get_channels(project_id: str):
    state = project_or_404(project_id)
    with state.lock:
        return {"ok": True, "channels": channel_list(state.image_id)}


@app.get("/api/editor/projects/{project_id}/paths")
def get_paths(project_id: str):
    state = project_or_404(project_id)
    with state.lock:
        return {"ok": True, "paths": path_list(state.image_id)}


@app.get("/api/editor/resources/{kind}")
def get_resources(kind: str, q: str = ""):
    proc = {
        "fonts": "gimp-fonts-get-list",
        "brushes": "gimp-brushes-get-list",
        "gradients": "gimp-gradients-get-list",
        "patterns": "gimp-patterns-get-list",
        "palettes": "gimp-palettes-get-list",
    }.get(kind.lower())
    if not proc:
        raise HTTPException(404, "نوع المورد غير معروف")
    try:
        return {"ok": True, "kind": kind.lower(), "items": resource_list(proc, q)}
    except Exception as exc:
        raise HTTPException(500, f"تعذر قراءة موارد GIMP: {exc}")


@app.get("/api/editor/projects/{project_id}/preview")
def get_preview(project_id: str):
    state = project_or_404(project_id)
    path = preview_path(state)
    if not path.exists():
        with state.lock:
            render_preview(state)
    return FileResponse(path, media_type="image/png",
                        headers={"Cache-Control": "no-store, max-age=0"})


def apply_operation_to_gimp(state: ProjectState, op: str, p: dict[str, Any]):
    img = state.image_id

    if op == "rotate":
        deg = int(p.get("degrees", 90))
        rotation = {90: 0, 180: 1, 270: 2, -90: 2, -180: 1, -270: 0}.get(deg)
        if rotation is None:
            raise HTTPException(400, "الدوران المدعوم حاليًا 90/180/270 درجة")
        sf.call(f"(gimp-image-rotate {img} {rotation})")

    elif op == "flip_horizontal":
        sf.call(f"(gimp-image-flip {img} 0)")

    elif op == "flip_vertical":
        sf.call(f"(gimp-image-flip {img} 1)")

    elif op in {"brightness", "contrast"}:
        d = active_drawable(img)
        value = max(-100, min(100, float(p.get("value", 0)))) / 100.0
        brightness = value if op == "brightness" else 0.0
        contrast = value if op == "contrast" else 0.0
        sf.call(f"(gimp-drawable-brightness-contrast {d} {brightness:.5f} {contrast:.5f})")

    elif op in {"saturation", "hue_saturation"}:
        d = active_drawable(img)
        sat = max(-100, min(100, float(p.get("value", p.get("saturation", 0)))))
        hue = max(-180, min(180, float(p.get("hue", 0))))
        light = max(-100, min(100, float(p.get("lightness", 0))))
        sf.call(
            f"(gimp-drawable-hue-saturation {d} HUE-RANGE-ALL "
            f"{hue:.4f} {light:.4f} {sat:.4f} 0)"
        )

    elif op == "desaturate":
        d = active_drawable(img)
        sf.call(f"(gimp-drawable-desaturate {d} DESATURATE-LUMINOSITY)")

    elif op == "invert":
        d = active_drawable(img)
        sf.call(f"(gimp-drawable-invert {d} FALSE)")

    elif op == "crop":
        w = int(p.get("width", 0))
        h = int(p.get("height", 0))
        x = int(p.get("x", 0))
        y = int(p.get("y", 0))
        if w <= 0 or h <= 0:
            raise HTTPException(400, "أبعاد القص غير صحيحة")
        sf.call(f"(gimp-image-crop {img} {w} {h} {x} {y})")

    elif op == "resize":
        w = int(p.get("width", 0))
        h = int(p.get("height", 0))
        if w <= 0 or h <= 0:
            raise HTTPException(400, "أبعاد الصورة غير صحيحة")
        sf.call(f"(gimp-image-scale {img} {w} {h})")

    elif op == "select_all":
        sf.call(f"(gimp-selection-all {img})")

    elif op == "select_none":
        sf.call(f"(gimp-selection-none {img})")

    elif op == "invert_selection":
        sf.call(f"(gimp-selection-invert {img})")

    elif op == "feather":
        radius = max(0.0, float(p.get("radius", 5)))
        sf.call(f"(gimp-selection-feather {img} {radius:.4f})")

    elif op == "grow":
        steps = max(0, int(p.get("steps", 1)))
        sf.call(f"(gimp-selection-grow {img} {steps})")

    elif op == "shrink":
        steps = max(0, int(p.get("steps", 1)))
        sf.call(f"(gimp-selection-shrink {img} {steps})")

    elif op == "add_group":
        name = sf_string(str(p.get("name", "Group")))
        group_id = sf_int(f'(car (gimp-group-layer-new {img} "{name}"))')
        sf.call(f"(gimp-image-insert-layer {img} {group_id} 0 -1)")

    elif op == "move_to_group":
        layer_id = requested_layer(img, p)
        group_id = int(p.get("group_id", 0))
        position = int(p.get("position", -1))
        if group_id <= 0:
            raise HTTPException(400, "group_id مطلوب")
        sf.call(f"(gimp-image-reorder-item {img} {layer_id} {group_id} {position})")

    elif op == "add_mask":
        layer_id = requested_layer(img, p)
        mask_type_map = {
            "white": "ADD-WHITE-MASK",
            "black": "ADD-BLACK-MASK",
            "alpha": "ADD-ALPHA-MASK",
            "selection": "ADD-SELECTION-MASK",
            "grayscale": "ADD-COPY-MASK",
        }
        mask_type = mask_type_map.get(str(p.get("type", "white")).lower())
        if not mask_type:
            raise HTTPException(400, "نوع القناع غير مدعوم")
        mask_id = sf_int(f"(car (gimp-layer-create-mask {layer_id} {mask_type}))")
        sf.call(f"(gimp-layer-add-mask {layer_id} {mask_id})")

    elif op == "remove_mask":
        layer_id = requested_layer(img, p)
        sf.call(f"(gimp-layer-remove-mask {layer_id} MASK-DISCARD)")

    elif op == "apply_mask":
        layer_id = requested_layer(img, p)
        sf.call(f"(gimp-layer-remove-mask {layer_id} MASK-APPLY)")

    elif op == "add_channel":
        name = sf_string(str(p.get("name", "Channel")))
        width = sf_int(f"(car (gimp-image-get-width {img}))")
        height = sf_int(f"(car (gimp-image-get-height {img}))")
        opacity = max(0.0, min(100.0, float(p.get("opacity", 100))))
        color = scriptfu_color(p.get("color", "#000000"))
        channel_id = sf_int(
            f'(car (gimp-channel-new {img} "{name}" {width} {height} {opacity:.3f} {color}))'
        )
        sf.call(f"(gimp-image-insert-channel {img} {channel_id} 0 -1)")

    elif op == "delete_channel":
        channel_id = int(p.get("channel_id", 0))
        if channel_id <= 0:
            raise HTTPException(400, "channel_id مطلوب")
        sf.call(f"(gimp-image-remove-channel {img} {channel_id})")

    elif op == "channel_visibility":
        channel_id = int(p.get("channel_id", 0))
        visible = "TRUE" if bool(p.get("visible", True)) else "FALSE"
        sf.call(f"(gimp-item-set-visible {channel_id} {visible})")

    elif op == "channel_opacity":
        channel_id = int(p.get("channel_id", 0))
        opacity = max(0.0, min(100.0, float(p.get("opacity", 100))))
        sf.call(f"(gimp-channel-set-opacity {channel_id} {opacity:.3f})")

    elif op == "add_path":
        name = sf_string(str(p.get("name", "Path")))
        path_id = sf_int(f'(car (gimp-path-new {img} "{name}"))')
        sf.call(f"(gimp-image-insert-path {img} {path_id} 0 -1)")

    elif op == "delete_path":
        path_id = int(p.get("path_id", 0))
        if path_id <= 0:
            raise HTTPException(400, "path_id مطلوب")
        sf.call(f"(gimp-image-remove-path {img} {path_id})")

    elif op == "path_visibility":
        path_id = int(p.get("path_id", 0))
        visible = "TRUE" if bool(p.get("visible", True)) else "FALSE"
        sf.call(f"(gimp-item-set-visible {path_id} {visible})")

    elif op == "text_to_path":
        layer_id = requested_layer(img, p)
        path_id = sf_int(f"(car (gimp-path-new-from-text-layer {img} {layer_id}))")
        sf.call(f"(gimp-image-insert-path {img} {path_id} 0 -1)")

    elif op == "levels":
        layer_id = requested_layer(img, p)
        low_in = max(0.0, min(1.0, float(p.get("low_input", 0.0))))
        high_in = max(low_in, min(1.0, float(p.get("high_input", 1.0))))
        gamma = max(0.1, min(10.0, float(p.get("gamma", 1.0))))
        low_out = max(0.0, min(1.0, float(p.get("low_output", 0.0))))
        high_out = max(low_out, min(1.0, float(p.get("high_output", 1.0))))
        sf.call(
            f"(gimp-drawable-levels {layer_id} HISTOGRAM-VALUE "
            f"{low_in:.5f} {high_in:.5f} TRUE {gamma:.5f} "
            f"{low_out:.5f} {high_out:.5f} TRUE)"
        )

    elif op == "curves":
        layer_id = requested_layer(img, p)
        pts = p.get("points", [0.0, 0.0, 1.0, 1.0])
        vector, count = scriptfu_points(pts)
        sf.call(f"(gimp-drawable-curves-spline {layer_id} HISTOGRAM-VALUE {count} {vector})")

    elif op == "threshold":
        layer_id = requested_layer(img, p)
        low = max(0.0, min(1.0, float(p.get("low", 0.5))))
        high = max(low, min(1.0, float(p.get("high", 1.0))))
        sf.call(f"(gimp-drawable-threshold {layer_id} HISTOGRAM-VALUE {low:.5f} {high:.5f})")

    elif op == "posterize":
        layer_id = requested_layer(img, p)
        levels = max(2, min(256, int(p.get("levels", 4))))
        sf.call(f"(gimp-drawable-posterize {layer_id} {levels})")

    elif op == "color_balance":
        layer_id = requested_layer(img, p)
        range_map = {
            "shadows": "TRANSFER-SHADOWS",
            "midtones": "TRANSFER-MIDTONES",
            "highlights": "TRANSFER-HIGHLIGHTS",
        }
        tone = range_map.get(str(p.get("range", "midtones")).lower(), "TRANSFER-MIDTONES")
        cr = max(-100.0, min(100.0, float(p.get("cyan_red", 0))))
        mg = max(-100.0, min(100.0, float(p.get("magenta_green", 0))))
        yb = max(-100.0, min(100.0, float(p.get("yellow_blue", 0))))
        preserve = "TRUE" if bool(p.get("preserve_luminosity", True)) else "FALSE"
        sf.call(
            f"(gimp-drawable-color-balance {layer_id} {tone} {preserve} "
            f"{cr:.3f} {mg:.3f} {yb:.3f})"
        )

    elif op == "equalize":
        layer_id = requested_layer(img, p)
        sf.call(f"(gimp-drawable-equalize {layer_id} FALSE)")

    elif op == "select_rectangle":
        x = int(p.get("x", 0))
        y = int(p.get("y", 0))
        w = int(p.get("width", 0))
        h = int(p.get("height", 0))
        if w <= 0 or h <= 0:
            raise HTTPException(400, "أبعاد التحديد غير صحيحة")
        sf.call(f"(gimp-image-select-rectangle {img} CHANNEL-OP-REPLACE {x} {y} {w} {h})")

    elif op == "select_ellipse":
        x = int(p.get("x", 0))
        y = int(p.get("y", 0))
        w = int(p.get("width", 0))
        h = int(p.get("height", 0))
        if w <= 0 or h <= 0:
            raise HTTPException(400, "أبعاد التحديد غير صحيحة")
        sf.call(f"(gimp-image-select-ellipse {img} CHANNEL-OP-REPLACE {x} {y} {w} {h})")

    elif op == "add_layer":
        name = sf_string(str(p.get("name", "Layer")))
        width = int(p.get("width") or sf_int(f"(car (gimp-image-get-width {img}))"))
        height = int(p.get("height") or sf_int(f"(car (gimp-image-get-height {img}))"))
        opacity = max(0.0, min(100.0, float(p.get("opacity", 100))))
        mode_key = str(p.get("blend_mode", "normal")).lower()
        mode = BLEND_MODES.get(mode_key)
        if not mode:
            raise HTTPException(400, "Blend mode غير مدعوم في هذه الدفعة")
        layer_id = sf_int(
            f'(car (gimp-layer-new {img} "{name}" {width} {height} RGBA-IMAGE {opacity:.3f} {mode}))'
        )
        sf.call(f"(gimp-image-insert-layer {img} {layer_id} 0 -1)")
        if bool(p.get("fill_transparent", True)):
            sf.call(f"(gimp-drawable-edit-fill {layer_id} FILL-TRANSPARENT)")

    elif op == "delete_layer":
        layer_id = requested_layer(img, p)
        sf.call(f"(gimp-image-remove-layer {img} {layer_id})")

    elif op == "duplicate_layer":
        layer_id = requested_layer(img, p)
        new_layer = sf_int(f"(car (gimp-layer-copy {layer_id} TRUE))")
        sf.call(f"(gimp-image-insert-layer {img} {new_layer} 0 -1)")

    elif op == "rename_layer":
        layer_id = requested_layer(img, p)
        name = sf_string(str(p.get("name", "Layer")))
        sf.call(f'(gimp-item-set-name {layer_id} "{name}")')

    elif op == "visibility":
        layer_id = requested_layer(img, p)
        visible = "TRUE" if bool(p.get("visible", True)) else "FALSE"
        sf.call(f"(gimp-item-set-visible {layer_id} {visible})")

    elif op == "opacity":
        layer_id = requested_layer(img, p)
        opacity = max(0.0, min(100.0, float(p.get("value", p.get("opacity", 100)))))
        sf.call(f"(gimp-layer-set-opacity {layer_id} {opacity:.3f})")

    elif op == "blend_mode":
        layer_id = requested_layer(img, p)
        mode_key = str(p.get("mode", "normal")).lower()
        mode = BLEND_MODES.get(mode_key)
        if not mode:
            raise HTTPException(400, "Blend mode غير مدعوم في هذه الدفعة")
        sf.call(f"(gimp-layer-set-mode {layer_id} {mode})")

    elif op == "merge_visible":
        sf.call(f"(gimp-image-merge-visible-layers {img} CLIP-TO-IMAGE)")

    elif op == "add_text":
        text = sf_string(str(p.get("text", "")))
        if not text:
            raise HTTPException(400, "النص فارغ")
        x = int(p.get("x", 0))
        y = int(p.get("y", 0))
        size = max(4.0, min(1000.0, float(p.get("size", 32))))
        font_query = sf_string(str(p.get("font", "Sans")))
        font_id = sf_int(f'(vector-ref (car (gimp-fonts-get-list "{font_query}")) 0)')
        layer_id = sf_int(
            f'(car (gimp-text-font {img} 0 {x} {y} "{text}" {size:.3f} {font_id}))'
        )
        if "color" in p:
            color = scriptfu_color(p.get("color"))
            sf.call(f"(gimp-text-layer-set-color {layer_id} {color})")

    elif op == "edit_text":
        layer_id = requested_layer(img, p)
        text = sf_string(str(p.get("text", "")))
        sf.call(f'(gimp-text-layer-set-text {layer_id} "{text}")')

    elif op == "font_size":
        layer_id = requested_layer(img, p)
        size = max(4.0, min(1000.0, float(p.get("size", 32))))
        sf.call(f"(gimp-text-layer-set-font-size {layer_id} {size:.3f} UNIT-PIXEL)")

    elif op == "text_color":
        layer_id = requested_layer(img, p)
        color = scriptfu_color(p.get("color"))
        sf.call(f"(gimp-text-layer-set-color {layer_id} {color})")

    elif op in {"paintbrush", "pencil", "eraser"}:
        layer_id = requested_layer(img, p)
        vector, count = scriptfu_points(p.get("points"))
        size = max(1.0, min(2000.0, float(p.get("size", 20))))
        opacity = max(0.0, min(100.0, float(p.get("opacity", 100))))
        sf.call(f"(gimp-context-set-brush-size {size:.3f})")
        sf.call(f"(gimp-context-set-opacity {opacity:.3f})")
        if "color" in p:
            sf.call(f"(gimp-context-set-foreground {scriptfu_color(p.get('color'))})")
        if op == "paintbrush":
            sf.call(f"(gimp-paintbrush {layer_id} 0 {count} {vector} 0 0)")
        elif op == "pencil":
            sf.call(f"(gimp-pencil {layer_id} {count} {vector})")
        else:
            sf.call(f"(gimp-eraser {layer_id} {count} {vector} HARDNESS-HARD 0)")

    elif op == "fill":
        layer_id = requested_layer(img, p)
        sf.call(f"(gimp-context-set-foreground {scriptfu_color(p.get('color', '#000000'))})")
        sf.call(f"(gimp-drawable-edit-fill {layer_id} FILL-FOREGROUND)")

    elif op == "stroke_selection":
        layer_id = requested_layer(img, p)
        width = max(0.1, min(1000.0, float(p.get("width", 2))))
        sf.call(f"(gimp-context-set-line-width {width:.3f})")
        sf.call("(gimp-context-set-stroke-method STROKE-LINE)")
        if "color" in p:
            sf.call(f"(gimp-context-set-foreground {scriptfu_color(p.get('color'))})")
        sf.call(f"(gimp-drawable-edit-stroke-selection {layer_id})")

    elif op in GEGL_FILTERS:
        layer_id = requested_layer(img, p)
        gegl_name, schema = GEGL_FILTERS[op]
        filter_id = sf_int(f'(car (gimp-drawable-filter-new {layer_id} "{gegl_name}" ""))')
        config_parts = []
        for key, spec in schema.items():
            kind, default, low, high = spec
            raw = p.get(key, default)
            value = int(raw) if kind == "int" else float(raw)
            value = max(low, min(high, value))
            config_parts.append(f'"{key}"')
            config_parts.append(str(value))
        sf.call(f"(gimp-drawable-filter-configure {filter_id} {' '.join(config_parts)})")

    elif op == "flatten":
        sf.call(f"(gimp-image-flatten {img})")

    else:
        raise HTTPException(400, f"الأداة '{op}' موجودة في الخطة لكنها لم تربط بعد بمحرك PDB")


@app.post("/api/editor/projects/{project_id}/operations")
def operation(project_id: str, request: OperationRequest):
    state = project_or_404(project_id)
    op = request.operation.strip().lower()

    with state.lock:
        try:
            push_undo(state)
            apply_operation_to_gimp(state, op, request.params)
            preview_url = render_preview(state)
            return {
                "ok": True,
                "project_id": project_id,
                "operation": op,
                "preview_url": preview_url,
                "undo_depth": len(state.undo),
                "redo_depth": len(state.redo),
                "engine": "GIMP-PDB",
            }
        except HTTPException:
            if state.undo:
                failed_current = state.image_id
                state.image_id = state.undo.pop()
                delete_gimp_image(failed_current)
            raise
        except Exception as exc:
            if state.undo:
                failed_current = state.image_id
                state.image_id = state.undo.pop()
                delete_gimp_image(failed_current)
            raise HTTPException(500, f"GIMP operation failed: {exc}")


@app.post("/api/editor/projects/{project_id}/undo")
def undo(project_id: str):
    state = project_or_404(project_id)
    with state.lock:
        if not state.undo:
            raise HTTPException(409, "لا توجد خطوة أقدم")
        current_copy = sf_int(f"(car (gimp-image-duplicate {state.image_id}))")
        state.redo.append(current_copy)
        old_current = state.image_id
        state.image_id = state.undo.pop()
        delete_gimp_image(old_current)
        return {"ok": True, "preview_url": render_preview(state)}


@app.post("/api/editor/projects/{project_id}/redo")
def redo(project_id: str):
    state = project_or_404(project_id)
    with state.lock:
        if not state.redo:
            raise HTTPException(409, "لا توجد خطوة لإعادتها")
        current_copy = sf_int(f"(car (gimp-image-duplicate {state.image_id}))")
        state.undo.append(current_copy)
        old_current = state.image_id
        state.image_id = state.redo.pop()
        delete_gimp_image(old_current)
        return {"ok": True, "preview_url": render_preview(state)}


@app.get("/api/editor/projects/{project_id}/export")
def export(project_id: str, format: str = "png"):
    state = project_or_404(project_id)
    fmt = format.lower().strip()
    if fmt not in {"png", "jpg", "jpeg", "webp", "tiff", "xcf"}:
        raise HTTPException(400, "صيغة التصدير غير مدعومة")

    suffix = "jpg" if fmt == "jpeg" else fmt
    out = state.folder / f"export.{suffix}"

    with state.lock:
        try:
            sf.call(
                f'(gimp-file-save RUN-NONINTERACTIVE {state.image_id} "{sf_string(str(out))}")'
            )
        except Exception as exc:
            raise HTTPException(500, f"فشل التصدير من GIMP: {exc}")

    media = {
        "png": "image/png",
        "jpg": "image/jpeg",
        "jpeg": "image/jpeg",
        "webp": "image/webp",
        "tiff": "image/tiff",
        "xcf": "application/octet-stream",
    }[fmt]
    return FileResponse(out, media_type=media, filename=out.name)


@app.delete("/api/editor/projects/{project_id}")
def close_project(project_id: str):
    with projects_lock:
        state = projects.pop(project_id, None)
    if state is None:
        raise HTTPException(404, "المشروع غير موجود")

    with state.lock:
        delete_gimp_image(state.image_id)
        clear_stack(state.undo)
        clear_stack(state.redo)

    return {"ok": True}
