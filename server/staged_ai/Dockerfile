FROM python:3.11-slim

ENV DEBIAN_FRONTEND=noninteractive
ENV OMP_NUM_THREADS=2
ENV MKL_NUM_THREADS=2

RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1 \
    libglib2.0-0 \
    libgomp1 \
    curl \
    tesseract-ocr \
    tesseract-ocr-eng \
    tesseract-ocr-ara \
    fonts-dejavu-core \
    fonts-noto-core \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN pip install --no-cache-dir --upgrade pip setuptools wheel
RUN pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch torchvision
RUN pip install --no-cache-dir \
    fastapi \
    uvicorn \
    python-multipart \
    pillow \
    "numpy<2" \
    opencv-python-headless \
    onnxruntime \
    rembg \
    simple-lama-inpainting \
    pytesseract \
    arabic-reshaper \
    python-bidi \\
    easyocr

COPY app.py /app/app.py

CMD ["uvicorn","app:app","--host","0.0.0.0","--port","8000","--workers","1"]
