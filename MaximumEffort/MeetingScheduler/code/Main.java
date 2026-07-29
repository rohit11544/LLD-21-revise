import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Meeting Scheduler — ONE FILE (interview style)
 * Patterns: Facade + Strategy (room selection)
 *
 * Fixes vs naive draft:
 * - Atomic book: lock room + all users (sorted ids) then re-check then commit
 * - No nested lock helpers while holding room lock
 * - TreeSet scan stops early (still O(N) worst case; Interval Tree = follow-up)
 *
 *   javac Main.java && java Main
 */

// ========== INTERVAL ==========

class Interval implements Comparable<Interval> {
    private final long startTime; // half-open [start, end)
    private final long endTime;

    Interval(long startTime, long endTime) {
        if (startTime >= endTime) {
            throw new IllegalArgumentException("startTime must be < endTime");
        }
        this.startTime = startTime;
        this.endTime = endTime;
    }

    long getStartTime() { return startTime; }
    long getEndTime() { return endTime; }

    /** A overlaps B iff A.start < B.end && B.start < A.end */
    boolean overlapsWith(Interval other) {
        return this.startTime < other.endTime && other.startTime < this.endTime;
    }

    @Override
    public int compareTo(Interval other) {
        int byStart = Long.compare(this.startTime, other.startTime);
        return byStart != 0 ? byStart : Long.compare(this.endTime, other.endTime);
    }

    @Override
    public String toString() {
        return "[" + startTime + "," + endTime + ")";
    }
}

// ========== USER / ROOM / MEETING ==========

class User {
    private final String userId;
    private final String name;
    private final TreeSet<Interval> scheduledIntervals = new TreeSet<>();
    private final ReentrantLock lock = new ReentrantLock();

    User(String userId, String name) {
        this.userId = userId;
        this.name = name;
    }

    String getUserId() { return userId; }
    String getName() { return name; }
    ReentrantLock getLock() { return lock; }

    /** Call only while holding this user's lock. */
    boolean hasConflictLocked(Interval interval) {
        return IntervalSearch.hasOverlap(scheduledIntervals, interval);
    }

    /** Call only while holding this user's lock. */
    void addIntervalLocked(Interval interval) {
        scheduledIntervals.add(interval);
    }

    boolean isAvailable(Interval interval) {
        lock.lock();
        try {
            return !hasConflictLocked(interval);
        } finally {
            lock.unlock();
        }
    }
}

class MeetingRoom {
    private final String roomId;
    private final String name;
    private final int capacity;
    private final TreeSet<Interval> bookedIntervals = new TreeSet<>();
    private final ReentrantLock lock = new ReentrantLock();

    MeetingRoom(String roomId, String name, int capacity) {
        this.roomId = roomId;
        this.name = name;
        this.capacity = capacity;
    }

    String getRoomId() { return roomId; }
    String getName() { return name; }
    int getCapacity() { return capacity; }
    ReentrantLock getLock() { return lock; }

    /** Call only while holding this room's lock. */
    boolean hasConflictLocked(Interval interval) {
        return IntervalSearch.hasOverlap(bookedIntervals, interval);
    }

    /** Call only while holding this room's lock. */
    void bookIntervalLocked(Interval interval) {
        bookedIntervals.add(interval);
    }

    boolean isAvailable(Interval interval) {
        lock.lock();
        try {
            return !hasConflictLocked(interval);
        } finally {
            lock.unlock();
        }
    }
}

/** TreeSet ordered by start; early-exit when booked.start >= query.end. Worst case still O(N). */
class IntervalSearch {
    static boolean hasOverlap(TreeSet<Interval> booked, Interval query) {
        for (Interval existing : booked) {
            if (existing.getStartTime() >= query.getEndTime()) {
                break;
            }
            if (existing.overlapsWith(query)) {
                return true;
            }
        }
        return false;
    }
}

class Meeting {
    private final String meetingId;
    private final String title;
    private final Interval interval;
    private final MeetingRoom room;
    private final User organizer;
    private final List<User> participants;

    Meeting(String title, Interval interval, MeetingRoom room, User organizer, List<User> participants) {
        this.meetingId = UUID.randomUUID().toString();
        this.title = title;
        this.interval = interval;
        this.room = room;
        this.organizer = organizer;
        this.participants = participants;
    }

    String getMeetingId() { return meetingId; }
    String getTitle() { return title; }
    Interval getInterval() { return interval; }
    MeetingRoom getRoom() { return room; }
}

// ========== ROOM SELECTION STRATEGY ==========

interface RoomSelectionStrategy {
    MeetingRoom selectRoom(List<MeetingRoom> rooms, Interval interval, int requiredCapacity);
}

/** First room that fits capacity + is free. */
class FirstFitRoomStrategy implements RoomSelectionStrategy {
    @Override
    public MeetingRoom selectRoom(List<MeetingRoom> rooms, Interval interval, int requiredCapacity) {
        for (MeetingRoom room : rooms) {
            if (room.getCapacity() >= requiredCapacity && room.isAvailable(interval)) {
                return room;
            }
        }
        return null;
    }
}

/** Smallest capacity that still fits (least waste). */
class BestFitRoomStrategy implements RoomSelectionStrategy {
    @Override
    public MeetingRoom selectRoom(List<MeetingRoom> rooms, Interval interval, int requiredCapacity) {
        MeetingRoom best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (MeetingRoom room : rooms) {
            if (room.getCapacity() < requiredCapacity) continue;
            if (!room.isAvailable(interval)) continue;
            int diff = room.getCapacity() - requiredCapacity;
            if (diff < bestDiff) {
                bestDiff = diff;
                best = room;
            }
        }
        return best;
    }
}

