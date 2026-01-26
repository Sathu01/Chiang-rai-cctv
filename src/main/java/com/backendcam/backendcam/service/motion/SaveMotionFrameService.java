package com.backendcam.backendcam.service.motion;

import com.backendcam.backendcam.model.dto.MotionEvent;
import com.backendcam.backendcam.service.firestore.FirebaseAdminBootstrap;

import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Blob;
import com.google.firebase.cloud.StorageClient;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
@Service
public class SaveMotionFrameService {

     private final FirebaseAdminBootstrap bootstrap;
    private final Java2DFrameConverter converter = new Java2DFrameConverter();

    public SaveMotionFrameService(FirebaseAdminBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    public String uploadMotionFrame(Frame frame, String cameraId) {
        if (!bootstrap.isInitialized()) {
            return null;
        }

        try {
            BufferedImage image = converter.convert(frame);
            if (image == null) return null;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", baos);
            byte[] bytes = baos.toByteArray();

            String path = "motion/" + cameraId + "/" + System.currentTimeMillis() + ".jpg";

            Bucket bucket = StorageClient.getInstance().bucket();
            Blob blob = bucket.create(path, bytes, "image/jpeg");

            return "https://storage.googleapis.com/"
                    + bucket.getName() + "/"
                    + blob.getName();

        } catch (Exception e) {
            throw new RuntimeException("Upload frame to Firebase Storage failed", e);
        }
    }
}
