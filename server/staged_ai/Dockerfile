FROM python:3.11-slim

ENV DEBIAN_FRONTEND=noninteractive
ENV OMP_NUM_THREADS=2
ENV MKL_NUM_THREADS=2
ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    tesseract-ocr \
    tesseract-ocr-eng \
    tesseract-ocr-ara \
    fonts-dejavu-core \
    fonts-noto-core \
    libraqm0 \
    libharfbuzz0b \
    libfribidi0 \
    libgl1 \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN pip install --no-cache-dir --upgrade pip setuptools wheel && \
    pip install --no-cache-dir \
      fastapi \
      uvicorn \
      python-multipart \
      pillow \
      "numpy<2" \
      opencv-python-headless \
      pytesseract \
      arabic-reshaper \
      python-bidi

COPY app.py /app/app.py

CMD ["uvicorn","app:app","--host","0.0.0.0","--port","8000","--workers","1"]
