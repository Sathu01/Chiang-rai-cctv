from fastapi import FastAPI, HTTPException, File, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel
from pathlib import Path
import shutil
from typing import Optional, List
import uvicorn
import logging

from detector import LicensePlateDetector
from config import settings
from kafka_consumer import consume

# Initialize logger
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)


# ===== FastAPI App =====
app = FastAPI(
    title="YOLOv11 License Plate Detection + Gemini OCR",
    description="API สำหรับตรวจจับป้ายทะเบียนรถด้วย YOLOv11 และอ่านตัวอักษรด้วย Gemini Vision API",
    version="2.0.0"
)


# ===== Initialize Detector =====
logger.info("\n" + "="*70)
logger.info("🚀 Initializing License Plate Detector")

try:
    detector = LicensePlateDetector()
    logger.info("✅ Detector initialized successfully")
except Exception as e:
    logger.error(f"❌ Failed to initialize detector: {e}")
    logger.error("   Please check:")
    logger.error("   1. Model file exists at models/yolov11_license_plate.pt")
    logger.error("   2. Gemini API key is set in .env")
    raise

logger.info("="*70 + "\n")


# ===== Pydantic Models =====
class DetectionRequest(BaseModel):
    """Request model for detection from path"""
    image_path: str
    save_image: bool = True
    save_json: bool = True
    use_ocr: bool = True


class DetectionResponse(BaseModel):
    """Response model"""
    status: str
    message: str
    data: Optional[dict] = None


# ===== API Endpoints =====

@app.get("/")
async def root():
    """
    Health check endpoint
    
    Returns:
        API status และ configuration
    """
    return {
        "status": "running",
        "service": "YOLOv11 License Plate Detection + Gemini OCR",
        "version": "2.0.0",
        "model": {
            "yolo": settings.MODEL_PATH,
            "ocr": settings.OCR_ENGINE
        },
        "endpoints": {
            "detect_path": "/detect/path",
            "detect_upload": "/detect/upload",
            "detect_batch": "/detect/batch",
            "get_image": "/result/image/{filename}",
            "get_json": "/result/json/{filename}"
        }
    }


@app.post("/detect/path", response_model=DetectionResponse)
async def detect_from_path(request: DetectionRequest):
    """
    ตรวจจับป้ายทะเบียนจาก path ของภาพ
    Example:
        ```json
        {
            "image_path": "input/car1.jpg",
            "save_image": true,
            "save_json": true,
            "use_ocr": true
        }
        ```
    """
    try:
        # detect
        result = detector.detect(
            image_path=request.image_path,
            save_image=request.save_image,
            save_json=request.save_json,
            use_ocr=request.use_ocr
        )
        
        # create summary message
        total_plates = result['total_plates']
        
        # pull out OCR texts
        ocr_texts = []
        for det in result.get('detections', []):
            if 'ocr' in det and det['ocr'].get('text'):
                ocr_texts.append(det['ocr']['text'])
        
        message = f"ตรวจพบป้ายทะเบียน {total_plates} ป้าย"
        if ocr_texts:
            message += f" (อ่านได้: {', '.join(ocr_texts)})"
        
        return DetectionResponse(
            status="success",
            message=message,
            data=result
        )
    
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"เกิดข้อผิดพลาด: {str(e)}"
        )


@app.post("/detect/upload")
async def detect_from_upload(
    file: UploadFile = File(...),
    save_image: bool = True,
    save_json: bool = True,
    use_ocr: bool = True
):
    """
    ตรวจจับป้ายทะเบียนจากการอัปโหลดไฟล์
    
    Args:
        file: ไฟล์ภาพที่อัปโหลด (jpg, png, etc.)
        save_image: บันทึกภาพ
        save_json: บันทึก JSON
        use_ocr: ใช้ OCR
    
    Returns:
        ผลลัพธ์การตรวจจับ
    """
    try:
        # save uploaded file to temp path
        temp_path = f"input/uploaded_{file.filename}"
        Path("input").mkdir(exist_ok=True)
        
        with open(temp_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)
        
        # detect
        result = detector.detect(
            temp_path,
            save_image=save_image,
            save_json=save_json,
            use_ocr=use_ocr
        )
        
        # create summary message
        total_plates = result['total_plates']
        ocr_texts = [
            d.get('ocr', {}).get('text', '')
            for d in result.get('detections', [])
        ]
        readable = [t for t in ocr_texts if t]
        
        message = f"ตรวจพบป้ายทะเบียน {total_plates} ป้าย"
        if readable:
            message += f" (อ่านได้: {', '.join(readable)})"
        
        return DetectionResponse(
            status="success",
            message=message,
            data=result
        )
    
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"เกิดข้อผิดพลาด: {str(e)}"
        )


@app.post("/detect/batch")
async def detect_batch(
    image_paths: List[str],
    save_image: bool = True,
    save_json: bool = True
):
    """
    ตรวจจับหลายภาพพร้อมกัน
    
    Example:
        ```json
        [
            "input/car1.jpg",
            "input/car2.jpg",
            "input/car3.jpg"
        ]
        ```
    """
    try:
        # test existing paths
        existing_paths = [p for p in image_paths if Path(p).exists()]
        
        if not existing_paths:
            raise HTTPException(
                status_code=404,
                detail="ไม่พบไฟล์ภาพที่ระบุ"
            )
        
        # Batch detection
        results = detector.batch_detect(
            existing_paths,
            save_image=save_image,
            save_json=save_json
        )
        
        # summarize results
        total_images = len(results)
        total_plates = sum(
            r.get('total_plates', 0)
            for r in results
            if 'total_plates' in r
        )
        
        successful = sum(1 for r in results if 'error' not in r)
        failed = total_images - successful
        
        return DetectionResponse(
            status="success",
            message=(
                f"Processed {total_images} images: "
                f"Successful {successful}, Failed {failed}, "
                f"Total plates detected {total_plates}"
            ),
            data={
                "summary": {
                    "total_images": total_images,
                    "successful": successful,
                    "failed": failed,
                    "total_plates": total_plates
                },
                "results": results
            }
        )
    
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"เกิดข้อผิดพลาด: {str(e)}"
        )


@app.get("/result/image/{filename}")
async def get_result_image(filename: str):
    """
    ดึงภาพผลลัพธ์
    
    Args:
        filename: ชื่อไฟล์ภาพ (จาก output_image_path)
    
    Returns:
        Image file
    """
    file_path = Path(settings.OUTPUT_IMAGE_DIR) / filename
    
    if not file_path.exists():
        raise HTTPException(
            status_code=404,
            detail=f"ไม่พบไฟล์: {filename}"
        )
    
    return FileResponse(file_path)


@app.get("/result/json/{filename}")
async def get_result_json(filename: str):
    """
    ดึงผลลัพธ์ JSON
    
    Args:
        filename: ชื่อไฟล์ JSON (จาก output_json_path)
    
    Returns:
        JSON file
    """
    file_path = Path(settings.OUTPUT_JSON_DIR) / filename
    
    if not file_path.exists():
        raise HTTPException(
            status_code=404,
            detail=f"ไม่พบไฟล์: {filename}"
        )
    
    return FileResponse(file_path)

# ===== Run Server =====
if __name__ == "__main__":
    import asyncio

    try:
        asyncio.run(consume(detector))
    except KeyboardInterrupt:
        logging.info("Application terminated by user.")
    finally:
        logging.info("Shutting down application...")

    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8000,
        reload=True
    )