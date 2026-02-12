package com.backendcam.backendcam.service.search;

import com.backendcam.backendcam.model.dto.LicensePlate;
import com.backendcam.backendcam.repository.LicensePlateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import me.xdrop.fuzzywuzzy.FuzzySearch;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GetLicensePlate {

    private final LicensePlateRepository licensePlateRepository;

    // Minimum fuzzy score (0-100) to consider a match
    private static final int FUZZY_THRESHOLD = 60;

    /**
     * Fuzzy search: find the latest license plate matching the query.
     * 1. Try exact match first.
     * 2. If no exact match, fuzzy match across all records using FuzzySearch.
     * 3. Return the latest (by dateTime) among the best fuzzy matches.
     */
    public LicensePlate getLatestByLicensePlate(String query) throws ExecutionException, InterruptedException {
        // 1. Try exact match first
        List<LicensePlate> exactMatches = licensePlateRepository.findByLicensePlate(query);
        if (exactMatches != null && !exactMatches.isEmpty()) {
            return getLatest(exactMatches);
        }

        // 2. Fuzzy search across all records
        List<LicensePlate> allPlates = licensePlateRepository.getAll();
        if (allPlates == null || allPlates.isEmpty()) {
            return null;
        }

        String normalizedQuery = normalize(query);

        // Score each plate and keep those above the threshold
        List<LicensePlate> fuzzyMatches = allPlates.stream()
                .filter(p -> p.getLicensePlate() != null)
                .filter(p -> fuzzyScore(normalizedQuery, normalize(p.getLicensePlate())) >= FUZZY_THRESHOLD)
                .collect(Collectors.toList());

        if (fuzzyMatches.isEmpty()) {
            return null;
        }

        // Find the best score among matches
        int bestScore = fuzzyMatches.stream()
                .mapToInt(p -> fuzzyScore(normalizedQuery, normalize(p.getLicensePlate())))
                .max()
                .orElse(0);

        // Keep only plates with the best score, return the latest by dateTime
        List<LicensePlate> bestMatches = fuzzyMatches.stream()
                .filter(p -> fuzzyScore(normalizedQuery, normalize(p.getLicensePlate())) == bestScore)
                .collect(Collectors.toList());

        return getLatest(bestMatches);
    }

    /** Return the latest plate by dateTime from a list */
    private LicensePlate getLatest(List<LicensePlate> plates) {
        return plates.stream()
                .sorted(Comparator.comparing(
                        LicensePlate::getDateTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .orElse(null);
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
