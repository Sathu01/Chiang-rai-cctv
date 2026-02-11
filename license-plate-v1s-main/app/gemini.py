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
        
        # สร้างโฟลเดอร์สำหรับเก็บภาพ crop (ถ้าใช้ URL)
        if self.use_image_url:
            self.temp_dir = Path("license-plate-v1s-main/output/temp_crops")
            self.temp_dir.mkdir(parents=True, exist_ok=True)
            logger.info(f"✓ Using IMAGE URL mode - temp dir: {self.temp_dir}")
        else:
            self.temp_dir = None
            logger.info(f"✓ Using BASE64 mode")
        
        logger.info(f"✓ Gemini OCR initialized ({model_name})")
    
    def create_prompt(self, language: str = "thai") -> str:
        """สร้าง prompt สำหรับ Gemini"""
        base_prompt = """You are an OCR system specialized in reading Thai vehicle license plates from CCTV images.

TASK:
Read and extract ONLY the visible characters on the Thai license plate in the image.
Do NOT guess, infer, or auto-correct missing characters.

THAI LICENSE PLATE FORMATS:
- Standard car: กข 1234 or กข-1234
- New format: 1กข 2345 or 1กข-2345
- Motorcycle: 1กข กรุงเทพ
- Public vehicle: 12-3456, นท-123

RULES (STRICT):
- Read characters only if clearly visible.
- If some characters are unclear, replace them with "?".
- Use "UNREADABLE" ONLY if no characters can be read at all.
- Do NOT explain your reasoning.
- Do NOT output anything except the result.

OUTPUT FORMAT:
- Full readable plate: "กข 1234"
- Partially readable: "กข ????", "?? 1234"
- Completely unreadable: "UNREADABLE"

Allowed Thai characters:
ก ข ฃ ค ฅ ฆ ง จ ฉ ช ซ ฌ ญ ฎ ฏ ฐ ฑ ฒ ณ ด ต ถ ท ธ น บ ป ผ ฝ พ ฟ ภ ม ย ร ล ว ศ ษ ส ห ฬ อ ฮ
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
        """บันทึกภาพ crop ชั่วคราว"""
        timestamp = int(time.time() * 1000)
        filename = f"{Path(original_filename).stem}_plate_{detection_id}_{timestamp}.jpg"
        filepath = self.temp_dir / filename
        
        cv2.imwrite(str(filepath), image)
        logger.debug(f"💾 Saved temp image: {filepath}")
        
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
                cleaned_text = self.clean_text(raw_text)
                processing_time = time.time() - start_time
                confidence = self.estimate_confidence(cleaned_text, raw_text)
                
                # ✅ สำเร็จ
                logger.info(f"✅ Gemini API success on attempt {attempt + 1}")
                return {
                    "text": cleaned_text,
                    "confidence": confidence,
                    "raw_response": raw_text,
                    "processing_time": processing_time,
                    "model": self.model_name,
                    "attempts": attempt + 1
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
                cleaned_text = self.clean_text(raw_text)
                processing_time = time.time() - start_time
                confidence = self.estimate_confidence(cleaned_text, raw_text)
                
                logger.info(f"✅ Gemini API success on attempt {attempt + 1}")
                return {
                    "text": cleaned_text,
                    "confidence": confidence,
                    "raw_response": raw_text,
                    "processing_time": processing_time,
                    "model": self.model_name,
                    "attempts": attempt + 1,
                    "mode": "base64"
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
    
    def clean_text(self, text: str) -> str:
        """ทำความสะอาดข้อความจาก Gemini"""
        if not text:
            logger.warning("⚠️ Gemini returned empty text")
            return ""
        
        logger.debug(f"🔍 Raw Gemini response: '{text}'")
        
        lines = text.split('\n')
        main_text = lines[0].strip()
        
        unwanted_phrases = [
            "ป้ายทะเบียน", "license plate", "number plate",
            "อ่านได้ว่า", "reads", "shows", "says",
            "ภาพนี้", "this image", "the plate", "is", "the text"
        ]
        
        for phrase in unwanted_phrases:
            main_text = re.sub(phrase, "", main_text, flags=re.IGNORECASE)
        
        main_text = re.sub(r'[^\w\s\-\u0E00-\u0E7F]', '', main_text)
        main_text = ' '.join(main_text.split())
        main_text = main_text.upper()
        
        logger.info(f"✅ Cleaned text: '{main_text}'")
        
        return main_text
    
    def estimate_confidence(self, cleaned_text: str, raw_text: str) -> float:
        """ประมาณค่า confidence"""
        if not cleaned_text or "UNREADABLE" in raw_text.upper():
            return 0.0
        
        if len(cleaned_text) < 3:
            return 0.5
        
        thai_pattern = r'[ก-ฮ]{1,3}[\s\-]?\d{3,4}'
        english_pattern = r'[A-Z]{2,3}[\s\-]?\d{3,4}'
        
        if re.search(thai_pattern, cleaned_text) or re.search(english_pattern, cleaned_text):
            return 0.9
        
        return 0.7
    
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
        """ลบไฟล์ temp ที่เก่าเกินกำหนด"""
        if not self.use_image_url or not self.temp_dir:
            return
        
        current_time = time.time()
        cutoff_time = current_time - (older_than_hours * 3600)
        
        deleted_count = 0
        for file in self.temp_dir.glob("*.jpg"):
            if file.stat().st_mtime < cutoff_time:
                file.unlink()
                deleted_count += 1
        
        if deleted_count > 0:
            logger.info(f"🗑️ Cleaned up {deleted_count} old temp files")