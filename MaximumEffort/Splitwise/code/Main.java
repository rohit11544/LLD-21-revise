import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Splitwise — ONE FILE (interview style)
 * Patterns: Facade + Strategy (Equal / Exact / Percent)
 *
 * Fixes vs naive draft:
 * - Money as long cents (no double drift)
 * - Equal split: base share + remainder cents distributed
 * - Heap nodes are BalanceNode (not mutated Map.Entry)
 * - Exact/Percent validate sizes + totals
 *
 *   javac Main.java && java Main
 */

// ========== MONEY (CENTS) ==========

class Money {
    static long dollarsToCents(double dollars) {
        return Math.round(dollars * 100.0);
    }

    static String format(long cents) {
        long abs = Math.abs(cents);
        String sign = cents < 0 ? "-" : "";
        return sign + "$" + (abs / 100) + "." + String.format("%02d", abs % 100);
    }
}

// ========== DOMAIN ==========

class User {
    private final String userId;
    private final String name;

    User(String userId, String name) {
        this.userId = userId;
        this.name = name;
    }

    String getUserId() { return userId; }
    String getName() { return name; }
}

/** One participant's share of an expense (amount in cents). */
class Split {
    private final User user;
    private long amountCents;

    Split(User user) {
        this.user = user;
    }

    User getUser() { return user; }
    long getAmountCents() { return amountCents; }
    void setAmountCents(long amountCents) { this.amountCents = amountCents; }
}

// ========== SPLIT STRATEGIES ==========

interface SplitStrategy {
    /**
     * @param values Exact: cents per person; Percent: percent points (must sum 100);
     *               Equal: ignored
     */
    void calculateSplits(long totalCents, List<Split> splits, List<Long> values);
}

class EqualSplitStrategy implements SplitStrategy {
    @Override
    public void calculateSplits(long totalCents, List<Split> splits, List<Long> values) {
        int n = splits.size();
        if (n == 0) throw new IllegalArgumentException("No participants");
        long base = totalCents / n;
        long remainder = totalCents % n; // leftover pennies
        for (int i = 0; i < n; i++) {
            // First 'remainder' people get +1 cent so sum == total
            splits.get(i).setAmountCents(base + (i < remainder ? 1 : 0));
        }
    }
}

class ExactSplitStrategy implements SplitStrategy {
    @Override
    public void calculateSplits(long totalCents, List<Split> splits, List<Long> values) {
        if (values == null || values.size() != splits.size()) {
            throw new IllegalArgumentException("Exact values size must match splits");
        }
        long sum = 0;
        for (Long v : values) sum += v;
        if (sum != totalCents) {
            throw new IllegalArgumentException(
                    "Exact sum " + sum + " cents != total " + totalCents + " cents");
        }
        for (int i = 0; i < splits.size(); i++) {
            splits.get(i).setAmountCents(values.get(i));
        }
    }
}

class PercentSplitStrategy implements SplitStrategy {
    @Override
    public void calculateSplits(long totalCents, List<Split> splits, List<Long> values) {
        if (values == null || values.size() != splits.size()) {
            throw new IllegalArgumentException("Percent values size must match splits");
        }
        long percentSum = 0;
        for (Long v : values) percentSum += v;
        if (percentSum != 100) {
            throw new IllegalArgumentException("Percentages sum to " + percentSum + ", expected 100");
        }

        long assigned = 0;
        for (int i = 0; i < splits.size(); i++) {
            long share;
            if (i == splits.size() - 1) {
                share = totalCents - assigned; // last gets remainder so sum == total
            } else {
                share = (totalCents * values.get(i)) / 100;
                assigned += share;
            }
            splits.get(i).setAmountCents(share);
        }
    }
}

// ========== BALANCE GRAPH + SIMPLIFY ==========

class BalanceSheet {
    /** debtorId -> (creditorId -> cents owed) */
    private final Map<String, Map<String, Long>> balances = new HashMap<>();

