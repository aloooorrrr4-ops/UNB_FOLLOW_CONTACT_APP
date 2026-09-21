from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.responses import Response
from PIL import Image, ImageFilter, ImageDraw, ImageFont, ImageOps, features
from io import BytesIO
import arabic_reshaper
from bidi.algorithm import get_display
import cv2
import gc
import asyncio
import multiprocessing
import os
import glob
import numpy as np
import pytesseract
from pytesseract import Output
import re
import unicodedata

app = FastAPI(title="UNB Staged AI Editor")

_session = None
_lama = None
_easy_ocr = None

def get_session():
    raise RuntimeError("Person cutout is disabled in OCR Lite mode")

def get_lama():
    raise RuntimeError("Background inpainting is disabled in OCR Lite mode")

def get_easy_ocr():
    raise RuntimeError("EasyOCR is disabled in OCR Lite mode")

def ensure_image(data: bytes) -> Image.Image:
    if not data:
        raise HTTPException(400, "الصورة فارغة")
    if len(data) > 25 * 1024 * 1024:
        raise HTTPException(413, "حجم الصورة أكبر من 25MB")
    try:
        img = Image.open(BytesIO(data))
        img = ImageOps.exif_transpose(img)
        return img.convert("RGB")
    except Exception:
        raise HTTPException(400, "تعذر قراءة الصورة")

