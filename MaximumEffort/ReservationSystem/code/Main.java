import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reservation System (BookMyShow-style) — ONE FILE
 * Patterns: Facade + Enum state machine + per-seat locks + TTL sweeper
 *
 *   javac Main.java && java Main
 */

// ========== DOMAIN ==========

enum SeatStatus {
    AVAILABLE,
    HELD,
    CONFIRMED
}

enum SeatCategory {
    REGULAR(100.0),
    PREMIUM(150.0),
    VIP(250.0);

    private final double price;

    SeatCategory(double price) {
        this.price = price;
    }

    double getPrice() {
        return price;
    }
}

class Seat {
    private final String seatId;
    private final SeatCategory category;
    private volatile SeatStatus status;
    private final ReentrantLock lock = new ReentrantLock();

    Seat(String seatId, SeatCategory category) {
        this.seatId = seatId;
        this.category = category;
        this.status = SeatStatus.AVAILABLE;
    }

    String getSeatId() { return seatId; }
    SeatCategory getCategory() { return category; }
    SeatStatus getStatus() { return status; }
    ReentrantLock getLock() { return lock; }

    void setStatus(SeatStatus status) {
        this.status = status;
    }
}

class SeatLock {
    private final String seatId;
    private final String userId;
    private final long lockTimeoutNano; // monotonic cutoff

    SeatLock(String seatId, String userId, long ttlMillis) {
        this.seatId = seatId;
        this.userId = userId;
        this.lockTimeoutNano = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ttlMillis);
    }

    String getSeatId() { return seatId; }
    String getUserId() { return userId; }

    boolean isExpired() {
        return System.nanoTime() >= lockTimeoutNano;
    }
}

class Booking {
    private final String bookingId;
    private final String userId;
    private final List<Seat> bookedSeats;
    private final double totalAmount;

    Booking(String userId, List<Seat> bookedSeats) {
        this.bookingId = UUID.randomUUID().toString();
        this.userId = userId;
        this.bookedSeats = bookedSeats;
        this.totalAmount = bookedSeats.stream()
                .mapToDouble(s -> s.getCategory().getPrice())
                .sum();
    }

    String getBookingId() { return bookingId; }
    String getUserId() { return userId; }
    double getTotalAmount() { return totalAmount; }
}

// ========== SEAT LOCK MANAGER + SWEEPER ==========

class SeatLockManager {
    private final Map<String, SeatLock> activeLocks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeperDaemon =
            Executors.newSingleThreadScheduledExecutor();

    SeatLockManager(Map<String, Seat> seatInventory, long checkIntervalMs) {
        sweeperDaemon.scheduleAtFixedRate(
                () -> sweepExpiredLocks(seatInventory),
                checkIntervalMs,
                checkIntervalMs,
                TimeUnit.MILLISECONDS);
    }

    boolean acquireLock(Seat seat, String userId, long ttlMillis) {
        seat.getLock().lock();
        try {
            if (seat.getStatus() != SeatStatus.AVAILABLE) {
                return false;
            }
            seat.setStatus(SeatStatus.HELD);
            activeLocks.put(seat.getSeatId(), new SeatLock(seat.getSeatId(), userId, ttlMillis));
            return true;
        } finally {
            seat.getLock().unlock();
        }
    }

    boolean validateLockOwner(String seatId, String userId) {
        SeatLock lock = activeLocks.get(seatId);
        return lock != null && !lock.isExpired() && lock.getUserId().equals(userId);
    }

    /** Drop hold metadata after confirm (seat stays CONFIRMED). */
    void clearLock(String seatId) {
        activeLocks.remove(seatId);
    }

    void releaseLock(Seat seat) {
        seat.getLock().lock();
        try {
            activeLocks.remove(seat.getSeatId());
            if (seat.getStatus() == SeatStatus.HELD) {
                seat.setStatus(SeatStatus.AVAILABLE);
            }
        } finally {
            seat.getLock().unlock();
        }
    }

    private void sweepExpiredLocks(Map<String, Seat> seatInventory) {
        for (Map.Entry<String, SeatLock> entry : activeLocks.entrySet()) {
            SeatLock lock = entry.getValue();
            if (lock.isExpired()) {
                Seat seat = seatInventory.get(lock.getSeatId());
                if (seat != null) {
                    releaseLock(seat);
                    System.out.println("[Sweeper] Expired lock released for Seat: " + lock.getSeatId());
                }
            }
        }
    }

    void shutdown() {
        sweeperDaemon.shutdownNow();
    }
}

// ========== RESERVATION FACADE ==========

