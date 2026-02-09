package com.backendcam.backendcam.service.listcamera;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.firebase.cloud.FirestoreClient;
import com.backendcam.backendcam.model.dto.ListCamera;

@Service
public class ListCameraService {
  
    public List<ListCamera> getCameraByPage(int page, int pageSize)
            throws Exception {

        Firestore db = FirestoreClient.getFirestore();

        Query query = db.collection("cameras")
                .orderBy(FieldPath.documentId())
                .limit(pageSize);

        // Convert page number to cursor
        if (page > 1) {
            int docsToSkip = (page - 1) * pageSize;

            QuerySnapshot skipped =
                    db.collection("cameras")
                      .orderBy(FieldPath.documentId())
                      .limit(docsToSkip)
                      .get()
                      .get();

            if (!skipped.isEmpty()) {
                DocumentSnapshot lastDoc =
                        skipped.getDocuments()
                               .get(skipped.size() - 1);

                query = query.startAfter(lastDoc);
            }
        }

        List<ListCamera> list = new ArrayList<>();

        for (QueryDocumentSnapshot doc : query.get().get().getDocuments()) {
            ListCamera cam = doc.toObject(ListCamera.class);
            cam.setId(doc.getId());
            list.add(cam);
        }

        return list;
    }

}


