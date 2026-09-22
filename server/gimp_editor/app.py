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
import shutil
import hmac
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, UploadFile, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field

APP_NAME = "UNB Pro Editor GIMP Bridge"
DATA_DIR = Path(os.getenv("UNB_EDITOR_DATA", "/var/lib/unb-pro-editor"))
GIMP_HOST = os.getenv("UNB_GIMP_HOST", "127.0.0.1")
GIMP_PORT = int(os.getenv("UNB_GIMP_PORT", "10008"))
MAX_HISTORY = int(os.getenv("UNB_EDITOR_HISTORY", "20"))
MAX_UPLOAD_BYTES = int(os.getenv("UNB_EDITOR_MAX_UPLOAD", str(80 * 1024 * 1024)))
PROJECT_TTL_SECONDS = int(os.getenv("UNB_EDITOR_PROJECT_TTL", "3600"))
CLEANUP_INTERVAL_SECONDS = int(os.getenv("UNB_EDITOR_CLEANUP_INTERVAL", "300"))
EDITOR_API_KEY = os.getenv("UNB_EDITOR_API_KEY", "").strip()

DATA_DIR.mkdir(parents=True, exist_ok=True)

app = FastAPI(title=APP_NAME, version="1.0.1-alpha")
engine_lock = threading.RLock()


@app.middleware("http")
async def editor_auth(request: Request, call_next):
    if request.url.path.startswith("/api/editor/"):
        if not EDITOR_API_KEY:
            return JSONResponse(
                status_code=503,
                content={"detail": "UNB_EDITOR_API_KEY is not configured on the server"},
            )
        supplied = request.headers.get("X-UNB-Editor-Key", "")
        if not supplied or not hmac.compare_digest(supplied, EDITOR_API_KEY):
            return JSONResponse(status_code=401, content={"detail": "Unauthorized"})
    return await call_next(request)


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

        # Never retry an in-flight Script-Fu command automatically. If the
        # socket dies after GIMP executed a mutation but before the response
        # arrives, retrying could apply the edit twice.
        with self._lock:
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
            except Exception:
                self.close()
                raise


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
    last_accessed: float = field(default_factory=time.time)
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
    state.last_accessed = time.time()
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
    while len(state.undo) > MAX_HISTORY:
        delete_gimp_image(state.undo.pop(0))
    return snapshot


def destroy_project_state(state: ProjectState):
    with state.lock:
        with engine_lock:
            delete_gimp_image(state.image_id)
            clear_stack(state.undo)
            clear_stack(state.redo)
    shutil.rmtree(state.folder, ignore_errors=True)


def cleanup_expired_projects():
    while True:
        time.sleep(max(30, CLEANUP_INTERVAL_SECONDS))
        cutoff = time.time() - max(300, PROJECT_TTL_SECONDS)
        expired: list[ProjectState] = []
        with projects_lock:
            for project_id, state in list(projects.items()):
                if state.last_accessed < cutoff:
                    expired.append(projects.pop(project_id))
        for state in expired:
            destroy_project_state(state)


threading.Thread(
    target=cleanup_expired_projects,
    name="unb-project-cleanup",
    daemon=True,
).start()


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
                      "select_polygon", "select_contiguous", "select_color", "select_item",
                      "border", "sharpen_selection", "translate_selection",
                      "feather", "grow", "shrink"],
        "image": ["resize", "crop", "rotate", "flip_horizontal", "flip_vertical",
                  "translate_layer", "scale_layer", "rotate_layer", "perspective_layer", "transform_2d"],
        "layers": ["add_layer", "delete_layer", "duplicate_layer", "rename_layer", "visibility",
                   "opacity", "blend_mode", "merge_visible", "flatten"],
        "colors": ["brightness", "contrast", "hue_saturation", "levels", "curves", "color_balance",
                   "threshold", "posterize", "desaturate", "invert"],
        "paint": ["pencil", "paintbrush", "airbrush", "eraser", "fill", "stroke_selection",
                  "clone", "heal", "smudge", "dodge_burn", "gradient"],
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
        "auth_required": True,
        "api_key_configured": bool(EDITOR_API_KEY),
        "project_ttl_seconds": PROJECT_TTL_SECONDS,
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
            "select_polygon", "select_contiguous", "select_color", "select_item",
            "border", "sharpen_selection", "translate_selection",
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
            "levels", "curves", "threshold", "posterize", "color_balance", "equalize",
            "clone", "heal", "smudge", "dodge_burn", "gradient",
            "translate_layer", "scale_layer", "rotate_layer", "perspective_layer", "transform_2d"
        ],
        "architecture": "allowlisted PDB bridge; no arbitrary remote script execution",
    }


