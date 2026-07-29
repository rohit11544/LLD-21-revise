import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Ride Sharing (Uber/Lyft-style) — ONE FILE
 * Patterns: Facade + Strategy (pricing/matching) + enum state machine
 *           + spatial grid index + per-driver tryLock
 *
 *   javac Main.java && java Main
 */

// ========== LOCATION + DOMAIN ==========

class Location {
    private final double latitude;
    private final double longitude;

    Location(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    double getLatitude() { return latitude; }
    double getLongitude() { return longitude; }

    /** Approx km (Euclidean degrees × ~111). Fine for LLD grid demos. */
    double distanceTo(Location other) {
        double latDiff = this.latitude - other.latitude;
        double lonDiff = this.longitude - other.longitude;
        return Math.sqrt(latDiff * latDiff + lonDiff * lonDiff) * 111.0;
    }

    /** Simplified grid cell (geohash-lite). */
    String toGridKey() {
        int latGrid = (int) (latitude * 10);
        int lonGrid = (int) (longitude * 10);
        return latGrid + ":" + lonGrid;
    }
}

enum DriverStatus {
    OFFLINE,
    AVAILABLE,
    ON_TRIP
}

class Driver {
    private final String driverId;
    private final String name;
    private volatile DriverStatus status;
    private volatile Location currentLocation;
    private final ReentrantLock lock = new ReentrantLock();

    Driver(String driverId, String name, Location initialLocation) {
        this.driverId = driverId;
        this.name = name;
        this.currentLocation = initialLocation;
        this.status = DriverStatus.OFFLINE;
    }

    String getDriverId() { return driverId; }
    String getName() { return name; }
    DriverStatus getStatus() { return status; }
    Location getCurrentLocation() { return currentLocation; }
    ReentrantLock getLock() { return lock; }

    void setStatus(DriverStatus status) { this.status = status; }
    void setCurrentLocation(Location location) { this.currentLocation = location; }
}

class Rider {
    private final String riderId;
    private final String name;

    Rider(String riderId, String name) {
        this.riderId = riderId;
        this.name = name;
    }

    String getRiderId() { return riderId; }
    String getName() { return name; }
}

// ========== TRIP STATE MACHINE ==========

enum TripStatus {
    CREATED,
    DRIVER_ASSIGNED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}

class Trip {
    private final String tripId;
    private final Rider rider;
    private volatile Driver driver;
    private final Location pickupLocation;
    private final Location dropoffLocation;
    private volatile TripStatus status;
    private final double estimatedFare;

    Trip(Rider rider, Location pickup, Location dropoff, double estimatedFare) {
        this.tripId = UUID.randomUUID().toString();
        this.rider = rider;
        this.pickupLocation = pickup;
        this.dropoffLocation = dropoff;
        this.estimatedFare = estimatedFare;
        this.status = TripStatus.CREATED;
    }

    String getTripId() { return tripId; }
    Rider getRider() { return rider; }
    Driver getDriver() { return driver; }
    Location getPickupLocation() { return pickupLocation; }
    Location getDropoffLocation() { return dropoffLocation; }
    TripStatus getStatus() { return status; }
    double getEstimatedFare() { return estimatedFare; }

    synchronized void assignDriver(Driver driver) {
        if (this.status != TripStatus.CREATED) {
            throw new IllegalStateException("Cannot assign driver in status: " + this.status);
        }
        this.driver = driver;
        this.status = TripStatus.DRIVER_ASSIGNED;
    }

    synchronized void startTrip() {
        if (this.status != TripStatus.DRIVER_ASSIGNED) {
            throw new IllegalStateException("Cannot start trip in status: " + this.status);
        }
        this.status = TripStatus.IN_PROGRESS;
    }

    synchronized void completeTrip() {
        if (this.status != TripStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot complete trip in status: " + this.status);
        }
        this.status = TripStatus.COMPLETED;
    }

    synchronized void cancelTrip() {
        if (this.status == TripStatus.COMPLETED || this.status == TripStatus.CANCELLED) {
            throw new IllegalStateException("Cannot cancel finalized trip in status: " + this.status);
        }
        this.status = TripStatus.CANCELLED;
    }
}

// ========== STRATEGY: PRICING + MATCHING ==========

interface PricingStrategy {
    double calculateFare(Location pickup, Location dropoff, double surgeMultiplier);
}

class StandardPricingStrategy implements PricingStrategy {
    private static final double BASE_FARE = 2.50;
    private static final double PER_KM_RATE = 1.20;

