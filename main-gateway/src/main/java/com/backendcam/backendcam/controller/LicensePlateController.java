package com.backendcam.backendcam.controller;

import com.backendcam.backendcam.model.dto.LicensePlate;
import com.backendcam.backendcam.service.search.GetLicensePlate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/license-plates")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class LicensePlateController {

    private final GetLicensePlate getLicensePlate;

    /**
     * GET /api/license-plates/search?licensePlate=ABC123
     * Returns the latest record matching the given license plate.
     */
    @GetMapping("/search")
    public ResponseEntity<?> getLatestLicensePlate(@RequestParam String licensePlate) {
        try {
            LicensePlate result = getLicensePlate.getLatestByLicensePlate(licensePlate);

            if (result == null) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Error searching license plate: " + e.getMessage());
        }
    }
}