@app.post("/api/editor/projects")
def create_project(image: UploadFile = File(...)):
    project_id = uuid.uuid4().hex
    folder = DATA_DIR / project_id
    folder.mkdir(parents=True, exist_ok=True)

    ext = Path(image.filename or "image.png").suffix.lower()
    if ext not in {".png", ".jpg", ".jpeg", ".webp", ".tif", ".tiff", ".bmp", ".gif"}:
        ext = ".png"
    source = folder / f"source{ext}"

    total = 0
    try:
        with source.open("wb") as out:
            while True:
                chunk = image.file.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > MAX_UPLOAD_BYTES:
                    raise HTTPException(
                        413,
                        f"حجم الصورة أكبر من الحد الحالي {MAX_UPLOAD_BYTES // (1024 * 1024)}MB",
                    )
                out.write(chunk)
    except HTTPException:
        shutil.rmtree(folder, ignore_errors=True)
        raise
    except Exception as exc:
        shutil.rmtree(folder, ignore_errors=True)
        raise HTTPException(400, f"تعذر قراءة الملف: {exc}")

    if total == 0:
        shutil.rmtree(folder, ignore_errors=True)
        raise HTTPException(400, "الملف فارغ")

    try:
        with engine_lock:
            image_id = sf_int(
                f'(car (gimp-file-load RUN-NONINTERACTIVE "{sf_string(str(source))}"))'
            )
            sf.call(f"(gimp-image-undo-enable {image_id})")
    except Exception as exc:
        shutil.rmtree(folder, ignore_errors=True)
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
        with engine_lock:
            preview_url = render_preview(state)
    except Exception as exc:
        with projects_lock:
            projects.pop(project_id, None)
        destroy_project_state(state)
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
    with engine_lock, state.lock:
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
    with engine_lock, state.lock:
        return {"ok": True, "layers": layer_list(state.image_id)}


@app.get("/api/editor/projects/{project_id}/channels")
def get_channels(project_id: str):
    state = project_or_404(project_id)
    with engine_lock, state.lock:
        return {"ok": True, "channels": channel_list(state.image_id)}


@app.get("/api/editor/projects/{project_id}/paths")
def get_paths(project_id: str):
    state = project_or_404(project_id)
    with engine_lock, state.lock:
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
        with engine_lock:
            return {"ok": True, "kind": kind.lower(), "items": resource_list(proc, q)}
    except Exception as exc:
        raise HTTPException(500, f"تعذر قراءة موارد GIMP: {exc}")


