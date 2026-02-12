package com.backendcam.backendcam.service.search;

import com.backendcam.backendcam.model.dto.LicensePlate;
import com.backendcam.backendcam.repository.LicensePlateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import me.xdrop.fuzzywuzzy.FuzzySearch;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GetLicensePlate {

    private final LicensePlateRepository licensePlateRepository;

    // Minimum fuzzy score (0-100) to consider a match
    private static final int FUZZY_THRESHOLD = 60;
    // Max number of fuzzy results to return
    private static final int MAX_RESULTS = 10;

    /**
     * Fuzzy search: return the top 10 matching license plates.
     * 1. Try exact match first.
     * 2. If no exact match, fuzzy match across all records using FuzzySearch.
     * 3. Return top 10 sorted by best score, then latest dateTime.
     */
    public List<LicensePlate> getTopMatchesByLicensePlate(String query) throws ExecutionException, InterruptedException {
        // 1. Try exact match first
        List<LicensePlate> exactMatches = licensePlateRepository.findByLicensePlate(query);
        if (exactMatches != null && !exactMatches.isEmpty()) {
            return exactMatches.stream()
                    .sorted(Comparator.comparing(
                            LicensePlate::getDateTime,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(MAX_RESULTS)
                    .collect(Collectors.toList());
        }

        // 2. Fuzzy search across all records
        List<LicensePlate> allPlates = licensePlateRepository.getAll();
        if (allPlates == null || allPlates.isEmpty()) {
            return Collections.emptyList();
        }

        String normalizedQuery = normalize(query);

        // Score each plate, keep those above threshold, sort by score desc then dateTime desc
        return allPlates.stream()
                .filter(p -> p.getLicensePlate() != null)
                .filter(p -> fuzzyScore(normalizedQuery, normalize(p.getLicensePlate())) >= FUZZY_THRESHOLD)
                .sorted(Comparator
                        .comparingInt((LicensePlate p) -> fuzzyScore(normalizedQuery, normalize(p.getLicensePlate())))
                        .reversed()
                        .thenComparing(
                                LicensePlate::getDateTime,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_RESULTS)
                .collect(Collectors.toList());
    }

    /** Normalize: uppercase, remove spaces/dashes/dots */
    private String normalize(String input) {
        if (input == null) return "";
        return input.toUpperCase().replaceAll("[\\s\\-.]", "");
    }

    /**
     * Compute fuzzy score (0-100) using FuzzySearch.
     * Uses the best of ratio and partialRatio for flexibility.
     */
    private int fuzzyScore(String s1, String s2) {
        int ratio = FuzzySearch.ratio(s1, s2);
        int partialRatio = FuzzySearch.partialRatio(s1, s2);
        return Math.max(ratio, partialRatio);
    }
}
