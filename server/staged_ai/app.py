from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.responses import Response
from rembg import remove, new_session
from simple_lama_inpainting import SimpleLama
from PIL import Image, ImageFilter
from io import BytesIO
import cv2
import gc
import numpy as np

app = FastAPI(title="UNB Staged AI Editor")

_session = None
_lama = None

def get_session():
    global _session
    if _session is None:
        _session = new_session("u2net_human_seg")
    return _session

def get_lama():
    global _lama
    if _lama is None:
        _lama = SimpleLama()
    return _lama

def ensure_image(data: bytes) -> Image.Image:
    if not data:
        raise HTTPException(400, "الصورة فارغة")
    if len(data) > 25 * 1024 * 1024:
        raise HTTPException(413, "حجم الصورة أكبر من 25MB")
    try:
        return Image.open(BytesIO(data)).convert("RGB")
    except Exception:
        raise HTTPException(400, "تعذر قراءة الصورة")

def cutout_bytes(data: bytes) -> bytes:
    ensure_image(data)
    return remove(
        data,
        session=get_session(),
        alpha_matting=False,
        force_return_bytes=True
    )

def refine_mask(mask: Image.Image) -> Image.Image:
    mask = mask.convert("L")
    arr = np.array(mask)
    arr = np.where(arr > 20, 255, 0).astype(np.uint8)

    h, w = arr.shape
    k = max(7, int(min(h, w) * 0.012))
    if k % 2 == 0:
        k += 1
    kernel = np.ones((k, k), np.uint8)
    arr = cv2.dilate(arr, kernel, iterations=1)
    arr = cv2.GaussianBlur(arr, (0, 0), sigmaX=max(1.0, k / 5.0))
    arr = np.where(arr > 24, 255, 0).astype(np.uint8)
    return Image.fromarray(arr, mode="L")

def mask_image(data: bytes) -> Image.Image:
    ensure_image(data)
    raw = remove(
        data,
        session=get_session(),
        only_mask=True,
        force_return_bytes=True
    )
    return refine_mask(Image.open(BytesIO(raw)).convert("L"))

def mask_from_cutout(data: bytes) -> Image.Image:
    try:
        cutout = Image.open(BytesIO(data)).convert("RGBA")
        return refine_mask(cutout.getchannel("A"))
    except Exception:
        raise HTTPException(400, "تعذر قراءة قصاصة المرحلة 1")

def run_lama(original: Image.Image, mask: Image.Image) -> Image.Image:
    ow, oh = original.size
    max_dim = max(ow, oh)

    work_img = original
    work_mask = mask

    # 768px is much faster on the current 2-vCPU server while preserving
    # full-resolution pixels outside the inpainted subject area.
    if max_dim > 768:
        scale = 768.0 / max_dim
        nw = max(64, int(ow * scale))
        nh = max(64, int(oh * scale))
        work_img = original.resize((nw, nh), Image.Resampling.LANCZOS)
        work_mask = mask.resize((nw, nh), Image.Resampling.NEAREST)

    restored_small = get_lama()(work_img, work_mask).convert("RGB")

    if restored_small.size == original.size:
        return restored_small

    restored_up = restored_small.resize(original.size, Image.Resampling.LANCZOS)

    # Preserve original full-resolution pixels outside the removed person.
    soft = mask.filter(ImageFilter.GaussianBlur(radius=3))
    return Image.composite(restored_up, original, soft)

def png_bytes(img: Image.Image) -> bytes:
    out = BytesIO()
    img.save(out, "PNG")
    return out.getvalue()

def jpg_bytes(img: Image.Image, quality=96) -> bytes:
    out = BytesIO()
    img.convert("RGB").save(out, "JPEG", quality=quality, subsampling=0)
    return out.getvalue()

def bbox_from_mask(mask: Image.Image):
    arr = np.array(mask)
    ys, xs = np.where(arr > 32)
    if len(xs) == 0:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1

@app.get("/health")
def health():
    return {
        "ok": True,
        "service": "UNB Staged AI Editor",
        "stages": 4
    }

@app.post("/api/stage1/cut-first")
async def stage1(file: UploadFile = File(...)):
    data = await file.read()
    try:
        result = cutout_bytes(data)
        return Response(result, media_type="image/png", headers={"Cache-Control": "no-store"})
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(500, str(e))

@app.post("/api/stage2/restore-background")
async def stage2(
    file: UploadFile = File(...),
    cutout: UploadFile | None = File(None)
):
    data = await file.read()
    try:
        original = ensure_image(data)

        if cutout is not None:
            cutout_data = await cutout.read()
            mask = mask_from_cutout(cutout_data)
        else:
            mask = mask_image(data)

        restored = run_lama(original, mask)
        return Response(jpg_bytes(restored), media_type="image/jpeg", headers={"Cache-Control": "no-store"})
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(500, str(e))
    finally:
        gc.collect()

@app.post("/api/stage3/cut-second")
async def stage3(file: UploadFile = File(...)):
    data = await file.read()
    try:
        result = cutout_bytes(data)
        return Response(result, media_type="image/png", headers={"Cache-Control": "no-store"})
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(500, str(e))

@app.post("/api/stage4/composite")
async def stage4(
    target: UploadFile = File(...),
    background: UploadFile = File(...),
    subject: UploadFile = File(...),
    target_cutout: UploadFile | None = File(None)
):
    target_data = await target.read()
    bg_data = await background.read()
    subject_data = await subject.read()
    target_cutout_data = await target_cutout.read() if target_cutout is not None else None

    try:
        target_img = ensure_image(target_data)
        target_mask = mask_from_cutout(target_cutout_data) if target_cutout_data else mask_image(target_data)
        box = bbox_from_mask(target_mask)
        if box is None:
            raise HTTPException(422, "لم يتم العثور على الشخص في الصورة الأولى")

        background_img = ensure_image(bg_data)
        if background_img.size != target_img.size:
            background_img = background_img.resize(target_img.size, Image.Resampling.LANCZOS)

        try:
            subject_img = Image.open(BytesIO(subject_data)).convert("RGBA")
        except Exception:
            raise HTTPException(400, "تعذر قراءة قصاصة الصورة الثانية")

        alpha = subject_img.getchannel("A")
        sb = alpha.getbbox()
        if sb is None:
            raise HTTPException(422, "قصاصة الصورة الثانية فارغة")
        subject_img = subject_img.crop(sb)

        x1, y1, x2, y2 = box
        target_w = max(1, x2 - x1)
        target_h = max(1, y2 - y1)

        sw, sh = subject_img.size
        scale = min(target_w / sw, target_h / sh)
        # Slightly preserve breathing room around the original subject.
        scale *= 0.98
        nw = max(1, int(sw * scale))
        nh = max(1, int(sh * scale))
        subject_img = subject_img.resize((nw, nh), Image.Resampling.LANCZOS)

        center_x = (x1 + x2) // 2
        px = int(center_x - nw / 2)
        py = int(y2 - nh)

        px = max(0, min(background_img.width - nw, px))
        py = max(0, min(background_img.height - nh, py))

        canvas = background_img.convert("RGBA")
        canvas.alpha_composite(subject_img, (px, py))
        final = canvas.convert("RGB")

        return Response(jpg_bytes(final), media_type="image/jpeg", headers={"Cache-Control": "no-store"})
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(500, str(e))
