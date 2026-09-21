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
    global _session
    if _session is None:
        from rembg import new_session
        _session = new_session("u2net_human_seg")
    return _session

def get_lama():
    global _lama
    if _lama is None:
        from simple_lama_inpainting import SimpleLama
        _lama = SimpleLama()
    return _lama

def get_easy_ocr():
    global _easy_ocr
    if _easy_ocr is None:
        import torch
        import easyocr

        # Keep PyTorch predictable on the small CPU server. EasyOCR's default
        # dynamic quantization was stalling inside the quantized RNN path.
        torch.set_num_threads(2)
        try:
            torch.set_num_interop_threads(1)
        except RuntimeError:
            pass

        print("EasyOCR reader loading (CPU, quantize=False)...", flush=True)
        _easy_ocr = easyocr.Reader(
            ["ar", "en"],
            gpu=False,
            quantize=False,
            model_storage_directory="/root/.EasyOCR/model",
            user_network_directory="/root/.EasyOCR/user_network",
            download_enabled=True,
            verbose=False
        )
        print("EasyOCR reader ready.", flush=True)
    return _easy_ocr

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
        config="--psm 6 -c preserve_interword_spaces=1",
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

        # Keep each OCR WORD as its own selectable block.
        # Do not merge words into a full line.
        if conf < 20:
            continue

        x = int(data["left"][i])
        y = int(data["top"][i])
        w = max(1, int(data["width"][i]))
        h = max(1, int(data["height"][i]))

        # Small word-local padding only. This keeps replacement/cleanup away
        # from neighboring words on the same line.
        pad_x = max(1, int(round(w * 0.015)))
        pad_y = max(1, int(round(h * 0.04)))
        x1 = max(0, x - pad_x)
        y1 = max(0, y - pad_y)
        x2 = min(img.width, x + w + pad_x)
        y2 = min(img.height, y + h + pad_y)
        bw = max(1, x2 - x1)
        bh = max(1, y2 - y1)

        language = detect_language(text)
        direction = "rtl" if has_arabic(text) else "ltr"
        number_type = detect_number_type(text)

        # Style is measured independently for this exact word.
        text_color, bg_color, font_weight = color_info(img, x, y, w, h)
        font_size = max(8, int(round(h * 0.98)))

        blocks.append({
            "id": "",
            "text": text,
            "language": language,
            "number_type": number_type,
            "direction": direction,
            "bbox": {"x": x1, "y": y1, "w": bw, "h": bh},
            "glyph_bbox": {"x": x, "y": y, "w": w, "h": h},
            "font_size": font_size,
            "font_weight": font_weight,
            "text_color": text_color,
            "bg_color": bg_color,
            "confidence": round(max(0.0, min(1.0, conf / 100.0)), 3),
            "engine": "tesseract-word",
            "block_num": int(data["block_num"][i]),
            "par_num": int(data["par_num"][i]),
            "line_num": int(data["line_num"][i]),
            "word_num": int(data["word_num"][i])
        })

    # Visual reading order. Arabic words on the same line are returned
    # individually; their boxes remain independent and tappable.
    blocks.sort(key=lambda b: (
        b["bbox"]["y"],
        b["line_num"],
        -b["bbox"]["x"] if b["direction"] == "rtl" else b["bbox"]["x"]
    ))

    for idx, block in enumerate(blocks, 1):
        block["id"] = f"blk_{idx:03d}"

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