// ========== FACADE ==========

class MeetingSchedulerService {
    private final List<MeetingRoom> rooms = new CopyOnWriteArrayList<>();
    private final Map<String, User> userRegistry = new ConcurrentHashMap<>();
    private final Map<String, Meeting> activeMeetings = new ConcurrentHashMap<>();
    private final RoomSelectionStrategy roomStrategy;

    MeetingSchedulerService(RoomSelectionStrategy roomStrategy) {
        this.roomStrategy = roomStrategy;
    }

    void addRoom(MeetingRoom room) { rooms.add(room); }

    void registerUser(User user) { userRegistry.put(user.getUserId(), user); }

    /**
     * Book meeting:
     * 1) soft-check users + strategy pick room
     * 2) lock room + users (userId order) → re-validate → commit
     */
    Meeting bookMeeting(String title, Interval interval, int requiredCapacity,
                        User organizer, List<User> participants) {
        List<User> allUsers = uniqueUsers(organizer, participants);

        for (User user : allUsers) {
            if (!user.isAvailable(interval)) {
                System.out.println("[Booking Failed] User " + user.getName() + " busy at " + interval);
                return null;
            }
        }

        MeetingRoom selectedRoom = roomStrategy.selectRoom(rooms, interval, requiredCapacity);
        if (selectedRoom == null) {
            System.out.println("[Booking Failed] No room for capacity " + requiredCapacity + " at " + interval);
            return null;
        }

        // Stable lock order: room first, then users by userId (avoids deadlock)
        List<User> lockedUsers = new ArrayList<>(allUsers);
        lockedUsers.sort(Comparator.comparing(User::getUserId));

        selectedRoom.getLock().lock();
        for (User user : lockedUsers) {
            user.getLock().lock();
        }
        try {
            if (selectedRoom.hasConflictLocked(interval)) {
                System.out.println("[Booking Failed] Concurrent room conflict: " + selectedRoom.getName());
                return null;
            }
            for (User user : lockedUsers) {
                if (user.hasConflictLocked(interval)) {
                    System.out.println("[Booking Failed] Concurrent user conflict: " + user.getName());
                    return null;
                }
            }

            selectedRoom.bookIntervalLocked(interval);
            for (User user : lockedUsers) {
                user.addIntervalLocked(interval);
            }

            Meeting meeting = new Meeting(title, interval, selectedRoom, organizer, participants);
            activeMeetings.put(meeting.getMeetingId(), meeting);
            System.out.println("[Booking Success] '" + title + "' -> " + selectedRoom.getName()
                    + " (" + selectedRoom.getCapacity() + " pax) " + interval);
            return meeting;
        } finally {
            for (int i = lockedUsers.size() - 1; i >= 0; i--) {
                lockedUsers.get(i).getLock().unlock();
            }
            selectedRoom.getLock().unlock();
        }
    }

    private static List<User> uniqueUsers(User organizer, List<User> participants) {
        Map<String, User> byId = new ConcurrentHashMap<>();
        byId.put(organizer.getUserId(), organizer);
        for (User p : participants) {
            byId.put(p.getUserId(), p);
        }
        return new ArrayList<>(byId.values());
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) {
        MeetingSchedulerService scheduler = new MeetingSchedulerService(new BestFitRoomStrategy());

        MeetingRoom smallRoom = new MeetingRoom("R1", "Small Boardroom", 4);
        MeetingRoom largeRoom = new MeetingRoom("R2", "Grand Auditorium", 20);
        scheduler.addRoom(smallRoom);
        scheduler.addRoom(largeRoom);

        User alice = new User("U1", "Alice");
        User bob = new User("U2", "Bob");
        scheduler.registerUser(alice);
        scheduler.registerUser(bob);

        Interval slot1 = new Interval(1000, 2000);
        Interval slot1Overlap = new Interval(1500, 2500);
        Interval slot2 = new Interval(3000, 4000);

        System.out.println("=== TEST 1: BEST-FIT ROOM ===");
        scheduler.bookMeeting("Sprint Sync", slot1, 3, alice, Collections.singletonList(bob));
        // capacity 3 → Small Boardroom (4) preferred over Auditorium (20)

        System.out.println("\n=== TEST 2: OVERLAP CONFLICT ===");
        scheduler.bookMeeting("Design Review", slot1Overlap, 2, alice, Collections.singletonList(bob));

        System.out.println("\n=== TEST 3: NON-OVERLAPPING ===");
        scheduler.bookMeeting("Architecture Planning", slot2, 2, alice, Collections.singletonList(bob));

        System.out.println("\n=== TEST 4: FIRST-FIT STRATEGY ===");
        MeetingSchedulerService firstFit = new MeetingSchedulerService(new FirstFitRoomStrategy());
        firstFit.addRoom(smallRoom);
        firstFit.addRoom(largeRoom);
        User carol = new User("U3", "Carol");
        firstFit.registerUser(carol);
        // slot free on large only if small still busy — use fresh rooms for clarity
        MeetingRoom rA = new MeetingRoom("RA", "Room A", 10);
        MeetingRoom rB = new MeetingRoom("RB", "Room B", 10);
        MeetingSchedulerService ff = new MeetingSchedulerService(new FirstFitRoomStrategy());
        ff.addRoom(rA);
        ff.addRoom(rB);
        User dave = new User("U4", "Dave");
        ff.bookMeeting("Standup", new Interval(5000, 6000), 5, dave, Collections.emptyList());
        // FirstFit picks Room A (first in list)
    }
}
