package com.backendcam.backendcam.service.kafka;

import com.backendcam.backendcam.model.dto.MotionEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class MotionEventProducer {

    private static final String TOPIC = "motion-event";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public MotionEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void send(MotionEvent event) {
        kafkaTemplate.send(
                TOPIC,
                event.getCameraId(), // key (partition by camera)
                event
        );
    }
}
