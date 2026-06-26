package com.localbuddy.common;

/** Geospatial helpers. */
public final class GeoUtil {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private GeoUtil() {
    }

    /** Great-circle (Haversine) distance in metres between two lat/lng points. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
