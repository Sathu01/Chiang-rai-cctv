import asyncio
import json
import logging
import requests
from aiokafka import AIOKafkaConsumer
from config import settings

logger = logging.getLogger(__name__)


async def consume(detector):
    """
    Simplified Kafka consumer to process image detection tasks.
    Expected message format (JSON):
    {"imageUrl": "http://example.com/image.jpg", "use_ocr": true}
    """
    consumer = AIOKafkaConsumer(
        settings.KAFKA_TOPIC,
        bootstrap_servers=settings.KAFKA_BOOTSTRAP_SERVERS,
        group_id=settings.KAFKA_GROUP_ID,
        auto_offset_reset=settings.KAFKA_AUTO_OFFSET_RESET,
        enable_auto_commit=True,
        value_deserializer=lambda v: json.loads(v.decode('utf-8'))
    )

    try:
        await consumer.start()
    except Exception as e:
        logger.error(f"Unable to connect to Kafka: {e}")
        return

    try:
        async for msg in consumer:
            payload = msg.value
            image_url = payload.get("imageUrl")
            use_ocr = payload.get("use_ocr", True)

            if image_url:
                try:
                    # Download the image from the URL
                    response = requests.get(image_url, stream=True)
                    if response.status_code == 200:
                        temp_image_path = f"{settings.OUTPUT_IMAGE_DIR}/temp_image.jpg"
                        with open(temp_image_path, 'wb') as f:
                            for chunk in response.iter_content(1024):
                                f.write(chunk)

                        # Perform detection on the downloaded image
                        result = detector.detect(image_path=temp_image_path, use_ocr=use_ocr)
                        logger.info(f"Detection finished for {image_url}: {result.get('total_plates')} plates detected.")
                    else:
                        logger.warning(f"Failed to download image from URL: {image_url}, Status Code: {response.status_code}")
                except FileNotFoundError:
                    logger.warning(f"File not found for detection: {image_url}")
                except Exception as e:
                    logger.error(f"Error during detection for {image_url}: {e}")
            else:
                logger.warning(f"Skipping message without imageUrl: {payload}")
    finally:
        await consumer.stop()
