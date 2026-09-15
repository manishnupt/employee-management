package com.hrms.employee.management.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrms.employee.management.dto.GeolocationResponse;
import com.hrms.employee.management.exceptions.DeviceLocationException;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class GeolocationValidator implements IpLocationValidator {
    // Office Location Coordinates
    private static final double OFFICE_LATITUDE = 18.5516;
    private static final double OFFICE_LONGITUDE = 73.8899;
    
    // Maximum allowed distance from office location (in kilometers)
    private static final double MAX_DISTANCE_KM = 1.0;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GeolocationValidator() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public boolean isInOfficeLocation(String ipAddress) throws DeviceLocationException {
        log.info("isInOfficeLocation called for ipAddress={}", ipAddress);
        try {
            // Fetch geolocation for the IP address
            GeolocationInfo geoInfo = fetchGeolocation(ipAddress);
            
            // Check if the location is within office proximity
            return isWithinOfficeProximity(
                geoInfo.getLatitude(), 
                geoInfo.getLongitude()
            );
        } catch (Exception e) {
            throw new DeviceLocationException(
                "Error validating location: " + e.getMessage()
            );
        }
    }

    // Fetch geolocation using a free geolocation API
    private GeolocationInfo fetchGeolocation(String ipAddress) throws Exception {
        try {
            String apiUrl = "https://ipapi.co/" + ipAddress + "/json/";
            
            HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder()
                    .uri(new URI(apiUrl))
                    .GET()
                    .build(), 
                HttpResponse.BodyHandlers.ofString()
            );

            // Log raw response for debugging
            log.info("Geolocation API Response: {}", response.body());

            GeolocationResponse geoResponse = objectMapper.readValue(
                response.body(), 
                GeolocationResponse.class
            );

            // Validate and handle potential null/zero values
            return new GeolocationInfo(
                validateCoordinate(geoResponse.getLatitude(), 18.5516),
                validateCoordinate(geoResponse.getLongitude(), 73.8899)
            );
        } catch (Exception e) {
            log.error("Geolocation fetch error for IP {}: {}", ipAddress, e.getMessage());
            throw new DeviceLocationException("Unable to fetch geolocation: " + e.getMessage());
        }
    }

    private double validateCoordinate(double coordinate, double fallback) {
        return (coordinate != 0 && !Double.isNaN(coordinate)) 
            ? coordinate 
            : fallback;
    }


    // Check if device is within office proximity
    private boolean isWithinOfficeProximity(double latitude, double longitude) {
        return calculateDistance(
            OFFICE_LATITUDE, OFFICE_LONGITUDE, 
            latitude, longitude
        ) <= MAX_DISTANCE_KM;
    }

    // Haversine formula for calculating distance between two geographical points
    private double calculateDistance(
        double lat1, double lon1, 
        double lat2, double lon2
    ) {
        final double EARTH_RADIUS = 6371.0; // kilometers

        // Convert to radians
        double lat1Rad = Math.toRadians(lat1);
        double lon1Rad = Math.toRadians(lon1);
        double lat2Rad = Math.toRadians(lat2);
        double lon2Rad = Math.toRadians(lon2);

        // Differences
        double dLat = lat2Rad - lat1Rad;
        double dLon = lon2Rad - lon1Rad;

        // Haversine formula
        double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                   Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                   Math.sin(dLon/2) * Math.sin(dLon/2);
        
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));

        // Distance in kilometers
        return EARTH_RADIUS * c;
    }
    

    @Data
    @AllArgsConstructor
    private static class GeolocationInfo {
        private double latitude;
        private double longitude;
    }
    
}
