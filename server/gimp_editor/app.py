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
        "selection": ["select_all", "select_none", "invert_selection", "feather", "grow", "shrink"],
        "image": ["resize", "crop", "rotate", "flip_horizontal", "flip_vertical"],
        "layers": ["add_layer", "delete_layer", "opacity", "blend_mode", "merge_visible", "flatten"],
        "colors": ["brightness", "contrast", "hue_saturation", "levels", "curves", "color_balance",
                   "threshold", "posterize", "desaturate", "invert"],
        "paint": ["pencil", "paintbrush", "airbrush", "eraser", "fill", "stroke_selection"],
        "text": ["add_text", "edit_text", "font", "font_size", "text_color"],
        "filters": ["gaussian_blur", "unsharp_mask", "noise_reduction", "bloom", "emboss",
                    "edge", "oilify", "pixelize", "mosaic", "motion_blur", "drop_shadow",
                    "color_temperature", "gegl"],
        "resources": ["brushes", "fonts", "gradients", "patterns", "palettes"],
        "advanced": ["channels", "paths", "masks", "guides", "groups"]
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
            "select_all", "select_none", "invert_selection",
            "feather", "grow", "shrink", "flatten"
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
        layers_raw = sf.call(f"(gimp-image-get-layers {state.image_id})")
        return {
            "ok": True,
            "project_id": project_id,
            "image_id": state.image_id,
            "width": width,
            "height": height,
            "layers_raw": layers_raw,
            "undo_depth": len(state.undo),
            "redo_depth": len(state.redo),
        }


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