    @Override
    public double calculateFare(Location pickup, Location dropoff, double surgeMultiplier) {
        double distanceKm = pickup.distanceTo(dropoff);
        return (BASE_FARE + distanceKm * PER_KM_RATE) * surgeMultiplier;
    }
}

interface DriverMatchingStrategy {
    Driver matchDriver(List<Driver> candidateDrivers, Location pickupLocation);
}

class NearestDriverStrategy implements DriverMatchingStrategy {
    @Override
    public Driver matchDriver(List<Driver> candidateDrivers, Location pickupLocation) {
        Driver nearest = null;
        double minDistance = Double.MAX_VALUE;
        for (Driver driver : candidateDrivers) {
            double distance = driver.getCurrentLocation().distanceTo(pickupLocation);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = driver;
            }
        }
        return nearest;
    }
}

// ========== SPATIAL GRID INDEX ==========

class SpatialIndexManager {
    private final Map<String, Set<Driver>> gridIndex = new ConcurrentHashMap<>();

    void updateDriverLocation(Driver driver, Location oldLocation, Location newLocation) {
        if (oldLocation != null) {
            removeFromCell(oldLocation.toGridKey(), driver);
        }
        String newKey = newLocation.toGridKey();
        gridIndex.computeIfAbsent(newKey, k -> ConcurrentHashMap.newKeySet()).add(driver);
        driver.setCurrentLocation(newLocation);
    }

    /** Remove driver from whichever cell they currently occupy. */
    void removeDriver(Driver driver) {
        Location loc = driver.getCurrentLocation();
        if (loc != null) {
            removeFromCell(loc.toGridKey(), driver);
        }
    }

    private void removeFromCell(String key, Driver driver) {
        Set<Driver> drivers = gridIndex.get(key);
        if (drivers != null) {
            drivers.remove(driver);
        }
    }

    /**
     * Center cell only (geohash-lite). Production: also scan 8 neighbors.
     * Complexity: O(K) drivers in the cell.
     */
    List<Driver> findNearbyAvailableDrivers(Location pickupLocation, double radiusKm) {
        List<Driver> candidates = new ArrayList<>();
        Set<Driver> driversInCell =
                gridIndex.getOrDefault(pickupLocation.toGridKey(), Collections.emptySet());

        for (Driver driver : driversInCell) {
            if (driver.getStatus() == DriverStatus.AVAILABLE
                    && driver.getCurrentLocation().distanceTo(pickupLocation) <= radiusKm) {
                candidates.add(driver);
            }
        }
        return candidates;
    }
}

// ========== FACADE ==========

class RideSharingService {
    private final SpatialIndexManager spatialIndex = new SpatialIndexManager();
    private final Map<String, Trip> activeTrips = new ConcurrentHashMap<>();
    private final PricingStrategy pricingStrategy;
    private final DriverMatchingStrategy matchingStrategy;

    RideSharingService(PricingStrategy pricingStrategy, DriverMatchingStrategy matchingStrategy) {
        this.pricingStrategy = pricingStrategy;
        this.matchingStrategy = matchingStrategy;
    }

    void updateDriverStatus(Driver driver, DriverStatus status) {
        if (status != DriverStatus.AVAILABLE) {
            spatialIndex.removeDriver(driver);
        }
        driver.setStatus(status);
        if (status == DriverStatus.AVAILABLE) {
            spatialIndex.updateDriverLocation(driver, null, driver.getCurrentLocation());
        }
    }

    void updateDriverLocation(Driver driver, Location newLocation) {
        Location oldLocation = driver.getCurrentLocation();
        if (driver.getStatus() == DriverStatus.AVAILABLE) {
            spatialIndex.updateDriverLocation(driver, oldLocation, newLocation);
        } else {
            driver.setCurrentLocation(newLocation); // track GPS without indexing
        }
    }

