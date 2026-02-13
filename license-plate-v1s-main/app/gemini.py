"""
Gemini Vision API OCR Handler - FIXED VERSION
แก้ไข: เพิ่มการจัดการ 503 error ให้ดีขึ้น
"""

from google import genai
import cv2
import numpy as np
from PIL import Image
import time
from typing import Dict, Optional
import re
import logging
from pathlib import Path
import random
import json

logger = logging.getLogger(__name__)


class GeminiOCR:
    
    def __init__(
        self,
        api_key: str,
        model_name: str,
        temperature: float = 0.1,
        max_retries: int = 5,  # 🔧 เพิ่มจาก 3 เป็น 5
        timeout: int = 30,
        use_image_url: bool = True,
        initial_retry_delay: float = 3.0  # 🆕 เพิ่ม: delay เริ่มต้น
    ):
        if not api_key:
            raise ValueError("Gemini API key is required")
        
        # Configure Gemini with new google-genai API
        self.client = genai.Client(api_key=api_key)
        
        self.model_name = model_name
        self.temperature = temperature
        self.max_retries = max_retries
        self.timeout = timeout
        self.use_image_url = use_image_url
        self.initial_retry_delay = initial_retry_delay  # 🆕
        
        # สร้างโฟลเดอร์สำหรับเก็บภาพ crop ถาวร (ถ้าใช้ URL)
        if self.use_image_url:
            # Resolve path from project root (license-plate-v1s-main/)
            project_root = Path(__file__).parent.parent
            self.temp_dir = project_root / "output" / "temp_crops"
            self.temp_dir.mkdir(parents=True, exist_ok=True)
            logger.info(f"✓ Using IMAGE URL mode - crops dir: {self.temp_dir}")
        else:
            self.temp_dir = None
            logger.info(f"✓ Using BASE64 mode")
        
        logger.info(f"✓ Gemini OCR initialized ({model_name})")
    
    def create_prompt(self, language: str = "thai") -> str:
        """สร้าง prompt สำหรับ Gemini เพื่ออ่านเลขป้ายทะเบียนและจังหวัด"""
        base_prompt = """You are an OCR system specialized in reading Thai vehicle license plates from CCTV images.

TASK:
Read and extract the license plate number AND province name from the Thai vehicle license plate in the image.

THAI LICENSE PLATE FORMATS:
- Standard car: กข 1234 with province name (e.g., กรุงเทพมหานคร)
- New format: 1กข 2345 with province name
- Motorcycle: 1กข with province name (e.g., 1กข กรุงเทพมหานคร)
- Public vehicle: 12-3456 or นท-123 with province name

COMMON THAI PROVINCES:
กรุงเทพมหานคร, เชียงใหม่, เชียงราย, ขอนแก่น, นครราชสีมา, ภูเก็ต, สงขลา, อุบลราชธานี, นนทบุรี, ปทุมธานี, สมุทรปราการ, ระยอง, ชลบุรี, นครปฐม, สุราษฎร์ธานี, etc.

RULES (STRICT):
1. Read the license plate number clearly visible on the plate
2. Read the province name (usually at the bottom or top of the plate)
3. If characters are unclear, replace them with "?"
4. If completely unreadable, use "UNREADABLE" for that field
5. Do NOT explain your reasoning
6. Do NOT output anything except JSON

OUTPUT FORMAT (JSON ONLY):
{
  "license_plate_number": "กข 1234",
  "province": "กรุงเทพมหานคร"
}

EXAMPLES:
{
  "license_plate_number": "1กข 2345",
  "province": "เชียงราย"
}

{
  "license_plate_number": "กข ????",
  "province": "กรุงเทพมหานคร"
}

{
  "license_plate_number": "UNREADABLE",
  "province": "UNREADABLE"
}

Allowed Thai characters:
ก ข ฃ ค ฅ ฆ ง จ ฉ ช ซ ฌ ญ ฎ ฏ ฐ ฑ ฒ ณ ด ต ถ ท ธ น บ ป ผ ฝ พ ฟ ภ ม ย ร ล ว ศ ษ ส ห ฬ อ ฮ

REMEMBER: Output ONLY valid JSON, nothing else!
"""
        return base_prompt
    
    def preprocess_image(self, image: np.ndarray) -> Image.Image:
        """แปลง OpenCV image เป็น PIL Image"""
        # BGR -> RGB
        rgb_image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        
        # PIL Image
        pil_image = Image.fromarray(rgb_image)
        
        # ปรับขนาดถ้าภาพใหญ่เกินไป
        max_size = 1024
        if max(pil_image.size) > max_size:
            pil_image.thumbnail((max_size, max_size), Image.Resampling.LANCZOS)
        
        return pil_image
    
    def save_temp_image(
        self, 
        image: np.ndarray,
        detection_id: int,
        original_filename: str
    ) -> str:
        """บันทึกภาพ crop ป้ายทะเบียน """
        timestamp = int(time.time() * 1000)
        filename = f"{Path(original_filename).stem}_plate_{detection_id}_{timestamp}.jpg"
        filepath = self.temp_dir / filename
        
        cv2.imwrite(str(filepath), image)
        logger.debug(f"💾 Saved cropped plate: {filepath}")
        
        return str(filepath)
    
    def _calculate_retry_delay(self, attempt: int, is_overload: bool = False) -> float:
        """
        🆕 คำนวณเวลารอระหว่าง retry ด้วย exponential backoff + jitter
        
        Args:
            attempt: ครั้งที่ retry (0-indexed)
            is_overload: เป็น 503 error หรือไม่
        
        Returns:
            จำนวนวินาทีที่ควรรอ
        """
        if is_overload:
            # สำหรับ 503 error ใช้เวลารอนานขึ้น
            base_delay = self.initial_retry_delay * (2.5 ** attempt)
        else:
            # error อื่นๆ ใช้ exponential backoff ปกติ
            base_delay = self.initial_retry_delay * (2 ** attempt)
        
        # เพิ่ม random jitter 0-25% เพื่อป้องกัน thundering herd
        jitter = random.uniform(0, 0.25 * base_delay)
        delay = base_delay + jitter
        
        # จำกัดไม่ให้รอนานเกิน 60 วินาที
        return min(delay, 60.0)
    
    def _is_overload_error(self, error: Exception) -> bool:
        """
        🆕 ตรวจสอบว่าเป็น error จาก API overload หรือไม่
        """
        error_str = str(error).lower()
        overload_keywords = [
            '503',
            'overloaded',
            'unavailable',
            'resource exhausted',
            'quota exceeded',
            'rate limit'
        ]
        return any(keyword in error_str for keyword in overload_keywords)
    
    def read_text(
        self, 
        image: np.ndarray,
        language: str = "both",
        detection_id: Optional[int] = None,
        original_filename: Optional[str] = None
    ) -> Dict[str, any]:
        """อ่านข้อความจากภาพด้วย Gemini Vision"""
        start_time = time.time()
        
        # เลือกวิธีการส่งภาพตาม mode
        if self.use_image_url:
            if detection_id is None or original_filename is None:
                logger.warning("⚠️ Missing detection_id or original_filename for URL mode")
                return self._fallback_to_base64(image, start_time, language)
            
            try:
                temp_path = self.save_temp_image(image, detection_id, original_filename)
                logger.debug(f"📤 Sending image URL to Gemini: {temp_path}")
                
                prompt = self.create_prompt(language)
                result = self._call_gemini_with_url(temp_path, prompt, start_time)
                
                result["image_path"] = temp_path
                result["mode"] = "url"
                
                return result
                
            except Exception as e:
                logger.warning(f"⚠️ URL mode failed: {e}, falling back to base64 mode")
                return self._fallback_to_base64(image, start_time, language)
        
        else:
            return self._call_gemini_with_base64(image, start_time, language)
    
    def _call_gemini_with_url(
        self,
        image_path: str,
        prompt: str,
        start_time: float
    ) -> Dict:
        """เรียก Gemini API ด้วย image URL"""
        last_error = None
        
        for attempt in range(self.max_retries):
            try:
                pil_image = Image.open(image_path)
                
                # 🆕 เพิ่ม timeout
                response = self.client.models.generate_content(
                    model=self.model_name,
                    contents=[prompt, pil_image],
                    config={
                        "temperature": self.temperature,
                    }
                )
                
                raw_text = response.text.strip()
                parsed_result = self.parse_json_response(raw_text)
                processing_time = time.time() - start_time
                confidence = self.estimate_confidence_from_json(parsed_result, raw_text)
                
                # ✅ สำเร็จ
                logger.info(f"✅ Gemini API success on attempt {attempt + 1}")
                return {
                    "license_plate_number": parsed_result.get("license_plate_number", ""),
                    "province": parsed_result.get("province", ""),
                    "confidence": confidence,
                    "raw_response": raw_text,
                    "processing_time": processing_time,
                    "model": self.model_name,
                    "attempts": attempt + 1,
                    "text": parsed_result.get("license_plate_number", "")  # backward compatibility
                }
            
            except Exception as e:
                last_error = e
                is_overload = self._is_overload_error(e)
                
                logger.warning(
                    f"⚠️ Gemini API attempt {attempt + 1}/{self.max_retries} failed: {e}"
                )
                
                # ถ้ายังมีโอกาส retry อีก
                if attempt < self.max_retries - 1:
                    wait_time = self._calculate_retry_delay(attempt, is_overload)
                    
                    if is_overload:
                        logger.info(
                            f"   ⏳ API overloaded (503), waiting {wait_time:.1f}s before retry..."
                        )
                    else:
                        logger.info(f"   ⏳ Waiting {wait_time:.1f}s before retry...")
                    
                    time.sleep(wait_time)
                else:
                    logger.error(f"❌ All {self.max_retries} retries failed")
        
        # ถ้า retry หมดแล้วยังไม่ได้
        return {
            "license_plate_number": "",
            "province": "",
            "text": "",
            "confidence": 0.0,
            "error": str(last_error),
            "processing_time": time.time() - start_time,
            "model": self.model_name,
            "attempts": self.max_retries
        }
    
    def _call_gemini_with_base64(
        self,
        image: np.ndarray,
        start_time: float,
        language: str
    ) -> Dict:
        """เรียก Gemini API ด้วย base64"""
        pil_image = self.preprocess_image(image)
        prompt = self.create_prompt(language)
        
        last_error = None
        for attempt in range(self.max_retries):
            try:
                response = self.client.models.generate_content(
                    model=self.model_name,
                    contents=[prompt, pil_image],
                    config={
                        "temperature": self.temperature,
                    }
                )
                
                raw_text = response.text.strip()
                parsed_result = self.parse_json_response(raw_text)
                processing_time = time.time() - start_time
                confidence = self.estimate_confidence_from_json(parsed_result, raw_text)
                
                logger.info(f"✅ Gemini API success on attempt {attempt + 1}")
                return {
                    "license_plate_number": parsed_result.get("license_plate_number", ""),
                    "province": parsed_result.get("province", ""),
                    "confidence": confidence,
                    "raw_response": raw_text,
                    "processing_time": processing_time,
                    "model": self.model_name,
                    "attempts": attempt + 1,
                    "mode": "base64",
                    "text": parsed_result.get("license_plate_number", "")  # backward compatibility
                }
            
            except Exception as e:
                last_error = e
                is_overload = self._is_overload_error(e)
                
                logger.warning(
                    f"⚠️ Gemini API attempt {attempt + 1}/{self.max_retries} failed: {e}"
                )
                
                if attempt < self.max_retries - 1:
                    wait_time = self._calculate_retry_delay(attempt, is_overload)
                    
                    if is_overload:
                        logger.info(
                            f"   ⏳ API overloaded (503), waiting {wait_time:.1f}s before retry..."
                        )
                    else:
                        logger.info(f"   ⏳ Waiting {wait_time:.1f}s before retry...")
                    
                    time.sleep(wait_time)
                else:
                    logger.error(f"❌ All {self.max_retries} retries failed")
        
        return {
            "license_plate_number": "",
            "province": "",
            "text": "",
            "confidence": 0.0,
            "error": str(last_error),
            "processing_time": time.time() - start_time,
            "model": self.model_name,
            "attempts": self.max_retries,
            "mode": "base64"
        }
    
    def _fallback_to_base64(
        self,
        image: np.ndarray,
        start_time: float,
        language: str
    ) -> Dict:
        """Fallback เมื่อ URL mode ล้มเหลว"""
        logger.info("🔄 Falling back to base64 mode...")
        result = self._call_gemini_with_base64(image, start_time, language)
        result["fallback"] = True
        return result
    
    def parse_json_response(self, text: str) -> Dict[str, str]:
        """แปลง JSON response จาก Gemini เป็น dict"""
        if not text:
            logger.warning("⚠️ Gemini returned empty text")
            return {"license_plate_number": "", "province": ""}
        
        logger.debug(f"🔍 Raw Gemini response: '{text}'")
        
        try:
            # ลองแปลงเป็น JSON โดยตรง
            # ลบ markdown code blocks ถ้ามี (```json ... ```)
            clean_text = text.strip()
            if clean_text.startswith('```'):
                # ลบ ```json และ ```
                clean_text = re.sub(r'^```(?:json)?\s*', '', clean_text)
                clean_text = re.sub(r'\s*```$', '', clean_text)
            
            result = json.loads(clean_text)
            
            # ตรวจสอบว่ามี keys ที่ต้องการ
            license_plate = result.get("license_plate_number", "").strip().upper()
            province = result.get("province", "").strip()
            
            logger.info(f"✅ Parsed JSON - Plate: '{license_plate}', Province: '{province}'")
            
            return {
                "license_plate_number": license_plate,
                "province": province
            }
            
        except json.JSONDecodeError as e:
            logger.warning(f"⚠️ Failed to parse JSON: {e}, falling back to text extraction")
            # ถ้า parse JSON ไม่ได้ ให้ลองหา pattern
            return self._extract_from_text(text)
    
    def _extract_from_text(self, text: str) -> Dict[str, str]:
        """สกัดข้อมูลจากข้อความธรรมดาเมื่อ parse JSON ไม่ได้"""
        # ลองหา license_plate_number และ province จาก text
        license_match = re.search(r'license_plate_number["\s:]+([^,\n"]+)', text, re.IGNORECASE)
        province_match = re.search(r'province["\s:]+([^,\n"]+)', text, re.IGNORECASE)
        
        license_plate = ""
        province = ""
        
        if license_match:
            license_plate = license_match.group(1).strip().strip('"').upper()
        
        if province_match:
            province = province_match.group(1).strip().strip('"')
        
        logger.info(f"✅ Extracted from text - Plate: '{license_plate}', Province: '{province}'")
        
        return {
            "license_plate_number": license_plate,
            "province": province
        }
    
    def clean_text(self, text: str) -> str:
        """ทำความสะอาดข้อความจาก Gemini (backward compatibility)"""
        parsed = self.parse_json_response(text)
        return parsed.get("license_plate_number", "")
    
    def estimate_confidence_from_json(self, parsed_result: Dict[str, str], raw_text: str) -> float:
        """ประมาณค่า confidence จาก parsed JSON result"""
        license_plate = parsed_result.get("license_plate_number", "")
        province = parsed_result.get("province", "")
        
        # ถ้าอ่านไม่ได้เลย
        if not license_plate or "UNREADABLE" in license_plate.upper():
            return 0.0
        
        # ถ้าป้ายทะเบียนสั้นเกินไป
        if len(license_plate) < 3:
            return 0.3
        
        confidence = 0.0
        
        # ตรวจสอบ format ป้ายทะเบียน
        thai_pattern = r'[ก-ฮ]{1,3}[\s\-]?\d{3,4}'
        english_pattern = r'[A-Z]{2,3}[\s\-]?\d{3,4}'
        new_format_pattern = r'\d[ก-ฮ]{2}[\s\-]?\d{3,4}'
        
        if re.search(thai_pattern, license_plate) or re.search(english_pattern, license_plate) or re.search(new_format_pattern, license_plate):
            confidence = 0.8
        else:
            confidence = 0.5
        
        # ถ้ามีจังหวัดด้วย เพิ่ม confidence
        if province and province != "UNREADABLE" and len(province) > 2:
            confidence = min(confidence + 0.15, 1.0)
        
        return confidence
    
    def estimate_confidence(self, cleaned_text: str, raw_text: str) -> float:
        """ประมาณค่า confidence (backward compatibility)"""
        parsed = {"license_plate_number": cleaned_text, "province": ""}
        return self.estimate_confidence_from_json(parsed, raw_text)
    
    def validate_plate_format(self, text: str) -> bool:
        """ตรวจสอบว่าข้อความมี format ป้ายทะเบียนหรือไม่"""
        patterns = [
            r'^[ก-ฮ]{1,2}[\s\-]?\d{4}$',
            r'^\d[ก-ฮ]{2}[\s\-]?\d{4}$',
            r'^\d[ก-ฮ]{2}[\s\-]?[\u0E00-\u0E7F]+$',
            r'^[A-Z]{2,3}[\s\-]?\d{3,5}$',
            r'^\d{1,2}[\s\-]?\d{4}$',
        ]
        
        for pattern in patterns:
            if re.match(pattern, text):
                return True
        
        return False
    
    def cleanup_temp_files(self, older_than_hours: int = 24):
        """ลบไฟล์เก่า (ปิดการใช้งาน - เก็บไฟล์ไว้ถาวร)"""
        # ไม่ลบไฟล์ - บันทึกไว้ถาวรเพื่อการตรวจสอบ
        if not self.use_image_url or not self.temp_dir:
            return
        
        logger.debug("Cleanup disabled - keeping all cropped plate images")
        return
        
        # เดิม: ลบไฟล์เก่า (ถูกปิดการใช้งาน)
        # current_time = time.time()
        # cutoff_time = current_time - (older_than_hours * 3600)
        # 
        # deleted_count = 0
        # for file in self.temp_dir.glob("*.jpg"):
        #     if file.stat().st_mtime < cutoff_time:
        #         file.unlink()
        #         deleted_count += 1
        # 
        # if deleted_count > 0:
        #     logger.info(f"🗑️ Cleaned up {deleted_count} old temp files")