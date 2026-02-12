package com.backendcam.backendcam.controller;

import com.backendcam.backendcam.model.dto.LicensePlate;
import com.backendcam.backendcam.service.search.GetLicensePlate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/license-plates")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class LicensePlateController {

    private final GetLicensePlate getLicensePlate;

    /**
     * GET /api/license-plates/search?licensePlate=ABC123
     * Returns the top 10 fuzzy matches sorted by score then latest dateTime.
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchLicensePlate(@RequestParam String licensePlate) {
        try {
            List<LicensePlate> results = getLicensePlate.getTopMatchesByLicensePlate(licensePlate);

            if (results.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Error searching license plate: " + e.getMessage());
        }
    }
}