    void addDebt(String debtorId, String creditorId, long amountCents) {
        if (debtorId.equals(creditorId) || amountCents <= 0) return;
        balances.computeIfAbsent(debtorId, k -> new HashMap<>())
                .merge(creditorId, amountCents, Long::sum);
    }

    Map<String, Map<String, Long>> getBalances() { return balances; }
}

class Transaction {
    private final String fromUser;
    private final String toUser;
    private final long amountCents;

    Transaction(String fromUser, String toUser, long amountCents) {
        this.fromUser = fromUser;
        this.toUser = toUser;
        this.amountCents = amountCents;
    }

    @Override
    public String toString() {
        return "User (" + fromUser + ") pays User (" + toUser + ") -> " + Money.format(amountCents);
    }
}

class BalanceNode {
    final String userId;
    long netCents; // negative = owes, positive = owed

    BalanceNode(String userId, long netCents) {
        this.userId = userId;
        this.netCents = netCents;
    }
}

class DebtSimplifier {
    /**
     * Net each user, then greedy match largest debtor with largest creditor.
     * At most N-1 transactions when nets sum to 0.
     */
    static List<Transaction> simplify(BalanceSheet sheet) {
        Map<String, Long> net = new HashMap<>();
        for (Map.Entry<String, Map<String, Long>> e : sheet.getBalances().entrySet()) {
            String debtor = e.getKey();
            for (Map.Entry<String, Long> edge : e.getValue().entrySet()) {
                String creditor = edge.getKey();
                long amt = edge.getValue();
                net.put(debtor, net.getOrDefault(debtor, 0L) - amt);
                net.put(creditor, net.getOrDefault(creditor, 0L) + amt);
            }
        }

        PriorityQueue<BalanceNode> debtors = new PriorityQueue<>(
                (a, b) -> Long.compare(a.netCents, b.netCents)); // most negative first
        PriorityQueue<BalanceNode> creditors = new PriorityQueue<>(
                (a, b) -> Long.compare(b.netCents, a.netCents)); // most positive first

        for (Map.Entry<String, Long> e : net.entrySet()) {
            if (e.getValue() < 0) debtors.add(new BalanceNode(e.getKey(), e.getValue()));
            else if (e.getValue() > 0) creditors.add(new BalanceNode(e.getKey(), e.getValue()));
        }

        List<Transaction> result = new ArrayList<>();
        while (!debtors.isEmpty() && !creditors.isEmpty()) {
            BalanceNode debtor = debtors.poll();
            BalanceNode creditor = creditors.poll();

            long debt = -debtor.netCents;
            long credit = creditor.netCents;
            long settled = Math.min(debt, credit);
            result.add(new Transaction(debtor.userId, creditor.userId, settled));

            long remDebt = debt - settled;
            long remCredit = credit - settled;
            if (remDebt > 0) debtors.add(new BalanceNode(debtor.userId, -remDebt));
            if (remCredit > 0) creditors.add(new BalanceNode(creditor.userId, remCredit));
        }
        return result;
    }
}

// ========== GROUP + FACADE ==========

class Group {
    private final String groupId;
    private final String name;
    private final Map<String, User> members = new ConcurrentHashMap<>();
    private final BalanceSheet balanceSheet = new BalanceSheet();
    private final ReentrantLock lock = new ReentrantLock();

    Group(String groupId, String name) {
        this.groupId = groupId;
        this.name = name;
    }

    String getGroupId() { return groupId; }
    String getName() { return name; }
    void addMember(User user) { members.put(user.getUserId(), user); }
    Map<String, User> getMembers() { return members; }
    BalanceSheet getBalanceSheet() { return balanceSheet; }
    ReentrantLock getLock() { return lock; }
}

class SplitwiseService {
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, Group> groups = new ConcurrentHashMap<>();

    void registerUser(User user) { users.put(user.getUserId(), user); }