def cutout_bytes(data: bytes) -> bytes:
    from rembg import remove
    img = ensure_image(data)
    normalized = BytesIO()
    img.save(normalized, "PNG")
    return remove(
        normalized.getvalue(),
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
    from rembg import remove
    img = ensure_image(data)
    normalized = BytesIO()
    img.save(normalized, "PNG")
    raw = remove(
        normalized.getvalue(),
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

def has_arabic(text: str) -> bool:
    return bool(re.search(r"[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF]", text or ""))

def detect_language(text: str) -> str:
    ar = has_arabic(text)
    en = bool(re.search(r"[A-Za-z]", text or ""))
    if ar and en:
        return "mixed"
    if ar:
        return "ar"
    if en:
        return "en"
    return "unknown"

def detect_number_type(text: str) -> str:
    western = bool(re.search(r"[0-9]", text or ""))
    arabic_indic = bool(re.search(r"[٠-٩۰-۹]", text or ""))
    if western and arabic_indic:
        return "mixed_digits"
    if arabic_indic:
        return "arabic_digits"
    if western:
        return "english_digits"
    return "none"

def rgb_to_hex(rgb):
    return "#{:02X}{:02X}{:02X}".format(
        int(np.clip(rgb[0], 0, 255)),
        int(np.clip(rgb[1], 0, 255)),
        int(np.clip(rgb[2], 0, 255))
    )

def color_info(img: Image.Image, x: int, y: int, w: int, h: int):
    arr = np.array(img).astype(np.float32)
    H, W = arr.shape[:2]

    x1 = max(0, x)
    y1 = max(0, y)
    x2 = min(W, x + w)
    y2 = min(H, y + h)

    roi = arr[y1:y2, x1:x2]
    if roi.size == 0:
        return "#000000", "#FFFFFF", "normal"

    # Estimate background from a thin ring around the detected text box.
    pad = max(2, int(round(min(max(1, w), max(1, h)) * 0.12)))
    rx1 = max(0, x1 - pad)
    ry1 = max(0, y1 - pad)
    rx2 = min(W, x2 + pad)
    ry2 = min(H, y2 + pad)

    outer = arr[ry1:ry2, rx1:rx2]
    ring_parts = []

    if y1 > ry1:
        ring_parts.append(arr[ry1:y1, rx1:rx2].reshape(-1, 3))
    if ry2 > y2:
        ring_parts.append(arr[y2:ry2, rx1:rx2].reshape(-1, 3))
    if x1 > rx1:
        ring_parts.append(arr[y1:y2, rx1:x1].reshape(-1, 3))
    if rx2 > x2:
        ring_parts.append(arr[y1:y2, x2:rx2].reshape(-1, 3))

    ring_parts = [p for p in ring_parts if p.size > 0]

    if ring_parts:
        border = np.concatenate(ring_parts, axis=0)
        bg = np.median(border, axis=0)
    else:
        bg = np.median(roi.reshape(-1, 3), axis=0)

    pixels = roi.reshape(-1, 3)
    dist = np.linalg.norm(pixels - bg, axis=1)

    if len(dist) < 8 or float(np.max(dist)) < 8:
        fg = np.array([0, 0, 0], dtype=np.float32)
        density = 0.0
    else:
        # Keep only the strongest-contrast pixels; these are normally the
        # interior of the glyphs rather than anti-aliased edges/background.
        q = float(np.percentile(dist, 88))
        threshold = max(12.0, q)
        selected = pixels[dist >= threshold]

        if len(selected) < 4:
            order = np.argsort(dist)
            selected = pixels[order[-max(4, len(order) // 12):]]

        fg = np.median(selected, axis=0)
        density = float(len(selected)) / max(1.0, float(len(pixels)))

    weight = "bold" if density > 0.18 else "normal"
    return rgb_to_hex(fg), rgb_to_hex(bg), weight

def detect_ocr_blocks_easyocr(img: Image.Image):
    np_img = np.array(img)
    reader = get_easy_ocr()
    print("EasyOCR readtext started.", flush=True)
    results = reader.readtext(
        np_img,
        detail=1,
        paragraph=False,
        decoder="greedy",
        batch_size=1,
        workers=0,
        canvas_size=1280,
        mag_ratio=1.0
    )
    print(f"EasyOCR readtext finished: {len(results)} raw items", flush=True)

    blocks = []
    block_id = 1

    for item in results:
        if not item or len(item) < 3:
            continue

        points, text, confidence = item[0], str(item[1] or "").strip(), float(item[2] or 0.0)
        if not text or confidence < 0.30:
            continue

        xs = [float(p[0]) for p in points]
        ys = [float(p[1]) for p in points]

        x1 = max(0, int(round(min(xs))))
        y1 = max(0, int(round(min(ys))))
        x2 = min(img.width, int(round(max(xs))))
        y2 = min(img.height, int(round(max(ys))))

        w = max(1, x2 - x1)
        h = max(1, y2 - y1)

        language = detect_language(text)
        direction = "rtl" if has_arabic(text) else "ltr"
        number_type = detect_number_type(text)
        text_color, bg_color, font_weight = color_info(img, x1, y1, w, h)
        font_size = max(9, int(round(h * 0.80)))

        blocks.append({
            "id": f"blk_{block_id:03d}",
            "text": text,
            "language": language,
            "number_type": number_type,
            "direction": direction,
            "bbox": {"x": x1, "y": y1, "w": w, "h": h},
            "font_size": font_size,
            "font_weight": font_weight,
            "text_color": text_color,
            "bg_color": bg_color,
            "confidence": round(max(0.0, min(1.0, confidence)), 3),
            "engine": "easyocr"
        })
        block_id += 1

    blocks.sort(key=lambda b: (b["bbox"]["y"], b["bbox"]["x"]))
    for i, block in enumerate(blocks, 1):
        block["id"] = f"blk_{i:03d}"

    return blocks

def detect_ocr_blocks_tesseract(img: Image.Image):
    data = pytesseract.image_to_data(
        img,
        lang="ara+eng",
        config="--psm 6",
        output_type=Output.DICT
    )

    blocks = []
    total = len(data.get("text", []))

    for i in range(total):
        text = (data["text"][i] or "").strip()
        if not text:
            continue

        try:
            conf = float(data["conf"][i])
        except Exception:
            conf = -1.0

        if conf < 20:
            continue

        x = int(data["left"][i])
        y = int(data["top"][i])
        w = max(1, int(data["width"][i]))
        h = max(1, int(data["height"][i]))

        # image_to_data already gives a box per word. Keep those boxes
        # separate so replacing one word never erases the whole line.
        language = detect_language(text)
        direction = "rtl" if has_arabic(text) else "ltr"
        number_type = detect_number_type(text)
        text_color, bg_color, font_weight = color_info(img, x, y, w, h)
        font_size = max(9, int(round(h * 0.95)))

        blocks.append({
            "id": "",
            "text": text,
            "language": language,
            "number_type": number_type,
            "direction": direction,
            "bbox": {"x": x, "y": y, "w": w, "h": h},
            "font_size": font_size,
            "font_weight": font_weight,
            "text_color": text_color,
            "bg_color": bg_color,
            "confidence": round(max(0.0, min(1.0, conf / 100.0)), 3),
            "engine": "tesseract",
            "block_num": int(data["block_num"][i]),
            "par_num": int(data["par_num"][i]),
            "line_num": int(data["line_num"][i]),
            "word_num": int(data["word_num"][i])
        })

    def sort_key(b):
        rtl = b["direction"] == "rtl"
        x_key = -b["bbox"]["x"] if rtl else b["bbox"]["x"]
        return (
            b["block_num"],
            b["par_num"],
            b["line_num"],
            x_key
        )

    blocks.sort(key=sort_key)
    for i, block in enumerate(blocks, 1):
        block["id"] = f"blk_{i:03d}"

    return blocks


def _easyocr_process_worker(image_array, queue):
    try:
        # Import/load in an isolated process so a PyTorch stall cannot block
        # the API process forever.
        os.environ.setdefault("OMP_NUM_THREADS", "2")
        os.environ.setdefault("MKL_NUM_THREADS", "2")
        import torch
        torch.set_num_threads(2)
        try:
            torch.set_num_interop_threads(1)
        except RuntimeError:
            pass
        import easyocr

        reader = easyocr.Reader(
            ["ar", "en"],
            gpu=False,
            quantize=False,
            model_storage_directory="/root/.EasyOCR/model",
            user_network_directory="/root/.EasyOCR/user_network",
            download_enabled=False,
            verbose=False
        )
        results = reader.readtext(
            image_array,
            detail=1,
            paragraph=False,
            decoder="greedy",
            batch_size=1,
            workers=0,
            canvas_size=1280,
            mag_ratio=1.0
        )
        queue.put(("ok", results))
    except Exception as e:
        queue.put(("error", repr(e)))

def detect_ocr_blocks_easyocr_guarded(img: Image.Image, timeout_seconds: int = 45):
    ctx = multiprocessing.get_context("spawn")
    q = ctx.Queue(maxsize=1)
    p = ctx.Process(
        target=_easyocr_process_worker,
        args=(np.array(img), q),
        daemon=True
    )
    p.start()
    p.join(timeout_seconds)

    if p.is_alive():
        p.terminate()
        p.join(5)
        raise TimeoutError(f"EasyOCR timed out after {timeout_seconds}s")

    if q.empty():
        raise RuntimeError(f"EasyOCR worker exited without result (code={p.exitcode})")

    status, payload = q.get()
    if status != "ok":
        raise RuntimeError(payload)

    # Convert raw EasyOCR output using the same block schema.
    blocks = []
    block_id = 1
    for item in payload:
        if not item or len(item) < 3:
            continue
        points, text, confidence = item[0], str(item[1] or "").strip(), float(item[2] or 0.0)
        if not text or confidence < 0.30:
            continue

        xs = [float(p[0]) for p in points]
        ys = [float(p[1]) for p in points]
        x1 = max(0, int(round(min(xs))))
        y1 = max(0, int(round(min(ys))))
        x2 = min(img.width, int(round(max(xs))))
        y2 = min(img.height, int(round(max(ys))))
        w = max(1, x2 - x1)
        h = max(1, y2 - y1)

        language = detect_language(text)
        direction = "rtl" if has_arabic(text) else "ltr"
        number_type = detect_number_type(text)
        text_color, bg_color, font_weight = color_info(img, x1, y1, w, h)
        font_size = max(9, int(round(h * 0.80)))

        blocks.append({
            "id": f"blk_{block_id:03d}",
            "text": text,
            "language": language,
            "number_type": number_type,
            "direction": direction,
            "bbox": {"x": x1, "y": y1, "w": w, "h": h},
            "font_size": font_size,
            "font_weight": font_weight,
            "text_color": text_color,
            "bg_color": bg_color,
            "confidence": round(max(0.0, min(1.0, confidence)), 3),
            "engine": "easyocr"
        })
        block_id += 1

    blocks.sort(key=lambda b: (b["bbox"]["y"], b["bbox"]["x"]))
    for i, block in enumerate(blocks, 1):
        block["id"] = f"blk_{i:03d}"
    return blocks

def detect_ocr_blocks(img: Image.Image):
    # Stable mode: EasyOCR/PyTorch repeatedly stalls on this 2-vCPU server.
    # Use Tesseract immediately so the Android app always gets a response.
    print("OCR stable mode: Tesseract started.", flush=True)
    blocks = detect_ocr_blocks_tesseract(img)
    print(f"OCR stable mode: Tesseract finished: {len(blocks)} blocks", flush=True)
    return blocks

def parse_hex_color(value: str):
    v = (value or "#000000").strip().lstrip("#")
    if len(v) == 3:
        v = "".join(c * 2 for c in v)
    if not re.fullmatch(r"[0-9A-Fa-f]{6}", v):
        return (0, 0, 0)
    return tuple(int(v[i:i + 2], 16) for i in (0, 2, 4))

def pick_font(bold: bool, arabic: bool):
    candidates = []
    if arabic:
        if bold:
            candidates += glob.glob("/usr/share/fonts/truetype/noto/*Naskh*Bold*.ttf")
            candidates += glob.glob("/usr/share/fonts/truetype/noto/*Arabic*Bold*.ttf")
        candidates += glob.glob("/usr/share/fonts/truetype/noto/*Naskh*Regular*.ttf")
        candidates += glob.glob("/usr/share/fonts/truetype/noto/*Arabic*.ttf")
    if bold:
        candidates += glob.glob("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf")
    candidates += glob.glob("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")
    return candidates[0] if candidates else None

def shape_text(text: str):
    if has_arabic(text):
        try:
            return get_display(arabic_reshaper.reshape(text))
        except Exception:
            return text
    return text

def count_text_units(text: str) -> int:
    # Count visible letters/digits only. Spaces and punctuation do not inflate
    # the ratio used to size a replacement word.
    return max(
        1,
        sum(
            1
            for ch in (text or "")
            if ch.isalnum() or has_arabic(ch)
        )
    )

def make_font(font_path: str | None, size: int, arabic: bool):
    if not font_path:
        return ImageFont.load_default(), False

    if arabic:
        try:
            if features.check("raqm"):
                return ImageFont.truetype(
                    font_path,
                    size=size,
                    layout_engine=ImageFont.Layout.RAQM
                ), True
        except Exception:
            pass

    return ImageFont.truetype(font_path, size=size), False

def text_metrics(draw, text: str, font, arabic: bool, use_raqm: bool):
    if arabic and use_raqm:
        box = draw.textbbox(
            (0, 0),
            text,
            font=font,
            direction="rtl",
            language="ar"
        )
    else:
        render_text = shape_text(text) if arabic else text
        box = draw.textbbox((0, 0), render_text, font=font)

    return box, max(1, box[2] - box[0]), max(1, box[3] - box[1])

def estimate_background_rgb(img: Image.Image, x: int, y: int, w: int, h: int):
    arr = np.array(img)
    H, W = arr.shape[:2]
    pad = max(3, int(max(w, h) * 0.10))

    x1 = max(0, x - pad)
    y1 = max(0, y - pad)
    x2 = min(W, x + w + pad)
    y2 = min(H, y + h + pad)

    roi = arr[y1:y2, x1:x2]
    if roi.size == 0:
        return np.array([255,255,255], dtype=np.float32)

    # Border is more likely to represent the real local background than the text.
    border = np.concatenate([
        roi[:max(1, pad//2)].reshape(-1,3),
        roi[-max(1, pad//2):].reshape(-1,3),
        roi[:, :max(1, pad//2)].reshape(-1,3),
        roi[:, -max(1, pad//2):].reshape(-1,3)
    ], axis=0)

    return np.median(border, axis=0).astype(np.float32)

def build_text_glyph_mask(img: Image.Image, x: int, y: int, w: int, h: int):
    arr = np.array(img)
    H, W = arr.shape[:2]

    x1 = max(0, x)
    y1 = max(0, y)
    x2 = min(W, x + w)
    y2 = min(H, y + h)

    roi = arr[y1:y2, x1:x2]
    if roi.size == 0:
        return None, (x1,y1,x2,y2)

    bg = estimate_background_rgb(img, x, y, w, h)
    pix = roi.astype(np.float32)
    dist = np.linalg.norm(pix - bg.reshape(1,1,3), axis=2)

    # Keep only pixels that differ enough from the local background.
    # This targets the actual glyphs instead of erasing a whole rectangle.
    base = max(16.0, float(np.percentile(dist, 72)))
    mask = np.where(dist >= base, 255, 0).astype(np.uint8)

    # Remove isolated noise and slightly expand the glyph strokes.
    mask = cv2.medianBlur(mask, 3)
    k = max(2, int(round(max(2, min(w,h) * 0.06))))
    kernel = np.ones((k, k), np.uint8)
    mask = cv2.dilate(mask, kernel, iterations=1)

    # Keep the mask concentrated around the OCR box.
    return mask, (x1,y1,x2,y2)

def normalize_compare_text(text: str) -> str:
    value = unicodedata.normalize("NFKC", text or "")
    value = value.replace("\u200f", "").replace("\u200e", "")
    value = re.sub(r"\s+", " ", value).strip()
    return value

def _smooth_background_fill(arr_rgb: np.ndarray, x1: int, y1: int, x2: int, y2: int):
    H, W = arr_rgb.shape[:2]
    region_w = max(1, x2 - x1)
    region_h = max(1, y2 - y1)

    margin_x = max(4, int(round(region_w * 0.10)))
    margin_y = max(4, int(round(region_h * 0.35)))

    sx1 = max(0, x1 - margin_x)
    sy1 = max(0, y1 - margin_y)
    sx2 = min(W, x2 + margin_x)
    sy2 = min(H, y2 + margin_y)

    samples_xy = []
    samples_rgb = []

    def add_patch(px1, py1, px2, py2):
        if px2 <= px1 or py2 <= py1:
            return
        patch = arr_rgb[py1:py2, px1:px2].astype(np.float32)
        yy, xx = np.mgrid[py1:py2, px1:px2]
        samples_xy.append(np.stack([
            xx.reshape(-1),
            yy.reshape(-1),
            np.ones(xx.size, dtype=np.float32)
        ], axis=1))
        samples_rgb.append(patch.reshape(-1, 3))

    add_patch(sx1, sy1, sx2, y1)
    add_patch(sx1, y2, sx2, sy2)
    add_patch(sx1, y1, x1, y2)
    add_patch(x2, y1, sx2, y2)

    if not samples_xy:
        return None, 999.0

    A = np.concatenate(samples_xy, axis=0)
    B = np.concatenate(samples_rgb, axis=0)

    if len(A) < 20:
        return None, 999.0

    # Robust two-pass plane fit. Nearby letters are treated as outliers.
    coef, *_ = np.linalg.lstsq(A, B, rcond=None)
    pred = A @ coef
    resid = np.linalg.norm(B - pred, axis=1)

    keep_threshold = max(10.0, float(np.percentile(resid, 65)))
    keep = resid <= keep_threshold

    if int(np.sum(keep)) >= 20:
        coef, *_ = np.linalg.lstsq(A[keep], B[keep], rcond=None)
        pred = A[keep] @ coef
        residual = float(np.median(np.linalg.norm(B[keep] - pred, axis=1)))
    else:
        residual = float(np.median(resid))

    yy, xx = np.mgrid[y1:y2, x1:x2]
    target_A = np.stack([
        xx.reshape(-1),
        yy.reshape(-1),
        np.ones(xx.size, dtype=np.float32)
    ], axis=1)

    fill = (target_A @ coef).reshape(y2 - y1, x2 - x1, 3)
    fill = np.clip(fill, 0, 255).astype(np.uint8)
    return fill, residual


def _tight_word_mask(img: Image.Image, x: int, y: int, w: int, h: int):
    arr = np.array(img.convert("RGB"))
    H, W = arr.shape[:2]

    x1 = max(0, x)
    y1 = max(0, y)
    x2 = min(W, x + w)
    y2 = min(H, y + h)
    roi = arr[y1:y2, x1:x2]

    if roi.size == 0:
        return None, None, None

    bg = estimate_background_rgb(img, x, y, w, h)
    diff = np.linalg.norm(roi.astype(np.float32) - bg.reshape(1, 1, 3), axis=2)

    # Adaptive threshold: text is the locally strongest contrast, while
    # preserving anti-aliased edge pixels.
    p70 = float(np.percentile(diff, 70))
    p88 = float(np.percentile(diff, 88))
    threshold = max(10.0, min(p88 * 0.72, max(14.0, p70)))

    mask = np.where(diff >= threshold, 255, 0).astype(np.uint8)

    # Preserve Arabic dots/diacritics. A morphology-open here can delete dots
    # such as ب/ت/ث/ن/ي, so only close tiny gaps and leave small components.
    kernel = np.ones((2, 2), np.uint8)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel)

    ys, xs = np.where(mask > 0)
    if len(xs) < 4:
        return mask, (x1, y1, x2, y2), None

    gx1, gy1 = int(xs.min()), int(ys.min())
    gx2, gy2 = int(xs.max()) + 1, int(ys.max()) + 1

    return mask, (x1, y1, x2, y2), {
        "x": x1 + gx1,
        "y": y1 + gy1,
        "w": max(1, gx2 - gx1),
        "h": max(1, gy2 - gy1),
        "local_x": gx1,
        "local_y": gy1,
        "local_w": max(1, gx2 - gx1),
        "local_h": max(1, gy2 - gy1),
    }


def _font_candidates(arabic: bool, bold: bool):
    paths = []

    if arabic:
        roots = [
            "/usr/share/fonts/truetype/noto/",
            "/usr/share/fonts/opentype/noto/",
            "/usr/share/fonts/truetype/",
        ]
        # Include all available Arabic weights/widths. Ordering is only a
        # tiebreaker; visual scoring still chooses the actual best match.
        preferred = [
            "*NotoSansArabic*.ttf",
            "*NotoSansArabicUI*.ttf",
            "*NotoKufiArabic*.ttf",
            "*NotoNaskhArabic*.ttf",
            "*NotoNaskhArabicUI*.ttf",
            "*Arabic*.ttf",
            "*Naskh*.ttf",
        ]
        for root in roots:
            for pat in preferred:
                paths.extend(glob.glob(root + pat))
                paths.extend(glob.glob(root + "**/" + pat, recursive=True))

    if bold:
        paths.extend(glob.glob("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"))
    else:
        paths.extend(glob.glob("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"))

    out = []
    seen = set()
    for path in paths:
        if path not in seen and os.path.isfile(path):
            seen.add(path)
            out.append(path)
        if len(out) >= 48:
            break
    return out


def _render_text_alpha(text: str, font_path: str, size: int, arabic: bool):
    font, use_raqm = make_font(font_path, max(6, int(size)), arabic)

    # Large scratch canvas; crop to real glyph pixels afterwards.
    scratch_w = max(256, int(size * max(8, len(text)) * 2.2))
    scratch_h = max(128, int(size * 3.2))
    layer = Image.new("L", (scratch_w, scratch_h), 0)
    d = ImageDraw.Draw(layer)

    if arabic and use_raqm:
        d.text(
            (scratch_w - 8, 8),
            text,
            font=font,
            fill=255,
            anchor="ra",
            direction="rtl",
            language="ar"
        )
    else:
        render_text = shape_text(text) if arabic else text
        d.text((8, 8), render_text, font=font, fill=255)

    box = layer.getbbox()
    if box is None:
        return None, use_raqm
    return layer.crop(box), use_raqm


def _font_size_for_height(font_path: str, text: str, arabic: bool, target_h: int):
    lo = 6
    hi = max(16, int(target_h * 3.2))
    best_size = max(8, int(target_h))
    best_delta = 10**9

    while lo <= hi:
        mid = (lo + hi) // 2
        alpha, _ = _render_text_alpha(text, font_path, mid, arabic)
        gh = alpha.height if alpha is not None else 0
        delta = abs(gh - target_h)

        if delta < best_delta:
            best_delta = delta
            best_size = mid

        if gh < target_h:
            lo = mid + 1
        else:
            hi = mid - 1

    return max(6, best_size)


def _normalized_mask(mask: np.ndarray, width=128, height=64):
    if mask is None or mask.size == 0:
        return None
    src = np.where(mask > 0, 255, 0).astype(np.uint8)
    ys, xs = np.where(src > 0)
    if len(xs) == 0:
        return None
    crop = src[ys.min():ys.max()+1, xs.min():xs.max()+1]
    return cv2.resize(crop, (width, height), interpolation=cv2.INTER_AREA)


def _match_source_font(img: Image.Image, x: int, y: int, w: int, h: int,
                       original_text: str, font_weight: str):
    arabic = has_arabic(original_text)
    mask, _, glyph = _tight_word_mask(img, x, y, w, h)

    if glyph is None or not original_text.strip():
        fallback = pick_font(font_weight == "bold", arabic)
        return fallback, max(8, int(h * 0.95)), glyph, None

    local = mask[
        glyph["local_y"]:glyph["local_y"] + glyph["local_h"],
        glyph["local_x"]:glyph["local_x"] + glyph["local_w"]
    ]
    target_norm = _normalized_mask(local)

    candidates = _font_candidates(arabic, font_weight == "bold")
    fallback = pick_font(font_weight == "bold", arabic)
    if fallback and fallback not in candidates:
        candidates.append(fallback)

    if not candidates or target_norm is None:
        return fallback, max(8, int(h * 0.95)), glyph, None

    best = None

    for path in candidates:
        try:
            size = _font_size_for_height(
                path,
                original_text,
                arabic,
                max(4, glyph["h"])
            )
            alpha, use_raqm = _render_text_alpha(original_text, path, size, arabic)
            if alpha is None:
                continue

            rendered = np.array(alpha)
            rendered_norm = _normalized_mask(rendered)
            if rendered_norm is None:
                continue

            # Compare silhouette plus stroke density. This is not font-name
            # recognition; it finds the installed font whose raster shape most
            # closely resembles the actual source word.
            a = (target_norm.astype(np.float32) / 255.0)
            b = (rendered_norm.astype(np.float32) / 255.0)
            mse = float(np.mean((a - b) ** 2))

            # Weight/stroke density is critical for names on scanned IDs.
            target_density = float(np.mean(a > 0.30))
            rendered_density = float(np.mean(b > 0.30))
            density_delta = abs(target_density - rendered_density)

            target_aspect = glyph["w"] / float(max(1, glyph["h"]))
            rendered_aspect = alpha.width / float(max(1, alpha.height))
            aspect_delta = abs(np.log(max(0.05, rendered_aspect) / max(0.05, target_aspect)))

            score = mse + density_delta * 1.80 + aspect_delta * 0.28

            if best is None or score < best["score"]:
                best = {
                    "path": path,
                    "size": size,
                    "score": score,
                    "raqm": use_raqm
                }
        except Exception:
            continue

    if best is None:
        return fallback, max(8, int(h * 0.95)), glyph, None

    return best["path"], best["size"], glyph, round(best["score"], 4)



def _bbox_iou_dict(a: dict, b: dict) -> float:
    ax1, ay1 = int(a["x"]), int(a["y"])
    ax2, ay2 = ax1 + int(a["w"]), ay1 + int(a["h"])
    bx1, by1 = int(b["x"]), int(b["y"])
    bx2, by2 = bx1 + int(b["w"]), by1 + int(b["h"])

    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    iw, ih = max(0, ix2 - ix1), max(0, iy2 - iy1)
    inter = iw * ih
    union = max(1, (ax2 - ax1) * (ay2 - ay1) + (bx2 - bx1) * (by2 - by1) - inter)
    return inter / float(union)


def _line_reference_samples(img: Image.Image, x: int, y: int, w: int, h: int,
                            original_text: str, max_refs: int = 4):
    selected = {"x": x, "y": y, "w": w, "h": h}
    try:
        blocks = detect_ocr_blocks_tesseract(img)
    except Exception:
        blocks = []

    # Locate the OCR block corresponding to the selected word.
    target = None
    best_iou = 0.0
    for block in blocks:
        bbox = block.get("bbox") or {}
        if not all(k in bbox for k in ("x", "y", "w", "h")):
            continue
        score = _bbox_iou_dict(selected, bbox)
        if score > best_iou:
            best_iou = score
            target = block

    samples = [{
        "text": original_text,
        "bbox": selected,
        "is_target": True
    }]

    if target is None:
        return samples

    line_key = (
        target.get("block_num"),
        target.get("par_num"),
        target.get("line_num")
    )

    candidates = []
    for block in blocks:
        if block is target:
            continue
        if (
            block.get("block_num"),
            block.get("par_num"),
            block.get("line_num")
        ) != line_key:
            continue

        text = str(block.get("text") or "").strip()
        if not text or not has_arabic(text):
            continue

        bbox = block.get("bbox") or {}
        # Prefer similar-sized words close to the selected word.
        cy = float(bbox.get("y", 0)) + float(bbox.get("h", 1)) / 2.0
        sy = y + h / 2.0
        height_ratio = float(bbox.get("h", 1)) / max(1.0, float(h))
        if not (0.55 <= height_ratio <= 1.65):
            continue

        distance = abs(cy - sy) + abs(float(bbox.get("x", 0)) - x) * 0.04
        candidates.append((distance, block))

    candidates.sort(key=lambda t: t[0])
    for _, block in candidates[:max_refs]:
        samples.append({
            "text": str(block.get("text") or ""),
            "bbox": dict(block["bbox"]),
            "is_target": False
        })

    return samples


def _sample_word_mask_metrics(img: Image.Image, sample: dict):
    bbox = sample["bbox"]
    mask, _, glyph = _tight_word_mask(
        img,
        int(bbox["x"]), int(bbox["y"]), int(bbox["w"]), int(bbox["h"])
    )
    if mask is None or glyph is None:
        return None

    local = mask[
        glyph["local_y"]:glyph["local_y"] + glyph["local_h"],
        glyph["local_x"]:glyph["local_x"] + glyph["local_w"]
    ]
    if local.size == 0:
        return None

    binary = np.where(local > 0, 255, 0).astype(np.uint8)
    norm = _normalized_mask(binary)
    if norm is None:
        return None

    density = float(np.mean(binary > 0))
    dist = cv2.distanceTransform(binary, cv2.DIST_L2, 5)
    vals = dist[dist > 0]
    stroke = float(np.median(vals)) if vals.size else 0.0

    text_color, _, _ = color_info(
        img,
        int(bbox["x"]), int(bbox["y"]), int(bbox["w"]), int(bbox["h"])
    )

    # Core ink color: take the darkest local pixels, which better reflects the
    # printed stroke tone than anti-aliased edge pixels.
    arr_rgb = np.array(img.convert("RGB"))
    bx1 = max(0, int(bbox["x"]))
    by1 = max(0, int(bbox["y"]))
    bx2 = min(img.width, bx1 + int(bbox["w"]))
    by2 = min(img.height, by1 + int(bbox["h"]))
    roi_rgb = arr_rgb[by1:by2, bx1:bx2]
    core_color = parse_hex_color(text_color)
    if roi_rgb.size:
        roi_gray = cv2.cvtColor(roi_rgb, cv2.COLOR_RGB2GRAY)
        cutoff = float(np.percentile(roi_gray, 9))
        dark_pixels = roi_rgb[roi_gray <= cutoff]
        if len(dark_pixels) >= 4:
            core_color = tuple(int(v) for v in np.median(dark_pixels, axis=0))

    return {
        "text": sample["text"],
        "bbox": bbox,
        "glyph": glyph,
        "mask": binary,
        "norm": norm,
        "density": density,
        "stroke": stroke,
        "text_color": text_color,
        "core_color": rgb_to_hex(core_color),
        "glyph_height": int(glyph["h"]),
        "is_target": bool(sample.get("is_target"))
    }


def _match_font_from_line(img: Image.Image, samples: list, font_weight: str):
    measured = []
    for sample in samples:
        m = _sample_word_mask_metrics(img, sample)
        if m is not None and str(m["text"]).strip():
            measured.append(m)

    if not measured:
        return None

    arabic = True
    candidates = _font_candidates(arabic, font_weight == "bold")
    fallback = pick_font(font_weight == "bold", arabic)
    if fallback and fallback not in candidates:
        candidates.append(fallback)

    best = None

    for path in candidates:
        scores = []
        width_scales = []
        fitted_sizes = []

        for m in measured:
            try:
                glyph = m["glyph"]
                size = _font_size_for_height(
                    path,
                    m["text"],
                    True,
                    max(4, int(glyph["h"]))
                )
                alpha, _ = _render_text_alpha(m["text"], path, size, True)
                if alpha is None:
                    continue
                fitted_sizes.append(size)

                rendered_norm = _normalized_mask(np.array(alpha))
                if rendered_norm is None:
                    continue

                a = m["norm"].astype(np.float32) / 255.0
                b = rendered_norm.astype(np.float32) / 255.0
                mse = float(np.mean((a - b) ** 2))

                alpha_arr = np.array(alpha)
                rdensity = float(np.mean(alpha_arr > 76))
                density_delta = abs(m["density"] - rdensity)
                rstroke = _alpha_stroke_metric(alpha_arr)
                stroke_delta = abs(m["stroke"] - rstroke) / max(1.0, m["stroke"])

                source_aspect = glyph["w"] / float(max(1, glyph["h"]))
                rendered_aspect = alpha.width / float(max(1, alpha.height))
                aspect_delta = abs(np.log(
                    max(0.05, rendered_aspect) / max(0.05, source_aspect)
                ))

                # Target word counts a little more, but neighbors stabilize the
                # actual line font/weight/color.
                weight = 1.30 if m["is_target"] else 1.0
                score = weight * (
                    mse +
                    density_delta * 1.35 +
                    stroke_delta * 0.95 +
                    aspect_delta * 0.22
                )
                scores.append(score)

                scale = source_aspect / max(0.05, rendered_aspect)
                width_scales.append(float(np.clip(scale, 0.72, 1.38)))
            except Exception:
                continue

        if not scores:
            continue

        avg_score = float(np.mean(scores))
        if best is None or avg_score < best["score"]:
            best = {
                "path": path,
                "score": avg_score,
                "width_scale": float(np.median(width_scales)) if width_scales else 1.0,
                "font_size": int(round(float(np.median(fitted_sizes)))) if fitted_sizes else 12
            }

    if best is None:
        return None

    colors = []
    core_colors = []
    densities = []
    strokes = []
    bottoms = []
    neighbor_heights = []

    for m in measured:
        colors.append(parse_hex_color(m["text_color"]))
        core_colors.append(parse_hex_color(m["core_color"]))
        densities.append(m["density"])
        strokes.append(m["stroke"])
        g = m["glyph"]
        bottoms.append(int(g["y"] + g["h"]))
        if not m["is_target"]:
            neighbor_heights.append(int(g["h"]))

    color_arr = np.array(colors, dtype=np.float32)
    median_color = tuple(int(v) for v in np.median(color_arr, axis=0))
    core_arr = np.array(core_colors, dtype=np.float32)
    median_core = tuple(int(v) for v in np.median(core_arr, axis=0))

    return {
        "font_path": best["path"],
        "font_score": round(best["score"], 4),
        "font_size": int(best.get("font_size", 12)),
        "width_scale": round(best["width_scale"], 4),
        "target_density": float(np.median(densities)),
        "target_stroke": float(np.median(strokes)),
        "line_color": rgb_to_hex(median_color),
        "line_core_color": rgb_to_hex(median_core),
        "line_height": int(round(float(np.median(neighbor_heights)))) if neighbor_heights else None,
        "baseline_bottom": int(round(float(np.median(bottoms)))),
        "reference_count": len(measured)
    }


def _match_alpha_density(alpha: Image.Image, target_density: float):
    arr = np.array(alpha).astype(np.uint8)
    if arr.size == 0:
        return alpha

    best = arr
    best_delta = abs(float(np.mean(arr > 76)) - float(target_density))

    for op in (-2, -1, 0, 1, 2):
        test = arr.copy()
        if op > 0:
            k = np.ones((op * 2 + 1, op * 2 + 1), np.uint8)
            test = cv2.dilate(test, k, iterations=1)
        elif op < 0:
            r = abs(op)
            k = np.ones((r * 2 + 1, r * 2 + 1), np.uint8)
            test = cv2.erode(test, k, iterations=1)

        density = float(np.mean(test > 76))
        delta = abs(density - float(target_density))
        if delta < best_delta:
            best_delta = delta
            best = test

    return Image.fromarray(best, mode="L")


def _alpha_stroke_metric(arr: np.ndarray) -> float:
    binary = np.where(arr > 76, 255, 0).astype(np.uint8)
    if not np.any(binary):
        return 0.0
    dist = cv2.distanceTransform(binary, cv2.DIST_L2, 5)
    vals = dist[dist > 0]
    return float(np.median(vals)) if vals.size else 0.0


def _match_alpha_stroke_and_density(alpha: Image.Image,
                                    target_stroke: float,
                                    target_density: float):
    arr = np.array(alpha).astype(np.uint8)
    if arr.size == 0:
        return alpha

    base_stroke = _alpha_stroke_metric(arr)
    base_density = float(np.mean(arr > 76))

    best = arr
    best_score = (
        abs(base_stroke - float(target_stroke)) * 1.8 +
        abs(base_density - float(target_density)) * 5.0
    )

    # Positive values thicken, negative values thin. Up to 4 px is enough to
    # emulate heavier weights such as ExtraBold/Black when only Bold is installed.
    for op in range(-3, 5):
        test = arr.copy()
        if op > 0:
            k = np.ones((op * 2 + 1, op * 2 + 1), np.uint8)
            test = cv2.dilate(test, k, iterations=1)
        elif op < 0:
            r = abs(op)
            k = np.ones((r * 2 + 1, r * 2 + 1), np.uint8)
            test = cv2.erode(test, k, iterations=1)

        stroke = _alpha_stroke_metric(test)
        density = float(np.mean(test > 76))
        score = (
            abs(stroke - float(target_stroke)) * 1.8 +
            abs(density - float(target_density)) * 5.0
        )
        if score < best_score:
            best_score = score
            best = test

    return Image.fromarray(best, mode="L")


def _source_render_style(img: Image.Image, x: int, y: int, w: int, h: int,
                         original_text: str, font_weight: str):
    samples = _line_reference_samples(
        img, x, y, w, h, original_text, max_refs=4
    )
    line_match = _match_font_from_line(img, samples, font_weight)

    # Keep target-word glyph geometry for exact anchor/erase.
    mask, _, glyph = _tight_word_mask(img, x, y, w, h)
    text_color, bg_color, detected_weight = color_info(img, x, y, w, h)

    font_path = None
    font_score = None
    line_font_size = None
    line_height = None
    width_scale = 1.0
    target_density = 0.0
    target_stroke = 0.0
    baseline_bottom = int((glyph["y"] + glyph["h"]) if glyph else (y + h))
    reference_count = 1

    if line_match:
        font_path = line_match.get("font_path")
        font_score = line_match.get("font_score")
        line_font_size = int(line_match.get("font_size", 0) or 0) or None
        line_height = line_match.get("line_height")
        width_scale = float(line_match.get("width_scale", 1.0))
        target_density = float(line_match.get("target_density", 0.0))
        target_stroke = float(line_match.get("target_stroke", 0.0))
        baseline_bottom = int(line_match.get("baseline_bottom", baseline_bottom))
        reference_count = int(line_match.get("reference_count", 1))
        # Same-line core ink is the strongest visual color reference.
        text_color = line_match.get("line_core_color") or line_match.get("line_color") or text_color

    if not font_path:
        font_path, _, glyph2, font_score = _match_source_font(
            img, x, y, w, h, original_text, font_weight
        )
        if glyph is None:
            glyph = glyph2

    if not font_path:
        font_path = pick_font(font_weight == "bold", True)

    # Preserve the visual gap to the nearest same-line neighbor. For Arabic,
    # anchoring only to the old right edge makes a shorter replacement leave a
    # visibly large empty gap before the next word.
    anchor_mode = "right"
    nearest_gap = None
    for sample in samples:
        if sample.get("is_target"):
            continue
        b = sample.get("bbox") or {}
        bx = int(b.get("x", 0))
        bw = int(b.get("w", 0))

        # Neighbor entirely on the left side of the selected word.
        if bx + bw <= x:
            gap = x - (bx + bw)
            if nearest_gap is None or gap < nearest_gap:
                nearest_gap = gap
                anchor_mode = "left"

        # Neighbor entirely on the right side.
        elif bx >= x + w:
            gap = bx - (x + w)
            if nearest_gap is None or gap < nearest_gap:
                nearest_gap = gap
                anchor_mode = "right"

    # Source softness is measured locally; don't wash text out with low alpha.
    arr = np.array(img.convert("RGB"))
    x1, y1 = max(0, x), max(0, y)
    x2, y2 = min(img.width, x + w), min(img.height, y + h)
    roi = arr[y1:y2, x1:x2]

    blur_radius = 0.16
    if roi.size:
        gray = cv2.cvtColor(roi, cv2.COLOR_RGB2GRAY)
        sharpness = float(cv2.Laplacian(gray, cv2.CV_64F).var())
        if sharpness < 16:
            blur_radius = 0.38
        elif sharpness < 32:
            blur_radius = 0.28
        elif sharpness < 65:
            blur_radius = 0.20

    return {
        "font_path": font_path,
        "font_size": line_font_size,
        "line_height": line_height,
        "glyph": glyph,
        "font_score": font_score,
        "text_color": text_color,
        "bg_color": bg_color,
        "font_weight": detected_weight or font_weight,
        "blur_radius": blur_radius,
        "opacity": 255,
        "width_scale": float(np.clip(width_scale, 0.72, 1.38)),
        "target_density": target_density,
        "target_stroke": target_stroke,
        "baseline_bottom": baseline_bottom,
        "reference_count": reference_count,
        "anchor_mode": anchor_mode,
        "nearest_gap": nearest_gap,
    }


def erase_text_area(img: Image.Image, x: int, y: int, w: int, h: int):
    rgb = np.array(img.convert("RGB"))
    H, W = rgb.shape[:2]

    # A tiny padded rectangle contains the complete old word including
    # anti-aliased gray fringes that glyph-threshold masks can miss.
    pad_x = max(2, int(round(w * 0.018)))
    pad_y = max(2, int(round(h * 0.065)))
    rx1 = max(0, x - pad_x)
    ry1 = max(0, y - pad_y)
    rx2 = min(W, x + w + pad_x)
    ry2 = min(H, y + h + pad_y)

    # Smooth scanned-card backgrounds are better reconstructed as a local
    # color plane over the whole old-word footprint. This removes ghost
    # remnants without the dirty Telea halo.
    fill, residual = _smooth_background_fill(rgb, rx1, ry1, rx2, ry2)
    if fill is not None and residual <= 30.0:
        restored = rgb.copy()
        restored[ry1:ry2, rx1:rx2] = fill

        # Feather only the outer border; keep the interior fully replaced so no
        # traces of the previous letters survive.
        mask = np.zeros((H, W), dtype=np.uint8)
        cv2.rectangle(mask, (rx1, ry1), (rx2 - 1, ry2 - 1), 255, -1)

        # Erode before blur so the interior stays at alpha=1.
        edge = max(1, min(4, int(round(min(w, h) * 0.05))))
        k = np.ones((edge * 2 + 1, edge * 2 + 1), np.uint8)
        core = cv2.erode(mask, k, iterations=1)
        soft = cv2.GaussianBlur(mask, (0, 0), sigmaX=max(0.8, edge * 0.8))
        soft = np.maximum(core, soft).astype(np.float32) / 255.0
        soft = soft[..., None]

        out = (
            restored.astype(np.float32) * soft +
            rgb.astype(np.float32) * (1.0 - soft)
        ).clip(0, 255).astype(np.uint8)
        return Image.fromarray(out)

    # Textured/complex background fallback: erase only the detected glyphs.
    local_mask, bounds, glyph = _tight_word_mask(img, x, y, w, h)
    if local_mask is None or bounds is None:
        return img.copy()

    x1, y1, x2, y2 = bounds
    if x2 <= x1 or y2 <= y1:
        return img.copy()

    glyph_mask = np.where(local_mask > 0, 255, 0).astype(np.uint8)
    dilate_px = max(1, int(round(max(1, min(w, h)) * 0.075)))
    kernel = np.ones((dilate_px * 2 + 1, dilate_px * 2 + 1), np.uint8)
    glyph_mask = cv2.dilate(glyph_mask, kernel, iterations=1)

    full_mask = np.zeros((H, W), dtype=np.uint8)
    full_mask[y1:y2, x1:x2] = glyph_mask

    bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
    restored_bgr = cv2.inpaint(bgr, full_mask, 2, cv2.INPAINT_TELEA)
    restored = cv2.cvtColor(restored_bgr, cv2.COLOR_BGR2RGB)
    return Image.fromarray(restored)


def draw_replacement(
    img: Image.Image,
    x: int,
    y: int,
    w: int,
    h: int,
    new_text: str,
    original_text: str,
    font_size: int,
    text_color: str,
    direction: str,
    font_weight: str,
    source_style: dict | None = None
):
    canvas = img.copy()
    is_ar = has_arabic(new_text)

    style = source_style or {}
    glyph = style.get("glyph")

    font_path = style.get("font_path") or pick_font(font_weight == "bold", is_ar)

    # Use a point size calibrated from several neighboring words on the same
    # line. This is more typographically stable than forcing every replacement
    # to the old word's tight pixel height.
    calibrated_size = style.get("font_size")
    if calibrated_size:
        chosen_size = max(6, int(calibrated_size))
    elif glyph and font_path:
        chosen_size = _font_size_for_height(
            font_path,
            new_text,
            is_ar,
            max(4, int(glyph["h"]))
        )
    else:
        chosen_size = max(8, int(font_size))

    alpha, use_raqm = _render_text_alpha(new_text, font_path, chosen_size, is_ar) if font_path else (None, False)
    if alpha is None:
        return canvas

    width_scale = float(style.get("width_scale", 1.0))
    target_h = alpha.height
    target_w = max(1, int(round(alpha.width * width_scale)))

    # Keep the rendered point size. Only apply a small global scale if it is
    # clearly outside the neighboring line height.
    line_height = style.get("line_height")
    if line_height:
        line_height = max(1, int(line_height))
        if target_h < int(line_height * 0.82):
            scale = (line_height * 0.92) / max(1.0, float(target_h))
            target_h = max(1, int(round(target_h * scale)))
            target_w = max(1, int(round(target_w * scale)))
        elif target_h > int(line_height * 1.18):
            scale = (line_height * 1.05) / max(1.0, float(target_h))
            target_h = max(1, int(round(target_h * scale)))
            target_w = max(1, int(round(target_w * scale)))

    old_units = count_text_units(original_text)
    new_units = count_text_units(new_text)
    max_expand = 1.22 if new_units <= old_units else min(1.70, 1.12 + 0.08 * (new_units - old_units))
    max_w = max(1, int(round(w * max_expand)))
    if target_w > max_w:
        scale = max_w / float(target_w)
        target_w = max_w
        target_h = max(1, int(round(target_h * scale)))

    alpha = alpha.resize(
        (target_w, target_h),
        Image.Resampling.LANCZOS
    )

    target_density = float(style.get("target_density", 0.0))
    target_stroke = float(style.get("target_stroke", 0.0))
    if target_density > 0:
        target_density = min(0.80, target_density * 1.08)
    if target_stroke > 0:
        target_stroke = target_stroke * 1.06
    if target_stroke > 0.01 or target_density > 0.01:
        alpha = _match_alpha_stroke_and_density(
            alpha,
            target_stroke if target_stroke > 0.01 else _alpha_stroke_metric(np.array(alpha)),
            target_density if target_density > 0.01 else float(np.mean(np.array(alpha) > 76))
        )

    # Real source glyph anchor, not the coarse OCR box.
    if glyph:
        source_right = int(glyph["x"] + glyph["w"])
        source_left = int(glyph["x"])
        source_bottom = int(glyph["y"] + glyph["h"])
    else:
        source_right = x + w
        source_left = x
        source_bottom = y + h

    anchor_mode = style.get("anchor_mode", "right")
    if direction == "rtl" or is_ar:
        if anchor_mode == "left":
            # Keep the original gap to the word on the left. A shorter Arabic
            # replacement then grows toward the right instead of opening a gap.
            paste_x = source_left
        else:
            paste_x = source_right - target_w
    else:
        if anchor_mode == "right":
            paste_x = source_right - target_w
        else:
            paste_x = source_left

    # Bottom alignment approximates a shared Arabic baseline much better than
    # top alignment when the replacement contains different ascenders/descenders.
    baseline_bottom = int(style.get("baseline_bottom", source_bottom))
    # Keep the selected word near its original baseline; same-line references
    # correct OCR boxes that are slightly high/low.
    if abs(baseline_bottom - source_bottom) <= max(3, int(h * 0.35)):
        paste_y = baseline_bottom - target_h
    else:
        paste_y = source_bottom - target_h

    paste_x = max(0, min(canvas.width - target_w, paste_x))
    paste_y = max(0, min(canvas.height - target_h, paste_y))

    # Source color takes priority for automatic matching. The UI color remains
    # available as fallback if source analysis did not succeed.
    color_hex = style.get("text_color") or text_color
    color = parse_hex_color(color_hex)
    opacity = int(style.get("opacity", 255))

    rgba = Image.new("RGBA", (target_w, target_h), (*color, 0))
    rgba.putalpha(alpha.point(lambda p: int(p * opacity / 255)))

    blur_radius = float(style.get("blur_radius", 0.35))
    if blur_radius > 0.01:
        rgba = rgba.filter(ImageFilter.GaussianBlur(radius=blur_radius))

    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    layer.alpha_composite(rgba, (paste_x, paste_y))

    return Image.alpha_composite(canvas.convert("RGBA"), layer).convert("RGB")


def replace_text_professional(
    img: Image.Image,
    x: int,
    y: int,
    w: int,
    h: int,
    new_text: str,
    original_text: str,
    font_size: int,
    text_color: str,
    direction: str,
    font_weight: str
):
    style = _source_render_style(
        img, x, y, w, h, original_text, font_weight
    )
    try:
        print("STYLE_MATCH", {
            "original": original_text,
            "new": new_text,
            "font": os.path.basename(style.get("font_path") or ""),
            "font_size": style.get("font_size"),
            "line_height": style.get("line_height"),
            "width_scale": round(float(style.get("width_scale", 1.0)), 3),
            "density": round(float(style.get("target_density", 0.0)), 3),
            "stroke": round(float(style.get("target_stroke", 0.0)), 3),
            "color": style.get("text_color"),
            "refs": style.get("reference_count"),
            "anchor": style.get("anchor_mode"),
        }, flush=True)
    except Exception:
        pass
    cleaned = erase_text_area(img, x, y, w, h)
    result = draw_replacement(
        cleaned,
        x, y, w, h,
        new_text,
        original_text,
        font_size,
        text_color,
        direction,
        font_weight,
        source_style=style
    )
    return result, style


@app.get("/health")
def health():
    return {
        "ok": True,
        "service": "UNB Staged AI Editor",
        "stages": 4,
        "features": ["ocr_detect", "ocr_replace"],
        "ocr_engine": "Tesseract Arabic+English word-level lite mode",
        "arabic_render": "RAQM + character-count sizing + box-safe RTL anchoring",
        "same_text_mode": "preserve-original-pixels",
        "background_cleanup": "glyph-mask inpaint + local-plane blend",
        "text_render": "same-line point-size calibration + core-ink color + stroke transfer",
        "style_match_version": 6,
        "nonblocking_jobs": True,
        "lazy_ai_imports": True,
        "ocr_selection": "word-level boxes",
        "server_mode": "ocr-lite",
        "heavy_ai_models": False
    }

@app.post("/api/stage1/cut-first")
async def stage1():
    raise HTTPException(503, "هذه النسخة مخصصة لتحرير النصوص فقط")

@app.post("/api/stage2/restore-background")
async def stage2():
    raise HTTPException(503, "هذه النسخة مخصصة لتحرير النصوص فقط")

@app.post("/api/stage3/cut-second")
async def stage3():
    raise HTTPException(503, "هذه النسخة مخصصة لتحرير النصوص فقط")

@app.post("/api/stage4/composite")
async def stage4():
    raise HTTPException(503, "هذه النسخة مخصصة لتحرير النصوص فقط")

@app.post("/api/ocr/detect")
async def ocr_detect(image: UploadFile = File(...)):
    data = await image.read()
    try:
        img = ensure_image(data)
        print(f"OCR detect started: {img.width}x{img.height}", flush=True)
        # EasyOCR/PyTorch is more stable in the main Uvicorn thread on this
        # small CPU server. Running it through asyncio.to_thread can stall
        # inside quantized RNN inference.
        blocks = detect_ocr_blocks(img)
        print(f"OCR detect finished: {len(blocks)} blocks", flush=True)
        return {
            "ok": True,
            "image_width": img.width,
            "image_height": img.height,
            "count": len(blocks),
            "blocks": blocks
        }
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(500, str(e))

@app.post("/api/ocr/replace")
async def ocr_replace(
    image: UploadFile = File(...),
    x: int = Form(...),
    y: int = Form(...),
    w: int = Form(...),
    h: int = Form(...),
    new_text: str = Form(...),
    original_text: str = Form(""),
    font_size: int = Form(24),
    text_color: str = Form("#000000"),
    direction: str = Form("auto"),
    language: str = Form("unknown"),
    font_weight: str = Form("normal")
):
    data = await image.read()

    try:
        img = ensure_image(data)

        if w <= 0 or h <= 0:
            raise HTTPException(400, "أبعاد النص غير صحيحة")

        if direction == "auto":
            direction = "rtl" if has_arabic(new_text) else "ltr"

        # If the user replaces a word with the exact same word, preserve the
        # original pixels. This is the only way to be literally identical in
        # font, anti-aliasing, blur, color and compression without knowing the
        # source font.
        if original_text and normalize_compare_text(new_text) == normalize_compare_text(original_text):
            return Response(
                png_bytes(img),
                media_type="image/png",
                headers={
                    "Cache-Control": "no-store",
                    "X-UNB-Replace-Mode": "original-pixels"
                }
            )

        result, style = await asyncio.to_thread(
            replace_text_professional,
            img,
            x, y, w, h,
            new_text,
            original_text,
            font_size,
            text_color,
            direction,
            font_weight
        )

        return Response(
            png_bytes(result),
            media_type="image/png",
            headers={
                "Cache-Control": "no-store",
                "X-UNB-Render-Mode": "source-style-match",
                "X-UNB-Font-Score": str(style.get("font_score", "na"))
            }
        )
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(500, str(e))