@app.get("/api/editor/projects/{project_id}/preview")
def get_preview(project_id: str):
    state = project_or_404(project_id)
    path = preview_path(state)
    if not path.exists():
        with engine_lock, state.lock:
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

    elif op == "translate_layer":
        layer_id = requested_layer(img, p)
        x = float(p.get("x", 0))
        y = float(p.get("y", 0))
        sf.call(f"(gimp-item-transform-translate {layer_id} {x:.5f} {y:.5f})")

    elif op == "scale_layer":
        layer_id = requested_layer(img, p)
        x0 = float(p.get("x0", 0))
        y0 = float(p.get("y0", 0))
        x1 = float(p.get("x1", 0))
        y1 = float(p.get("y1", 0))
        if x1 <= x0 or y1 <= y0:
            raise HTTPException(400, "إحداثيات التحجيم غير صحيحة")
        sf.call(f"(gimp-item-transform-scale {layer_id} {x0:.5f} {y0:.5f} {x1:.5f} {y1:.5f})")

    elif op == "rotate_layer":
        layer_id = requested_layer(img, p)
        angle = float(p.get("radians", 0))
        auto_center = "TRUE" if bool(p.get("auto_center", True)) else "FALSE"
        cx = float(p.get("center_x", 0))
        cy = float(p.get("center_y", 0))
        sf.call(
            f"(gimp-item-transform-rotate {layer_id} {angle:.8f} "
            f"{auto_center} {cx:.5f} {cy:.5f})"
        )

    elif op == "perspective_layer":
        layer_id = requested_layer(img, p)
        vals = [float(p.get(k, 0)) for k in ("x0","y0","x1","y1","x2","y2","x3","y3")]
        sf.call(
            "(gimp-item-transform-perspective "
            + str(layer_id) + " " + " ".join(f"{v:.5f}" for v in vals) + ")"
        )

    elif op == "transform_2d":
        layer_id = requested_layer(img, p)
        sx = float(p.get("source_x", 0))
        sy = float(p.get("source_y", 0))
        scalex = float(p.get("scale_x", 1))
        scaley = float(p.get("scale_y", 1))
        angle = float(p.get("radians", 0))
        dx = float(p.get("dest_x", sx))
        dy = float(p.get("dest_y", sy))
        sf.call(
            f"(gimp-item-transform-2d {layer_id} {sx:.5f} {sy:.5f} "
            f"{scalex:.6f} {scaley:.6f} {angle:.8f} {dx:.5f} {dy:.5f})"
        )

    elif op == "clone":
        layer_id = requested_layer(img, p)
        src_layer = int(p.get("source_layer_id", layer_id))
        src_x = float(p.get("source_x", 0))
        src_y = float(p.get("source_y", 0))
        vector, count = scriptfu_points(p.get("points"))
        size = max(1.0, min(2000.0, float(p.get("size", 30))))
        opacity = max(0.0, min(100.0, float(p.get("opacity", 100))))
        sf.call(f"(gimp-context-set-brush-size {size:.3f})")
        sf.call(f"(gimp-context-set-opacity {opacity:.3f})")
        sf.call(
            f"(gimp-clone {layer_id} {src_layer} IMAGE-CLONE "
            f"{src_x:.5f} {src_y:.5f} {count} {vector})"
        )

    elif op == "heal":
        layer_id = requested_layer(img, p)
        src_layer = int(p.get("source_layer_id", layer_id))
        src_x = float(p.get("source_x", 0))
        src_y = float(p.get("source_y", 0))
        vector, count = scriptfu_points(p.get("points"))
        size = max(1.0, min(2000.0, float(p.get("size", 30))))
        opacity = max(0.0, min(100.0, float(p.get("opacity", 100))))
        sf.call(f"(gimp-context-set-brush-size {size:.3f})")
        sf.call(f"(gimp-context-set-opacity {opacity:.3f})")
        sf.call(
            f"(gimp-heal {layer_id} {src_layer} {src_x:.5f} {src_y:.5f} "
            f"{count} {vector})"
        )

    elif op == "smudge":
        layer_id = requested_layer(img, p)
        vector, count = scriptfu_points(p.get("points"))
        pressure = max(0.0, min(100.0, float(p.get("pressure", 50))))
        size = max(1.0, min(2000.0, float(p.get("size", 30))))
        sf.call(f"(gimp-context-set-brush-size {size:.3f})")
        sf.call(f"(gimp-smudge {layer_id} {pressure:.3f} {count} {vector})")

    elif op == "dodge_burn":
        layer_id = requested_layer(img, p)
        vector, count = scriptfu_points(p.get("points"))
        exposure = max(0.0, min(100.0, float(p.get("exposure", 50))))
        kind = "DODGE" if str(p.get("type", "dodge")).lower() == "dodge" else "BURN"
        tone_map = {
            "shadows": "TRANSFER-SHADOWS",
            "midtones": "TRANSFER-MIDTONES",
            "highlights": "TRANSFER-HIGHLIGHTS",
        }
        tone = tone_map.get(str(p.get("range", "midtones")).lower(), "TRANSFER-MIDTONES")
        sf.call(f"(gimp-dodgeburn {layer_id} {exposure:.3f} {kind} {tone} {count} {vector})")

    elif op == "gradient":
        layer_id = requested_layer(img, p)
        x1 = float(p.get("x1", 0))
        y1 = float(p.get("y1", 0))
        x2 = float(p.get("x2", 100))
        y2 = float(p.get("y2", 0))
        gradient_map = {
            "linear": "GRADIENT-LINEAR",
            "radial": "GRADIENT-RADIAL",
            "square": "GRADIENT-SQUARE",
            "bilinear": "GRADIENT-BILINEAR",
        }
        gradient_type = gradient_map.get(str(p.get("type", "linear")).lower(), "GRADIENT-LINEAR")
        if "foreground" in p:
            sf.call(f"(gimp-context-set-foreground {scriptfu_color(p.get('foreground'))})")
        if "background" in p:
            sf.call(f"(gimp-context-set-background {scriptfu_color(p.get('background'))})")
        sf.call("(gimp-context-set-gradient-fg-bg-rgb)")
        reverse = "TRUE" if bool(p.get("reverse", False)) else "FALSE"
        sf.call(f"(gimp-context-set-gradient-reverse {reverse})")
        sf.call(
            f"(gimp-drawable-edit-gradient-fill {layer_id} {gradient_type} 0 "
            f"FALSE 3 0.2 TRUE {x1:.5f} {y1:.5f} {x2:.5f} {y2:.5f})"
        )

    elif op == "select_polygon":
        vector, count = scriptfu_points(p.get("points"))
        if count < 6:
            raise HTTPException(400, "التحديد الحر يحتاج ثلاث نقاط على الأقل")
        sf.call(f"(gimp-image-select-polygon {img} CHANNEL-OP-REPLACE {count} {vector})")

    elif op == "select_contiguous":
        layer_id = requested_layer(img, p)
        x = float(p.get("x", 0))
        y = float(p.get("y", 0))
        threshold = max(0.0, min(1.0, float(p.get("threshold", 0.15))))
        sf.call(f"(gimp-context-set-sample-threshold {threshold:.5f})")
        sf.call(f"(gimp-image-select-contiguous-color {img} CHANNEL-OP-REPLACE {layer_id} {x:.5f} {y:.5f})")

    elif op == "select_color":
        layer_id = requested_layer(img, p)
        threshold = max(0.0, min(1.0, float(p.get("threshold", 0.15))))
        sf.call(f"(gimp-context-set-sample-threshold {threshold:.5f})")
        sf.call(f"(gimp-image-select-color {img} CHANNEL-OP-REPLACE {layer_id} {scriptfu_color(p.get('color', '#ffffff'))})")

    elif op == "select_item":
        item_id = int(p.get("item_id", requested_layer(img, p)))
        sf.call(f"(gimp-image-select-item {img} CHANNEL-OP-REPLACE {item_id})")

    elif op == "border":
        radius = max(1, int(p.get("radius", 1)))
        sf.call(f"(gimp-selection-border {img} {radius})")

    elif op == "sharpen_selection":
        sf.call(f"(gimp-selection-sharpen {img})")

    elif op == "translate_selection":
        dx = int(p.get("x", 0))
        dy = int(p.get("y", 0))
        sf.call(f"(gimp-selection-translate {img} {dx} {dy})")

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

    with engine_lock, state.lock:
        push_undo(state)
        try:
            apply_operation_to_gimp(state, op, request.params)
            preview_url = render_preview(state)
        except HTTPException:
            failed_current = state.image_id
            state.image_id = state.undo.pop()
            delete_gimp_image(failed_current)
            raise
        except Exception as exc:
            failed_current = state.image_id
            state.image_id = state.undo.pop()
            delete_gimp_image(failed_current)
            raise HTTPException(500, f"GIMP operation failed: {exc}")

        clear_stack(state.redo)
        return {
            "ok": True,
            "project_id": project_id,
            "operation": op,
            "preview_url": preview_url,
            "undo_depth": len(state.undo),
            "redo_depth": len(state.redo),
            "engine": "GIMP-PDB",
        }