    void createGroup(Group group) { groups.put(group.getGroupId(), group); }

    void addExpense(String groupId, String paidByUserId, long totalCents,
                    List<Split> splits, SplitStrategy strategy, List<Long> values) {
        Group group = groups.get(groupId);
        if (group == null) throw new IllegalArgumentException("Group not found");

        strategy.calculateSplits(totalCents, splits, values);

        group.getLock().lock();
        try {
            for (Split split : splits) {
                String debtorId = split.getUser().getUserId();
                if (!debtorId.equals(paidByUserId)) {
                    group.getBalanceSheet().addDebt(debtorId, paidByUserId, split.getAmountCents());
                }
            }
            User payer = group.getMembers().get(paidByUserId);
            String payerName = payer != null ? payer.getName() : paidByUserId;
            System.out.println("[Expense Added] " + Money.format(totalCents)
                    + " paid by " + payerName + " in " + group.getName());
        } finally {
            group.getLock().unlock();
        }
    }

    List<Transaction> getSimplifiedGroupDebts(String groupId) {
        Group group = groups.get(groupId);
        if (group == null) throw new IllegalArgumentException("Group not found");
        group.getLock().lock();
        try {
            return DebtSimplifier.simplify(group.getBalanceSheet());
        } finally {
            group.getLock().unlock();
        }
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) {
        SplitwiseService splitwise = new SplitwiseService();

        User alice = new User("U1", "Alice");
        User bob = new User("U2", "Bob");
        User charlie = new User("U3", "Charlie");
        User dave = new User("U4", "Dave");
        splitwise.registerUser(alice);
        splitwise.registerUser(bob);
        splitwise.registerUser(charlie);
        splitwise.registerUser(dave);

        Group goaTrip = new Group("G1", "Goa Trip");
        goaTrip.addMember(alice);
        goaTrip.addMember(bob);
        goaTrip.addMember(charlie);
        goaTrip.addMember(dave);
        splitwise.createGroup(goaTrip);

        System.out.println("=== TEST 1: EQUAL SPLIT ($100 / 4) ===");
        List<Split> splits1 = Arrays.asList(
                new Split(alice), new Split(bob), new Split(charlie), new Split(dave));
        splitwise.addExpense("G1", "U1", Money.dollarsToCents(100),
                splits1, new EqualSplitStrategy(), Collections.emptyList());

        System.out.println("\n=== TEST 2: EXACT SPLIT ($60 taxi) ===");
        List<Split> splits2 = Arrays.asList(new Split(charlie), new Split(dave));
        splitwise.addExpense("G1", "U2", Money.dollarsToCents(60),
                splits2, new ExactSplitStrategy(),
                Arrays.asList(Money.dollarsToCents(40), Money.dollarsToCents(20)));

        System.out.println("\n=== TEST 3: EQUAL WITH REMAINDER ($100 / 3) ===");
        Group lunch = new Group("G2", "Lunch");
        lunch.addMember(alice);
        lunch.addMember(bob);
        lunch.addMember(charlie);
        splitwise.createGroup(lunch);
        List<Split> splits3 = Arrays.asList(new Split(alice), new Split(bob), new Split(charlie));
        splitwise.addExpense("G2", "U1", Money.dollarsToCents(100),
                splits3, new EqualSplitStrategy(), Collections.emptyList());
        System.out.println("Shares (cents): "
                + splits3.get(0).getAmountCents() + ", "
                + splits3.get(1).getAmountCents() + ", "
                + splits3.get(2).getAmountCents()
                + " sum=" + (splits3.get(0).getAmountCents()
                + splits3.get(1).getAmountCents()
                + splits3.get(2).getAmountCents()));

        System.out.println("\n=== TEST 4: GREEDY SIMPLIFY (Goa Trip) ===");
        for (Transaction t : splitwise.getSimplifiedGroupDebts("G1")) {
            System.out.println(t);
        }
    }
}
