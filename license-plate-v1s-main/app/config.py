from pydantic_settings import BaseSettings
from pathlib import Path
from typing import Optional, List


class Settings(BaseSettings):
    ENVIRONMENT: str = "dev"

    # Kafka settings
    KAFKA_BOOTSTRAP_SERVERS: str
    KAFKA_TOPIC: str = "license-plates"
    KAFKA_GROUP_ID: str = "lp-consumer-group"
    KAFKA_AUTO_OFFSET_RESET: str = "earliest"

    # Model path
    MODEL_PATH: str = "models/license-plate-finetune-v1s.pt"
    CONFIDENCE_THRESHOLD: float = 0.25

    # ===== OCR Settings =====
    OCR_ENGINE: str = "gemini"  # gemini | easyocr

    # ===== Gemini API Settings =====
    GEMINI_API_KEY: Optional[str] = None
    GEMINI_MODEL: str  # ต้องกำหนดใน .env
    GEMINI_TEMPERATURE: float = 0.1
    GEMINI_MAX_RETRIES: int = 3
    GEMINI_TIMEOUT: int = 30
    USE_IMAGE_URL: bool = True  # ใช้ image_url แทน base64 เพื่อลดขนาด payload

    # ===== EasyOCR Settings =====
    EASYOCR_LANGUAGES: List[str] = ["th", "en"]
    EASYOCR_GPU: bool = False

    # ===== Output Settings =====
    OUTPUT_IMAGE_DIR: str = "output/images"
    OUTPUT_JSON_DIR: str = "output/json"

    # ===== Validation =====
    def validate_gemini_api_key(self) -> None:
        if self.OCR_ENGINE == "gemini" and not self.GEMINI_API_KEY:
            raise ValueError(
                "❌ GEMINI_API_KEY is required when OCR_ENGINE='gemini'"
            )

    # ===== Helper Methods =====
    def get_ocr_config(self) -> dict:
        if self.OCR_ENGINE == "gemini":
            return {
                "engine": "gemini",
                "api_key": self.GEMINI_API_KEY,
                "model": self.GEMINI_MODEL,
                "temperature": self.GEMINI_TEMPERATURE,
                "max_retries": self.GEMINI_MAX_RETRIES,
                "timeout": self.GEMINI_TIMEOUT,
                "use_image_url": self.USE_IMAGE_URL,
            }

        return {
            "engine": "easyocr",
            "languages": self.EASYOCR_LANGUAGES,
            "gpu": self.EASYOCR_GPU,
        }

    def create_output_dirs(self) -> None:
        Path(self.OUTPUT_IMAGE_DIR).mkdir(parents=True, exist_ok=True)
        Path(self.OUTPUT_JSON_DIR).mkdir(parents=True, exist_ok=True)
    
    model_config = {
        "env_file": Path(__file__).parent.parent / ".env.dev",
        "env_file_encoding": "utf-8",
    }

# create a single settings instance
settings = Settings()