    /**
     * @return assigned trip, or null if no driver could be locked
     */
    Trip requestRide(Rider rider, Location pickup, Location dropoff, double surgeMultiplier) {
        double estimatedFare = pricingStrategy.calculateFare(pickup, dropoff, surgeMultiplier);
        Trip trip = new Trip(rider, pickup, dropoff, estimatedFare);

        List<Driver> nearbyDrivers = new ArrayList<>(
                spatialIndex.findNearbyAvailableDrivers(pickup, 5.0));

        // Prefer drivers via strategy (nearest); on lock miss, remove and try next
        while (!nearbyDrivers.isEmpty()) {
            Driver candidate = matchingStrategy.matchDriver(nearbyDrivers, pickup);
            if (candidate == null) {
                break;
            }

            if (candidate.getLock().tryLock()) {
                try {
                    if (candidate.getStatus() == DriverStatus.AVAILABLE) {
                        candidate.setStatus(DriverStatus.ON_TRIP);
                        spatialIndex.removeDriver(candidate); // leave matchable index
                        trip.assignDriver(candidate);
                        activeTrips.put(trip.getTripId(), trip);

                        System.out.println("[Dispatch] Matched Trip " + trip.getTripId()
                                + " for Rider (" + rider.getName() + ") with Driver ("
                                + candidate.getName() + ") | Estimated Fare: $"
                                + String.format("%.2f", estimatedFare));
                        return trip;
                    }
                } finally {
                    candidate.getLock().unlock();
                }
            }
            nearbyDrivers.remove(candidate);
        }

        System.out.println("[Dispatch] No available drivers for Rider: " + rider.getName());
        return null;
    }

    void startTrip(String tripId) {
        Trip trip = activeTrips.get(tripId);
        if (trip == null) {
            throw new IllegalArgumentException("Unknown trip: " + tripId);
        }
        trip.startTrip();
        System.out.println("[Trip] IN_PROGRESS: " + tripId);
    }

    void completeTrip(String tripId) {
        Trip trip = activeTrips.get(tripId);
        if (trip == null) {
            throw new IllegalArgumentException("Unknown trip: " + tripId);
        }
        trip.completeTrip();
        Driver driver = trip.getDriver();
        driver.setCurrentLocation(trip.getDropoffLocation());
        updateDriverStatus(driver, DriverStatus.AVAILABLE);
        activeTrips.remove(tripId);
        System.out.println("[Trip] COMPLETED: " + tripId
                + " | Driver " + driver.getName() + " AVAILABLE again");
    }

    void cancelTrip(String tripId) {
        Trip trip = activeTrips.get(tripId);
        if (trip == null) {
            throw new IllegalArgumentException("Unknown trip: " + tripId);
        }
        trip.cancelTrip();
        Driver driver = trip.getDriver();
        if (driver != null) {
            updateDriverStatus(driver, DriverStatus.AVAILABLE);
        }
        activeTrips.remove(tripId);
        System.out.println("[Trip] CANCELLED: " + tripId);
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) {
        RideSharingService rideService = new RideSharingService(
                new StandardPricingStrategy(),
                new NearestDriverStrategy());

        Location downtown = new Location(12.9716, 77.5946);
        Location airport = new Location(13.1986, 77.7066);

        Rider riderAlice = new Rider("R1", "Alice");
        Rider riderCharlie = new Rider("R2", "Charlie");
        Driver driverBob = new Driver("D1", "Bob", downtown);

        System.out.println("=== TEST 1: DRIVER ONLINE & MATCH ===");
        rideService.updateDriverStatus(driverBob, DriverStatus.AVAILABLE);

        Trip trip1 = rideService.requestRide(riderAlice, downtown, airport, 1.5);
        if (trip1 == null) {
            System.out.println("Unexpected: no match for Alice");
            return;
        }

        System.out.println("\n=== TEST 2: TRIP LIFECYCLE ===");
        rideService.startTrip(trip1.getTripId());
        rideService.completeTrip(trip1.getTripId());

        System.out.println("\n=== TEST 3: CONCURRENT CONFLICT (one driver) ===");
        // Bob is AVAILABLE at airport after completeTrip
        Trip trip2 = rideService.requestRide(riderCharlie, airport, downtown, 1.0);
        Trip trip3Conflict = rideService.requestRide(riderAlice, airport, downtown, 1.0);

        System.out.println("Charlie matched: " + (trip2 != null));
        System.out.println("Alice conflict (expect false): " + (trip3Conflict != null));

        if (trip2 != null) {
            System.out.println("\n=== TEST 4: CANCEL RESTORES DRIVER ===");
            rideService.cancelTrip(trip2.getTripId());
            Trip trip4 = rideService.requestRide(riderAlice, airport, downtown, 1.0);
            System.out.println("Alice rematch after cancel: " + (trip4 != null));
        }
    }
}