def erase_text_area(img: Image.Image, x: int, y: int, w: int, h: int):
    rgb = np.array(img.convert("RGB"))
    H, W = rgb.shape[:2]

    # Tiny horizontal padding avoids touching neighboring Arabic words.
    pad_x = max(1, int(round(w * 0.01)))
    pad_y = max(2, int(round(h * 0.10)))

    x1 = max(0, x - pad_x)
    y1 = max(0, y - pad_y)
    x2 = min(W, x + w + pad_x)
    y2 = min(H, y + h + pad_y)

    if x2 <= x1 or y2 <= y1:
        return img.copy()

    fill, residual = _smooth_background_fill(rgb, x1, y1, x2, y2)

    # Receipts/documents usually have a smooth or gently graded background.
    # Plane reconstruction prevents the dirty halo that Telea creates there.
    if fill is not None and residual <= 18.0:
        restored = rgb.copy()
        restored[y1:y2, x1:x2] = fill

        mask = np.zeros((H, W), dtype=np.uint8)
        cv2.rectangle(mask, (x1, y1), (x2 - 1, y2 - 1), 255, -1)
        soft = cv2.GaussianBlur(mask, (0, 0), sigmaX=1.2).astype(np.float32) / 255.0
        soft = soft[..., None]

        blended = (
            restored.astype(np.float32) * soft +
            rgb.astype(np.float32) * (1.0 - soft)
        )
        return Image.fromarray(np.clip(blended, 0, 255).astype(np.uint8))

    # Complex/textured area: fall back to OpenCV inpainting.
    bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
    mask = np.zeros((H, W), dtype=np.uint8)
    cv2.rectangle(mask, (x1, y1), (x2 - 1, y2 - 1), 255, -1)
    restored = cv2.inpaint(bgr, mask, 3, cv2.INPAINT_TELEA)
    return Image.fromarray(cv2.cvtColor(restored, cv2.COLOR_BGR2RGB))

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
    font_weight: str
):
    # new_text is rendered exactly as typed by the user.
    canvas = img.copy()
    draw = ImageDraw.Draw(canvas)

    # Estimate local background/contrast so the replacement blends with
    # scanned or slightly blurry documents rather than looking digitally sharp.
    local_bg = estimate_background_rgb(img, x, y, w, h)

    is_ar = has_arabic(new_text)
    font_path = pick_font(font_weight == "bold", is_ar)

    old_count = count_text_units(original_text)
    new_count = count_text_units(new_text)

    requested_size = max(8, int(font_size))

    # Character-count ratio gives a good first estimate when replacing a short
    # word with a much longer one. Actual pixel width below is still decisive.
    ratio = old_count / float(max(1, new_count))
    ratio_scale = min(1.0, max(0.70, ratio ** 0.5))
    start_size = max(8, int(round(requested_size * ratio_scale)))

    chosen_font = None
    chosen_raqm = False
    chosen_box = None
    tw = th = 1

    # Always fit inside the original OCR box. This prevents the replacement
    # from running into the adjacent Arabic word.
    for test_size in range(start_size, 7, -1):
        font, use_raqm = make_font(font_path, test_size, is_ar)
        box, test_w, test_h = text_metrics(
            draw,
            new_text,
            font,
            is_ar,
            use_raqm
        )

        chosen_font = font
        chosen_raqm = use_raqm
        chosen_box = box
        tw = test_w
        th = test_h

        if test_w <= max(1, int(w * 0.98)) and test_h <= max(1, int(h * 1.12)):
            break

    if chosen_font is None:
        chosen_font, chosen_raqm = make_font(font_path, 8, is_ar)
        chosen_box, tw, th = text_metrics(
            draw,
            new_text,
            chosen_font,
            is_ar,
            chosen_raqm
        )

    # Preserve the original direction and anchor. Arabic is anchored to the
    # right edge of the selected word box.
    if direction == "rtl" or is_ar:
        tx = int(round(x + w - tw))
    else:
        tx = int(round(x))

    ty = int(round(y + max(0, (h - th) / 2.0) - chosen_box[1]))

    tx = max(0, min(max(0, canvas.width - tw), tx))
    ty = max(0, min(max(0, canvas.height - th), ty))

    color = parse_hex_color(text_color)

    # Draw onto a transparent layer so we can soften it to match source quality.
    text_layer = Image.new("RGBA", canvas.size, (0,0,0,0))
    text_draw = ImageDraw.Draw(text_layer)

    if is_ar and chosen_raqm:
        text_draw.text(
            (tx + tw, ty),
            new_text,
            font=chosen_font,
            fill=(*color, 255),
            anchor="ra",
            direction="rtl",
            language="ar"
        )
    else:
        render_text = shape_text(new_text) if is_ar else new_text
        text_draw.text(
            (tx, ty),
            render_text,
            font=chosen_font,
            fill=(*color, 255)
        )

    # Slight optical softening; scanned/phone images rarely contain perfectly
    # sharp digital glyph edges.
    text_layer = text_layer.filter(ImageFilter.GaussianBlur(radius=0.35))
    canvas = Image.alpha_composite(canvas.convert("RGBA"), text_layer).convert("RGB")

    return canvas


@app.get("/health")
def health():
    return {
        "ok": True,
        "service": "UNB Staged AI Editor",
        "stages": 4,
        "features": ["cutout", "inpaint", "composite", "ocr_detect", "ocr_replace"],
        "ocr_engine": "Tesseract Arabic+English word-level stable mode",
        "arabic_render": "RAQM + character-count sizing + box-safe RTL anchoring",
        "same_text_mode": "preserve-original-pixels",
        "background_cleanup": "smooth-plane + Telea fallback",
        "nonblocking_jobs": True,
        "lazy_ai_imports": True
    }

@app.post("/api/stage1/cut-first")
async def stage1(file: UploadFile = File(...)):
    data = await file.read()
    try:
        result = await asyncio.to_thread(cutout_bytes, data)
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

        restored = await asyncio.to_thread(run_lama, original, mask)
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
        result = await asyncio.to_thread(cutout_bytes, data)
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
        scale = min(target_w / sw, target_h / sh) * 0.98
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

        cleaned = await asyncio.to_thread(erase_text_area, img, x, y, w, h)
        result = await asyncio.to_thread(
            draw_replacement,
            cleaned,
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
            headers={"Cache-Control": "no-store"}
        )
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(500, str(e))