@app.post("/api/editor/projects/{project_id}/undo")
def undo(project_id: str):
    state = project_or_404(project_id)
    with engine_lock, state.lock:
        if not state.undo:
            raise HTTPException(409, "لا توجد خطوة أقدم")

        old_current = state.image_id
        candidate = state.undo[-1]
        current_copy = sf_int(f"(car (gimp-image-duplicate {old_current}))")
        state.image_id = candidate
        try:
            preview_url = render_preview(state)
        except Exception as exc:
            state.image_id = old_current
            delete_gimp_image(current_copy)
            raise HTTPException(500, f"فشل إنشاء معاينة التراجع: {exc}")

        state.undo.pop()
        state.redo.append(current_copy)
        while len(state.redo) > MAX_HISTORY:
            delete_gimp_image(state.redo.pop(0))
        delete_gimp_image(old_current)
        return {"ok": True, "preview_url": preview_url}


@app.post("/api/editor/projects/{project_id}/redo")
def redo(project_id: str):
    state = project_or_404(project_id)
    with engine_lock, state.lock:
        if not state.redo:
            raise HTTPException(409, "لا توجد خطوة لإعادتها")

        old_current = state.image_id
        candidate = state.redo[-1]
        current_copy = sf_int(f"(car (gimp-image-duplicate {old_current}))")
        state.image_id = candidate
        try:
            preview_url = render_preview(state)
        except Exception as exc:
            state.image_id = old_current
            delete_gimp_image(current_copy)
            raise HTTPException(500, f"فشل إنشاء معاينة الإعادة: {exc}")

        state.redo.pop()
        state.undo.append(current_copy)
        while len(state.undo) > MAX_HISTORY:
            delete_gimp_image(state.undo.pop(0))
        delete_gimp_image(old_current)
        return {"ok": True, "preview_url": preview_url}


@app.get("/api/editor/projects/{project_id}/export")
def export(project_id: str, format: str = "png"):
    state = project_or_404(project_id)
    fmt = format.lower().strip()
    if fmt not in {"png", "jpg", "jpeg", "webp", "tiff", "xcf"}:
        raise HTTPException(400, "صيغة التصدير غير مدعومة")

    suffix = "jpg" if fmt == "jpeg" else fmt
    out = state.folder / f"export.{suffix}"

    with engine_lock, state.lock:
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

    destroy_project_state(state)
    return {"ok": True}