class ReservationService {
    private final Map<String, Seat> seatInventory = new ConcurrentHashMap<>();
    private final Map<String, Booking> confirmedBookings = new ConcurrentHashMap<>();
    private final SeatLockManager lockManager;
    private final long defaultLockTtlMs;

    ReservationService(List<Seat> initialSeats, long defaultLockTtlMs) {
        this.defaultLockTtlMs = defaultLockTtlMs;
        for (Seat s : initialSeats) {
            seatInventory.put(s.getSeatId(), s);
        }
        this.lockManager = new SeatLockManager(seatInventory, 1000); // sweep every 1s
    }

    List<Seat> getAvailableSeats() {
        List<Seat> available = new ArrayList<>();
        for (Seat seat : seatInventory.values()) {
            if (seat.getStatus() == SeatStatus.AVAILABLE) {
                available.add(seat);
            }
        }
        return available;
    }

    /** Hold using the service default TTL. */
    boolean holdSeats(List<String> seatIds, String userId) {
        return holdSeats(seatIds, userId, defaultLockTtlMs);
    }

    /**
     * All-or-nothing temporary hold.
     * Sorts seat IDs before locking to prevent multi-seat deadlocks.
     */
    boolean holdSeats(List<String> seatIds, String userId, long ttlMillis) {
        List<Seat> acquiredSeats = new ArrayList<>();
        List<String> sortedSeatIds = new ArrayList<>(seatIds);
        Collections.sort(sortedSeatIds);

        for (String seatId : sortedSeatIds) {
            Seat seat = seatInventory.get(seatId);
            if (seat == null || !lockManager.acquireLock(seat, userId, ttlMillis)) {
                for (Seat acquired : acquiredSeats) {
                    lockManager.releaseLock(acquired);
                }
                return false;
            }
            acquiredSeats.add(seat);
        }
        System.out.println("[ReservationService] User " + userId + " successfully held seats: " + seatIds);
        return true;
    }

    Booking confirmBooking(List<String> seatIds, String userId) {
        List<String> sortedSeatIds = new ArrayList<>(seatIds);
        Collections.sort(sortedSeatIds); // same lock order as hold

        List<Seat> seatsToConfirm = new ArrayList<>();
        for (String seatId : sortedSeatIds) {
            if (!lockManager.validateLockOwner(seatId, userId)) {
                throw new IllegalStateException("Hold expired or invalid lock owner for seat: " + seatId);
            }
            seatsToConfirm.add(seatInventory.get(seatId));
        }

        for (Seat seat : seatsToConfirm) {
            seat.getLock().lock();
        }
        try {
            for (Seat seat : seatsToConfirm) {
                seat.setStatus(SeatStatus.CONFIRMED);
                lockManager.clearLock(seat.getSeatId());
            }
            Booking booking = new Booking(userId, seatsToConfirm);
            confirmedBookings.put(booking.getBookingId(), booking);
            System.out.println("[ReservationService] Booking CONFIRMED! Id: "
                    + booking.getBookingId() + " Total: $" + booking.getTotalAmount());
            return booking;
        } finally {
            for (Seat seat : seatsToConfirm) {
                seat.getLock().unlock();
            }
        }
    }

    void shutdown() {
        lockManager.shutdown();
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) throws InterruptedException {
        List<Seat> seats = Arrays.asList(
                new Seat("A1", SeatCategory.VIP),
                new Seat("A2", SeatCategory.VIP),
                new Seat("B1", SeatCategory.REGULAR)
        );

        ReservationService service = new ReservationService(seats, 1500);

        System.out.println("=== TEST 1: TEMPORARY HOLD & CONFIRMATION ===");
        boolean held = service.holdSeats(Arrays.asList("A1", "A2"), "user_alice", 2000);
        System.out.println("Alice hold result: " + held);

        boolean bobHeld = service.holdSeats(Arrays.asList("A1"), "user_bob", 2000);
        System.out.println("Bob hold result (Conflict): " + bobHeld);

        Booking booking = service.confirmBooking(Arrays.asList("A1", "A2"), "user_alice");
        System.out.println("Alice booking id: " + booking.getBookingId());

        System.out.println("\n=== TEST 2: LOCK EXPIRATION SWEEPER ===");
        service.holdSeats(Arrays.asList("B1"), "user_bob", 800);

        System.out.println("Sleeping 1.2s to trigger TTL expiration...");
        Thread.sleep(1200);

        System.out.println("Available seats count: " + service.getAvailableSeats().size());

        service.shutdown();
    }
}
