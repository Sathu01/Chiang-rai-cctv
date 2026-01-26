package com.backendcam.backendcam.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backendcam.backendcam.model.dto.MotionEvent;
import com.backendcam.backendcam.service.kafka.MotionEventProducer;

@RestController
@RequestMapping("/kafka")
public class KafkaController {
	
	private final MotionEventProducer motionEventProducer;

	@Autowired
	public KafkaController(MotionEventProducer motionEventProducer) {
		this.motionEventProducer = motionEventProducer;
	}

	@PostMapping("/sendMotionEvent")
	public String sendMotionEvent() {

		MotionEvent motionEvent = MotionEvent.builder()
				.cameraId("camera123")
				.timestamp(System.currentTimeMillis())
				.imageUrl("http://example.com/image.jpg")
				.metadata("Sample motion event")
				.build();

		motionEventProducer.send(motionEvent);
		return "Motion event sent to Kafka topic.";
	}
}