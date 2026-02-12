import cv2
import json
from pathlib import Path
from ultralytics import YOLO
from datetime import datetime
import numpy as np
from typing import Optional, Dict, List
import time
import logging
from PIL import Image, ImageDraw, ImageFont

# Import Gemini OCR
try:
    from gemini import GeminiOCR
    GEMINI_AVAILABLE = True
except ImportError:
    GEMINI_AVAILABLE = False
    print("⚠️ Gemini OCR not available")

from config import settings

logger = logging.getLogger(__name__)

class LicensePlateDetector:
    """
    ตัวตรวจจับป้ายทะเบียนและอ่านตัวอักษร
    
    Components:
    1. YOLO Model - ตรวจจับตำแหน่งป้าย (จาก morsetechlab)
    2. Gemini OCR - อ่านข้อความบนป้าย
    
    Usage:
        detector = LicensePlateDetector()
        result = detector.detect("input/car.jpg")
        print(result['detections'][0]['ocr']['text'])
    """
    
    def __init__(
        self,
        model_path: Optional[str] = None,
        conf_threshold: Optional[float] = None,
        use_gemini: bool = True,
        gemini_config: Optional[Dict] = None
    ):
        """
        Args:
            model_path: path ของ YOLO model (default จาก settings)
            conf_threshold: confidence threshold สำหรับ detection
            use_gemini: ใช้ Gemini OCR หรือไม่
            gemini_config: custom config สำหรับ Gemini
        """
        # ใช้ค่าจาก settings ถ้าไม่ได้ระบุ
        model_path_str = model_path or settings.MODEL_PATH
        # Resolve relative path from project root
        if not Path(model_path_str).is_absolute():
            project_root = Path(__file__).parent.parent
            self.model_path = str(project_root / model_path_str)
        else:
            self.model_path = model_path_str
        self.conf_threshold = conf_threshold or settings.CONFIDENCE_THRESHOLD
        self.use_gemini = use_gemini
        
        # โหลด YOLO model
        logger.info("🔄 Loading YOLOv11 License Plate Model...")
        logger.info(f"   Path: {self.model_path}")
        
        if not Path(self.model_path).exists():
            raise FileNotFoundError(
                f"❌ ไม่พบ model file: {self.model_path}\n"
                f"   กรุณารัน: bash download_model.sh\n"
            )

        self.model = YOLO(self.model_path)
        logger.info("✅ YOLO model loaded successfully")

        # Initialize Gemini OCR
        self.ocr = None
        if self.use_gemini:
            self._initialize_gemini(gemini_config)

        # สร้าง output directories
        settings.create_output_dirs()
        
        # 🆕 โหลดฟอนต์สำหรับภาษาไทย
        self._load_thai_font()

        logger.info(f"✅ Detector ready (Gemini OCR: {'enabled' if self.use_gemini else 'disabled'})")

    def _load_thai_font(self):
        """
        🆕 โหลดฟอนต์ภาษาไทย
        
        ลำดับการหาฟอนต์:
        1. /usr/share/fonts/truetype/thai/ (Linux)
        2. C:/Windows/Fonts/ (Windows)
        3. /System/Library/Fonts/ (macOS)
        4. DejaVuSans (fallback ถ้าไม่มีฟอนต์ไทย)
        """
        self.thai_font = None
        self.thai_font_size = 20
        
        # รายการฟอนต์ไทยที่จะลองหา
        thai_fonts = [
            # Linux
            "/usr/share/fonts/truetype/thai/Sarabun-Regular.ttf",
            "/usr/share/fonts/truetype/thai/Garuda.ttf",
            "/usr/share/fonts/truetype/thai/Loma.ttf",
            "/usr/share/fonts/truetype/thai/TlwgTypo.ttf",
            # Windows
            "C:/Windows/Fonts/THSarabunNew.ttf",
            "C:/Windows/Fonts/Tahoma.ttf",
            # macOS
            "/System/Library/Fonts/Thonburi.ttc",
            # Fallback
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        ]
        
        # ลองหาฟอนต์ที่มีอยู่
        for font_path in thai_fonts:
            if Path(font_path).exists():
                try:
                    self.thai_font = ImageFont.truetype(font_path, self.thai_font_size)
                    logger.info(f"✅ Loaded Thai font: {font_path}")
                    return
                except Exception as e:
                    logger.debug(f"Failed to load font {font_path}: {e}")
                    continue
        
        # ถ้าไม่เจอฟอนต์เลย ใช้ default font
        logger.warning("⚠️ No Thai font found, using default font (may not support Thai)")
        logger.warning("   To fix: install Thai fonts:")
        logger.warning("   Ubuntu: sudo apt-get install fonts-thai-tlwg")
        logger.warning("   Debian: sudo apt-get install fonts-tlwg-sarabun")
        self.thai_font = ImageFont.load_default()

    def _initialize_gemini(self, config: Optional[Dict] = None):
        """
        Initialize Gemini Vision API
        Args:
            config: Custom configuration
        """
        if not GEMINI_AVAILABLE:
            logger.error("❌ Gemini SDK not installed: pip install google-generativeai")
            self.use_gemini = False
            return

        # ดึง config
        gemini_config = config or settings.get_ocr_config()
        api_key = gemini_config.get("api_key") or settings.GEMINI_API_KEY
        if not api_key:
            logger.error("❌ Gemini API key not found")
            logger.error("   Set GEMINI_API_KEY in .env file")
            self.use_gemini = False
            return

        try:
            # เพิ่ม use_image_url parameter
            self.ocr = GeminiOCR(
                api_key=api_key,
                model_name=gemini_config.get("model"),
                temperature=gemini_config.get("temperature", 0.1),
                max_retries=gemini_config.get("max_retries", 3),
                timeout=gemini_config.get("timeout", 30),
                use_image_url=gemini_config.get("use_image_url", True)
            )
            logger.info("✅ Gemini OCR initialized")
        except Exception as e:
            logger.error(f"❌ Failed to initialize Gemini: {e}")
            self.use_gemini = False

    def detect(
        self,
        image_path: str,
        save_image: bool = True,
        save_json: bool = True,
        use_ocr: Optional[bool] = None
    ) -> Dict:
        """
        ตรวจจับป้ายทะเบียนในภาพและอ่านข้อความ (ถ้าเปิดใช้งาน OCR)
        
        Args:
            image_path: path ของภาพที่ต้องการตรวจจับ
            save_image: บันทึกภาพที่มีการ annotate ผลลัพธ์หรือไม่
            save_json: บันทึกผลลัพธ์เป็นไฟล์ JSON หรือไม่
            use_ocr: ใช้ OCR หรือไม่ (ถ้าไม่ได้ระบุ จะใช้ค่าจากการตั้งค่าเริ่มต้น)
        
        Returns:
            dict ของผลลัพธ์การตรวจจับ
        """
        # ตรวจสอบว่าจะใช้ OCR หรือไม่
        should_use_ocr = use_ocr if use_ocr is not None else self.use_gemini
        
        # Validation
        if not Path(image_path).exists():
            raise FileNotFoundError(f"ไม่พบไฟล์: {image_path}")
        
        # อ่านภาพ
        image = cv2.imread(image_path)
        if image is None:
            raise ValueError(f"ไม่สามารถอ่านภาพได้: {image_path}")
        
        logger.info(f"🔍 Processing: {Path(image_path).name}")
        logger.info(f"   Image size: {image.shape[1]}x{image.shape[0]}")
        
        # ===== ขั้นตอนที่ 1: YOLO Detection =====
        logger.info(f"   ⚡ Running YOLO detection...")
        start_time = time.time()
        
        results = self.model.predict(
            source=image,
            conf=self.conf_threshold,
            save=False,
            verbose=False
        )
        
        detection_time = time.time() - start_time
        logger.info(f"   ✅ Detection complete ({detection_time:.2f}s)")
        
        # ===== ขั้นตอนที่ 2: Process Results + OCR =====
        detections = self._process_detections(
            results[0], 
            image, 
            should_use_ocr,
            image_path
        )
        
        logger.info(f"   📊 Found {len(detections)} license plate(s)")
        
        # แสดงผล OCR results
        for i, det in enumerate(detections, 1):
            if 'ocr' in det and det['ocr'].get('text'):
                ocr = det['ocr']
                logger.info(f"      Plate {i}: '{ocr['text']}' (conf: {ocr['confidence']:.2%})")
        
        # ===== ขั้นตอนที่ 3: Save Results =====
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = Path(image_path).stem
        
        output_data = {
            "input_path": image_path,
            "timestamp": timestamp,
            "detections": detections,
            "total_plates": len(detections),
            "processing_time": {
                "detection": detection_time,
                "total": time.time() - start_time
            },
            "model": {
                "yolo": self.model_path,
                "ocr": "gemini" if should_use_ocr else "none"
            }
        }
        
        # บันทึกภาพ
        if save_image and len(detections) > 0:
            annotated_image = self._draw_annotations(image.copy(), detections)
            image_output_path = f"{settings.OUTPUT_IMAGE_DIR}/{filename}_{timestamp}.jpg"
            cv2.imwrite(image_output_path, annotated_image)
            output_data["output_image_path"] = image_output_path
            logger.info(f"   💾 Saved image: {image_output_path}")
        
        # บันทึก JSON
        if save_json:
            json_output_path = f"{settings.OUTPUT_JSON_DIR}/{filename}_{timestamp}.json"
            with open(json_output_path, 'w', encoding='utf-8') as f:
                json.dump(output_data, f, indent=2, ensure_ascii=False)
            output_data["output_json_path"] = json_output_path
            logger.info(f"   💾 Saved JSON: {json_output_path}")
        
        logger.info(f"   ✅ Processing complete\n")

        # Send result to Firestore
        try:
            from app.firestore_repository import FirestoreRepository
            repo = FirestoreRepository()
            repo.save_license_plate(output_data)
            logger.info("   🚀 Sent detection result to Firestore.")
        except Exception as e:
            logger.error(f"   ❌ Failed to send result to Firestore: {e}")

        return output_data
    
    def _process_detections(
        self,
        yolo_result,
        image: np.ndarray,
        use_ocr: bool,
        image_path: str
    ) -> List[Dict]:
        """
        ประมวลผลผลลัพธ์จาก YOLO และอ่าน OCR
        
        Args:
            yolo_result: ผลลัพธ์จาก YOLO
            image: ภาพต้นฉบับ
            use_ocr: ใช้ OCR หรือไม่
            image_path: path ของภาพต้นฉบับ
        
        Returns:
            List of detection dictionaries
        """
        detections = []
        
        if yolo_result.boxes is None or len(yolo_result.boxes) == 0:
            logger.warning(f"   ⚠️  No license plates detected")
            return detections
        
        logger.info(f"   🎯 Processing {len(yolo_result.boxes)} detection(s)...")
        
        for i, box in enumerate(yolo_result.boxes, 1):
            # ดึงข้อมูล bounding box
            x1, y1, x2, y2 = box.xyxy[0].cpu().numpy()
            confidence = float(box.conf[0].cpu().numpy())
            class_id = int(box.cls[0].cpu().numpy())
            class_name = yolo_result.names[class_id]
            
            bbox = {
                "x1": float(x1),
                "y1": float(y1),
                "x2": float(x2),
                "y2": float(y2),
                "width": float(x2 - x1),
                "height": float(y2 - y1)
            }
            
            detection = {
                "detection_id": i,
                "bbox": bbox,
                "confidence": confidence,
                "class_id": class_id,
                "class_name": class_name
            }
            
            # อ่าน OCR ถ้าเปิดใช้งาน
            if use_ocr and self.ocr:
                logger.info(f"🤖 Reading text from plate {i}...")
                ocr_result = self._read_plate_ocr(image, bbox, i, image_path)
                detection["ocr"] = ocr_result
                
                if ocr_result.get('text'):
                    logger.info(f"✅ '{ocr_result['text']}'")
                else:
                    logger.info(f"✗ No text detected")
            
            detections.append(detection)
        
        return detections
    
    def _read_plate_ocr(
        self,
        image: np.ndarray,
        bbox: Dict[str, float],
        detection_id: int,
        image_path: str
    ) -> Dict:
        """
        อ่านตัวอักษรจากป้ายทะเบียนด้วย Gemini
        
        Args:
            image: ภาพต้นฉบับ
            bbox: bounding box ของป้าย
            detection_id: ID ของการตรวจจับ
            image_path: path ของภาพต้นฉบับ
        
        Returns:
            OCR result dictionary
        """
        # Crop ภาพป้าย
        x1, y1 = int(bbox["x1"]), int(bbox["y1"])
        x2, y2 = int(bbox["x2"]), int(bbox["y2"])
        
        # เพิ่ม padding เล็กน้อย (5 pixels)
        padding = 5
        x1 = max(0, x1 - padding)
        y1 = max(0, y1 - padding)
        x2 = min(image.shape[1], x2 + padding)
        y2 = min(image.shape[0], y2 + padding)
        
        plate_img = image[y1:y2, x1:x2].copy()
        
        # ตรวจสอบว่า crop ได้หรือไม่
        if plate_img.size == 0:
            return {
                "text": "",
                "confidence": 0.0,
                "engine": "gemini",
                "error": "Failed to crop image"
            }
        
        # เรียก Gemini OCR
        try:
            result = self.ocr.read_text(
                plate_img, 
                language="both",
                detection_id=detection_id,
                original_filename=Path(image_path).name
            )
            
            return {
                "text": result.get("text", ""),
                "confidence": result.get("confidence", 0.0),
                "engine": "gemini",
                "model": result.get("model", "unknown"),
                "processing_time": result.get("processing_time", 0),
                "raw_response": result.get("raw_response", ""),
                "mode": result.get("mode", "unknown"),
                "image_path": result.get("image_path", "")
            }
        
        except Exception as e:
            logger.warning(f"⚠️ OCR error: {e}")
            return {
                "text": "",
                "confidence": 0.0,
                "engine": "gemini",
                "error": str(e)
            }
    
    def _draw_annotations(
        self,
        image: np.ndarray,
        detections: List[Dict]
    ) -> np.ndarray:
        """
        🆕 วาด bounding box และข้อความ OCR บนภาพ (รองรับภาษาไทย)
        
        Args:
            image: ภาพต้นฉบับ
            detections: ผลลัพธ์การตรวจจับ
        
        Returns:
            ภาพที่วาด annotation แล้ว
        """
        # แปลง OpenCV (BGR) เป็น PIL (RGB)
        pil_image = Image.fromarray(cv2.cvtColor(image, cv2.COLOR_BGR2RGB))
        draw = ImageDraw.Draw(pil_image)
        
        for det in detections:
            bbox = det["bbox"]
            x1, y1 = int(bbox["x1"]), int(bbox["y1"])
            x2, y2 = int(bbox["x2"]), int(bbox["y2"])
            
            # สี: เขียวถ้ามี OCR text, เหลืองถ้าไม่มี
            has_text = det.get("ocr", {}).get("text", "")
            color = (0, 255, 0) if has_text else (255, 255, 0)  # RGB
            
            # วาดกรอบ (ใช้ PIL)
            draw.rectangle([(x1, y1), (x2, y2)], outline=color, width=3)
            
            # เตรียม labels
            labels = []
            
            # Label 1: Detection info
            det_conf = det['confidence']
            labels.append(f"Plate: {det_conf:.2%}")
            
            # Label 2: OCR text (ถ้ามี)
            if 'ocr' in det:
                ocr = det['ocr']
                ocr_text = ocr.get('text', '')
                ocr_conf = ocr.get('confidence', 0)
                ocr_mode = ocr.get('mode', 'unknown')
                
                if ocr_text:
                    labels.append(f"Text: {ocr_text}")  # 🆕 ภาษาไทยแสดงได้แล้ว!
                    labels.append(f"OCR: {ocr_conf:.2%} ({ocr_mode})")
                else:
                    labels.append(f"OCR: No text")
            
            # วาด labels ด้วย PIL (รองรับภาษาไทย)
            y_offset = y1
            
            for label in labels:
                # วัดขนาด text
                bbox_text = draw.textbbox((0, 0), label, font=self.thai_font)
                label_w = bbox_text[2] - bbox_text[0]
                label_h = bbox_text[3] - bbox_text[1]
                
                # วาดพื้นหลัง
                draw.rectangle(
                    [(x1, y_offset - label_h - 10), (x1 + label_w + 10, y_offset)],
                    fill=color
                )
                
                # วาดข้อความ (สีดำ)
                draw.text(
                    (x1 + 5, y_offset - label_h - 5),
                    label,
                    fill=(0, 0, 0),
                    font=self.thai_font
                )
                
                y_offset -= (label_h + 15)
        
        # แปลงกลับเป็น OpenCV (RGB -> BGR)
        return cv2.cvtColor(np.array(pil_image), cv2.COLOR_RGB2BGR)
    
    def batch_detect(
        self,
        image_paths: List[str],
        save_image: bool = True,
        save_json: bool = True
    ) -> List[Dict]:
        """
        ตรวจจับหลายภาพพร้อมกัน
        
        Args:
            image_paths: list ของ path ภาพ
            save_image: บันทึกภาพ
            save_json: บันทึก JSON
        
        Returns:
            List of detection results
        """
        logger.info("=" * 70)
        logger.info(f"🚀 Batch Processing: {len(image_paths)} images")
        logger.info("=" * 70)
        
        results = []
        total_plates = 0
        successful = 0
        failed = 0
        
        start_time = time.time()
        
        for i, img_path in enumerate(image_paths, 1):
            logger.info(f"\n[{i}/{len(image_paths)}] Processing: {img_path}")
            
            try:
                result = self.detect(img_path, save_image, save_json)
                results.append(result)
                
                plates_found = result['total_plates']
                total_plates += plates_found
                successful += 1
                
            except Exception as e:
                logger.warning(f"   ❌ Error: {e}")
                results.append({
                    "error": str(e),
                    "path": img_path
                })
                failed += 1
        
        total_time = time.time() - start_time
        logger.info(f"\n✅ Batch processing complete")
        logger.info(f"   Total images: {len(image_paths)}")
        logger.info(f"   Successful: {successful}")
        logger.info(f"   Failed: {failed}")
        logger.info(f"   Total plates detected: {total_plates}")
        logger.info(f"   Total time: {total_time:.2f}s")
        
        return results