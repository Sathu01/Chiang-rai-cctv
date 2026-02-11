# ใช้ Python 3.10 slim เป็น base image
FROM python:3.10-slim

# ตั้งค่า working directory
WORKDIR /app

# ติดตั้ง system dependencies สำหรับ OpenCV และ OCR
RUN apt-get update && apt-get install -y \
    libgl1 \
    libglib2.0-0 \
    libsm6 \
    libxext6 \
    libxrender-dev \
    libgomp1 \
    wget \
    libgeos-dev \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements file
COPY requirements.txt .

# ติดตั้ง Python dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Copy .env file (ถ้ามี)
COPY .env* ./

# Copy application code
COPY app/ ./app/
COPY models/ ./models/

# สร้าง directories สำหรับ input/output
RUN mkdir -p input output/images output/json

# Expose port
EXPOSE 8000

# Set Python path
ENV PYTHONPATH=/app

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8000/ || exit 1

# Run the application
CMD ["python", "app/main.py"]