import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Elevator Control System — ONE FILE
 * Patterns: Facade + Strategy (dispatcher) + LOOK dual heaps + enum direction/status
 *
 *   javac Main.java && java Main
 */

// ========== DOMAIN ==========

enum Direction {
    UP,
    DOWN,
    IDLE
}

enum ElevatorStatus {
    MOVING,
    STOPPED,
    OUT_OF_SERVICE
}

class Request {
    private final int sourceFloor;
    private final Direction direction;

    Request(int sourceFloor, Direction direction) {
        this.sourceFloor = sourceFloor;
        this.direction = direction;
    }

    int getSourceFloor() { return sourceFloor; }
    Direction getDirection() { return direction; }
}

// ========== ELEVATOR CAR (LOOK QUEUES) ==========

class ElevatorCar {
    private final int id;
    private volatile int currentFloor;
    private volatile Direction direction;
    private volatile ElevatorStatus status;

    // LOOK: up = min-heap, down = max-heap
    private final PriorityQueue<Integer> upRequests = new PriorityQueue<>();
    private final PriorityQueue<Integer> downRequests = new PriorityQueue<>(Collections.reverseOrder());
    private final Set<Integer> upSet = new HashSet<>();
    private final Set<Integer> downSet = new HashSet<>();

    /** Hall-panel preferred direction when we stop at that floor for pickup. */
    private final Map<Integer, Direction> hallDirectionAtFloor = new HashMap<>();

    private final ReentrantLock lock = new ReentrantLock();

    ElevatorCar(int id, int initialFloor) {
        this.id = id;
        this.currentFloor = initialFloor;
        this.direction = Direction.IDLE;
        this.status = ElevatorStatus.STOPPED;
    }

    int getId() { return id; }
    int getCurrentFloor() { return currentFloor; }
    Direction getDirection() { return direction; }
    ElevatorStatus getStatus() { return status; }

    /** Internal car button — destination only. */
    void addDestinationFloor(int floor) {
        lock.lock();
        try {
            enqueueFloor(floor);
        } finally {
            lock.unlock();
        }
    }

    /**
     * External hall call — stop at floor AND remember intended travel direction
     * so after doors open we continue LOOK in that direction when sensible.
     */
    void addHallRequest(int floor, Direction hallDirection) {
        lock.lock();
        try {
            if (hallDirection != Direction.IDLE) {
                hallDirectionAtFloor.put(floor, hallDirection);
            }
            enqueueFloor(floor);
        } finally {
            lock.unlock();
        }
    }

    private void enqueueFloor(int floor) {
        if (floor == currentFloor) {
            openDoors();
            applyHallDirectionIfPresent(floor);
            return;
        }

        if (floor > currentFloor) {
            if (upSet.add(floor)) {
                upRequests.add(floor);
            }
            if (direction == Direction.IDLE) {
                direction = Direction.UP;
            }
        } else {
            if (downSet.add(floor)) {
                downRequests.add(floor);
            }
            if (direction == Direction.IDLE) {
                direction = Direction.DOWN;
            }
        }
        status = ElevatorStatus.MOVING;
    }

