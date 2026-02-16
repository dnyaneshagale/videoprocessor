package com.vidprocessor.service;

import com.vidprocessor.config.FirebaseConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import org.apache.commons.io.FilenameUtils;

/**
 * Service for updating video entries in Firebase Realtime Database via REST API.
 * Videos already exist at /videos/{video_id} — this service only patches HLS fields.
 */
@Service
@Slf4j
public class FirebaseService {

    private final RestTemplate restTemplate;
    private final String databaseUrl;

    public FirebaseService(RestTemplate firebaseRestTemplate, FirebaseConfig firebaseConfig) {
        this.restTemplate = firebaseRestTemplate;
        this.databaseUrl = firebaseConfig.getDatabaseUrl();
    }

    /**
     * Updates an existing video entry with HLS conversion result.
     *
     * @param r2ObjectKey       The R2 object key (e.g. "video/video_abc-123.mp4")
     * @param isHLSConverted    Whether HLS conversion succeeded
     * @param conversionTimeSec Time taken for conversion in seconds (0 if failed)
     */
    public void updateHlsFields(String r2ObjectKey, boolean isHLSConverted, long conversionTimeSec) {
        try {
            // Extract video_id from r2ObjectKey: "video/video_abc-123.mp4" → "video_abc-123"
            String videoId = FilenameUtils.getBaseName(r2ObjectKey);
            String url = databaseUrl + "/videos/" + videoId + ".json";

            Map<String, Object> updates = Map.of(
                    "isHLSConverted", isHLSConverted,
                    "hlsConversionTimeSec", conversionTimeSec
            );

            restTemplate.patchForObject(url, updates, Object.class);
            log.info("Firebase: Updated HLS fields for videoId={} — isHLSConverted={}, timeSec={}",
                    videoId, isHLSConverted, conversionTimeSec);
        } catch (Exception e) {
            log.error("Firebase: Failed to update HLS fields for video={}: {}", r2ObjectKey, e.getMessage(), e);
        }
    }
}