    /** One floor tick — LOOK: serve current direction, then reverse. */
    void step() {
        lock.lock();
        try {
            if (direction == Direction.UP) {
                if (!upRequests.isEmpty()) {
                    currentFloor++;
                    System.out.println("[Elevator " + id + "] Moving UP -> Floor " + currentFloor);
                    if (!upRequests.isEmpty() && upRequests.peek() == currentFloor) {
                        upSet.remove(upRequests.poll());
                        openDoors();
                        applyHallDirectionIfPresent(currentFloor);
                    }
                }
                if (upRequests.isEmpty()) {
                    if (!downRequests.isEmpty()) {
                        direction = Direction.DOWN;
                    } else {
                        direction = Direction.IDLE;
                        status = ElevatorStatus.STOPPED;
                    }
                }
            } else if (direction == Direction.DOWN) {
                if (!downRequests.isEmpty()) {
                    currentFloor--;
                    System.out.println("[Elevator " + id + "] Moving DOWN -> Floor " + currentFloor);
                    if (!downRequests.isEmpty() && downRequests.peek() == currentFloor) {
                        downSet.remove(downRequests.poll());
                        openDoors();
                        applyHallDirectionIfPresent(currentFloor);
                    }
                }
                if (downRequests.isEmpty()) {
                    if (!upRequests.isEmpty()) {
                        direction = Direction.UP;
                    } else {
                        direction = Direction.IDLE;
                        status = ElevatorStatus.STOPPED;
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void applyHallDirectionIfPresent(int floor) {
        Direction hallDir = hallDirectionAtFloor.remove(floor);
        if (hallDir == null || hallDir == Direction.IDLE) {
            return;
        }
        // Prefer continuing in hall direction if that side still has work, or we just became idle
        if (hallDir == Direction.UP && !upRequests.isEmpty()) {
            direction = Direction.UP;
            status = ElevatorStatus.MOVING;
        } else if (hallDir == Direction.DOWN && !downRequests.isEmpty()) {
            direction = Direction.DOWN;
            status = ElevatorStatus.MOVING;
        } else if (direction == Direction.IDLE) {
            direction = hallDir;
            // may immediately reverse on next steps if that queue empty — OK for LLD
        }
    }

    private void openDoors() {
        System.out.println("   [Elevator " + id + "] *** DOORS OPEN AT FLOOR " + currentFloor + " ***");
    }

    int getPendingRequestCount() {
        lock.lock();
        try {
            return upRequests.size() + downRequests.size();
        } finally {
            lock.unlock();
        }
    }
}

// ========== DISPATCHER STRATEGY ==========

interface ElevatorDispatcherStrategy {
    ElevatorCar selectElevator(List<ElevatorCar> elevators, Request request);
}

/**
 * Heuristic assigner (not the LOOK car queues themselves).
 * Prefers IDLE / same-direction cars already heading toward the hall floor.
 */
class LookDispatcherStrategy implements ElevatorDispatcherStrategy {
    @Override
    public ElevatorCar selectElevator(List<ElevatorCar> elevators, Request request) {
        ElevatorCar bestCar = null;
        int bestScore = Integer.MAX_VALUE;
        int floor = request.getSourceFloor();
        Direction want = request.getDirection();

        for (ElevatorCar car : elevators) {
            if (car.getStatus() == ElevatorStatus.OUT_OF_SERVICE) {
                continue;
            }

            int distance = Math.abs(car.getCurrentFloor() - floor);
            int score = distance;

            Direction carDir = car.getDirection();
            if (carDir == Direction.IDLE) {
                score += 0; // good candidate
            } else if (carDir == want) {
                // Same direction and still moving toward / not past the hall floor
                boolean toward =
                        (want == Direction.UP && car.getCurrentFloor() <= floor)
                                || (want == Direction.DOWN && car.getCurrentFloor() >= floor);
                score += toward ? -2 : 15; // prefer on-path same direction
            } else {
                score += 12; // opposite direction penalty
            }

            score += car.getPendingRequestCount() * 2;

            if (score < bestScore) {
                bestScore = score;
                bestCar = car;
            }
        }
        return bestCar != null ? bestCar : elevators.get(0);
    }
}

// ========== FACADE ==========

class ElevatorSystemFacade {
    private final List<ElevatorCar> elevators = new ArrayList<>();
    private final ElevatorDispatcherStrategy dispatcherStrategy;

    ElevatorSystemFacade(int numCars, ElevatorDispatcherStrategy strategy) {
        this.dispatcherStrategy = strategy;
        for (int i = 1; i <= numCars; i++) {
            elevators.add(new ElevatorCar(i, 1));
        }
    }

    List<ElevatorCar> getElevators() { return elevators; }

    /** External hall panel. */
    void requestElevator(int floor, Direction direction) {
        Request request = new Request(floor, direction);
        ElevatorCar selected = dispatcherStrategy.selectElevator(elevators, request);
        System.out.println("[Hall Call] Floor " + floor + " (" + direction
                + ") -> Elevator " + selected.getId());
        selected.addHallRequest(floor, direction);
    }

    /** Internal car panel. */
    void pressFloorButton(int elevatorId, int destinationFloor) {
        ElevatorCar car = elevators.get(elevatorId - 1);
        System.out.println("[Car Button] Elevator " + elevatorId
                + " -> Floor " + destinationFloor);
        car.addDestinationFloor(destinationFloor);
    }

    /** Tick simulation (production: per-car worker thread). */
    void stepAll() {
        for (ElevatorCar car : elevators) {
            car.step();
        }
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) {
        ElevatorSystemFacade system = new ElevatorSystemFacade(2, new LookDispatcherStrategy());

        System.out.println("=== TEST 1: HALL CALL & CAR BUTTON ===");
        system.requestElevator(5, Direction.UP);
        for (int i = 0; i < 4; i++) {
            system.stepAll();
        }
        system.pressFloorButton(1, 8);
        for (int i = 0; i < 4; i++) {
            system.stepAll();
        }

        System.out.println("\n=== TEST 2: OPPOSITE / SECOND CAR ===");
        system.requestElevator(2, Direction.DOWN);
        system.pressFloorButton(2, 6);
        for (int i = 0; i < 8; i++) {
            system.stepAll();
        }
    }
}
