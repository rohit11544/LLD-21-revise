import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Double-Entry Ledger & Wallet — ONE FILE (interview style)
 * Patterns: Facade + immutable journal (append-only)
 *
 * Fixes vs naive draft:
 * - Overdraft policy via account.allowNegative (wallets = false)
 * - transfer() helper builds balanced DR/CR legs
 * - Compensating reversal demo (no UPDATE/DELETE of old txns)
 * - Balances computed by replaying the journal only (no cached balance field on Account)
 * - Ordered account locks (same idea as seats / meeting users)
 *
 *   javac Main.java && java Main
 */

// ========== DOMAIN ==========

enum AccountType {
    ASSET,      // normal balance: DEBIT  (bank settlement in this LLD)
    LIABILITY   // normal balance: CREDIT (user wallets in this LLD)
}

enum EntryType {
    DEBIT,
    CREDIT
}

class Account {
    private final String accountId;
    private final String name;
    private final AccountType type;
    /** If false, post that would make signed balance < 0 is rejected. */
    private final boolean allowNegative;
    private final ReentrantLock lock = new ReentrantLock();

    Account(String accountId, String name, AccountType type, boolean allowNegative) {
        this.accountId = accountId;
        this.name = name;
        this.type = type;
        this.allowNegative = allowNegative;
    }

    static Account wallet(String id, String name) {
        // User wallet as LIABILITY; cannot go negative
        return new Account(id, name, AccountType.LIABILITY, false);
    }

    static Account asset(String id, String name) {
        return new Account(id, name, AccountType.ASSET, true);
    }

    String getAccountId() { return accountId; }
    String getName() { return name; }
    AccountType getType() { return type; }
    boolean isAllowNegative() { return allowNegative; }
    ReentrantLock getLock() { return lock; }
}

class Entry {
    private final String entryId;
    private final String accountId;
    private final EntryType type;
    private final long amountCents;

    Entry(String accountId, EntryType type, long amountCents) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Entry amount must be positive");
        }
        this.entryId = UUID.randomUUID().toString();
        this.accountId = accountId;
        this.type = type;
        this.amountCents = amountCents;
    }

    String getAccountId() { return accountId; }
    EntryType getType() { return type; }
    long getAmountCents() { return amountCents; }
}

class Transaction {
    private final String transactionId;
    private final String description;
    private final List<Entry> entries;
    private final long timestamp;

    Transaction(String description, List<Entry> entries) {
        this.transactionId = "TXN_" + UUID.randomUUID().toString().substring(0, 8);
        this.description = description;
        // Defensive copy + freeze: caller can't mutate our journal legs after construct
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.timestamp = System.currentTimeMillis();
        validateDoubleEntryInvariant();
    }

    private void validateDoubleEntryInvariant() {
        long totalDebit = 0;
        long totalCredit = 0;
        for (Entry entry : entries) {
            if (entry.getType() == EntryType.DEBIT) totalDebit += entry.getAmountCents();
            else totalCredit += entry.getAmountCents();
        }
        if (totalDebit != totalCredit) {
            throw new IllegalArgumentException(
                    "Double-Entry Violation: DR " + totalDebit + " != CR " + totalCredit);
        }
    }

    String getTransactionId() { return transactionId; }
    String getDescription() { return description; }
    List<Entry> getEntries() { return entries; }
    long getTimestamp() { return timestamp; }
}

// ========== LEDGER FACADE ==========

class LedgerService {
    private final Map<String, Account> accountRegistry = new ConcurrentHashMap<>();
    private final List<Transaction> journalLog = new CopyOnWriteArrayList<>();

    void registerAccount(Account account) {
        accountRegistry.put(account.getAccountId(), account);
    }

    Account getAccount(String accountId) {
        Account account = accountRegistry.get(accountId);
        if (account == null) throw new IllegalArgumentException("Account not found: " + accountId);
        return account;
    }

    /**
     * Post balanced entries. Locks accounts in id order, re-checks balances, appends journal.
     * Never mutates old transactions.
     */
    Transaction postTransaction(String description, List<Entry> entries) {
        Transaction txn = new Transaction(description, entries); // validates Σ DR = Σ CR

        Set<String> accountIds = new HashSet<>();
        for (Entry e : entries) accountIds.add(e.getAccountId());

        List<Account> lockedAccounts = new ArrayList<>();
        for (String id : accountIds) lockedAccounts.add(getAccount(id));
        lockedAccounts.sort(Comparator.comparing(Account::getAccountId));

        for (Account acc : lockedAccounts) acc.getLock().lock();
        try {
            Map<String, Long> pendingDeltas = calculateDeltas(entries);
            for (Map.Entry<String, Long> deltaEntry : pendingDeltas.entrySet()) {
                Account acc = getAccount(deltaEntry.getKey());
                long currentBalance = getAccountBalanceUnlocked(acc.getAccountId());
                long updatedBalance = currentBalance + deltaEntry.getValue();
                if (!acc.isAllowNegative() && updatedBalance < 0) {
                    throw new IllegalStateException(
                            "Insufficient funds / overdraft: " + acc.getName()
                                    + " would be " + updatedBalance + " cents");
                }
            }

            journalLog.add(txn);
            System.out.println("[Ledger] posted " + txn.getTransactionId() + ": " + description);
            return txn;
        } finally {
            for (int i = lockedAccounts.size() - 1; i >= 0; i--) {
                lockedAccounts.get(i).getLock().unlock();
            }
        }
    }

    /** Wallet P2P: DR fromWallet (↓ liability) + CR toWallet (↑ liability). */
    Transaction transfer(String fromWalletId, String toWalletId, long amountCents, String note) {
        if (fromWalletId.equals(toWalletId)) {
            throw new IllegalArgumentException("Cannot transfer to the same wallet");
        }
        Account from = getAccount(fromWalletId);
        Account to = getAccount(toWalletId);
        if (from.getType() != AccountType.LIABILITY) {
            throw new IllegalArgumentException("Transfer source must be a LIABILITY account");
        }
        if (to.getType() != AccountType.LIABILITY) {
            throw new IllegalArgumentException("Transfer destination must be a LIABILITY account");
        }

        // LIABILITY: Debit = owe less (Alice ↓); Credit = owe more (Bob ↑)
        List<Entry> legs = Arrays.asList(
                new Entry(fromWalletId, EntryType.DEBIT, amountCents),
                new Entry(toWalletId, EntryType.CREDIT, amountCents)
        );
        return postTransaction(note, legs);
    }

    /** Deposit into user wallet: DR bank asset + CR user liability. */
    Transaction deposit(String bankAssetId, String walletId, long amountCents, String note) {
        Account bank = getAccount(bankAssetId);
        Account wallet = getAccount(walletId);
        if (bank.getType() != AccountType.ASSET) {
            throw new IllegalArgumentException("Deposit source must be an ASSET account");
        }
        if (wallet.getType() != AccountType.LIABILITY) {
            throw new IllegalArgumentException("Deposit destination must be a LIABILITY account");
        }

        // ASSET Debit = bank gains cash ↑; LIABILITY Credit = owe wallet holder more ↑
        List<Entry> legs = Arrays.asList(
                new Entry(bankAssetId, EntryType.DEBIT, amountCents),
                new Entry(walletId, EntryType.CREDIT, amountCents)
        );
        return postTransaction(note, legs);
    }

    /**
     * Compensating reversal: mirror every leg (DR↔CR). History stays; no UPDATE/DELETE.
     */
    Transaction reverse(Transaction original, String note) {
        List<Entry> mirror = new ArrayList<>();
        for (Entry e : original.getEntries()) {
            EntryType flipped = (e.getType() == EntryType.DEBIT) ? EntryType.CREDIT : EntryType.DEBIT;
            mirror.add(new Entry(e.getAccountId(), flipped, e.getAmountCents()));
        }
        return postTransaction(note + " (reverses " + original.getTransactionId() + ")", mirror);
    }

    private Map<String, Long> calculateDeltas(List<Entry> entries) {
        Map<String, Long> deltas = new HashMap<>();
        for (Entry e : entries) {
            Account acc = getAccount(e.getAccountId());
            long delta;
            if (acc.getType() == AccountType.ASSET) {
                // ASSET: debit ↑, credit ↓
                delta = (e.getType() == EntryType.DEBIT) ? e.getAmountCents() : -e.getAmountCents();
            } else {
                // LIABILITY: credit ↑, debit ↓
                delta = (e.getType() == EntryType.CREDIT) ? e.getAmountCents() : -e.getAmountCents();
            }
            deltas.merge(e.getAccountId(), delta, Long::sum);
        }
        return deltas;
    }

    /**
     * Balance = replay journal for this account only (no stored balance on Account).
     * Holds account lock so concurrent post can't race the read.
     */
    long getAccountBalance(String accountId) {
        Account acc = getAccount(accountId);
        acc.getLock().lock();
        try {
            return getAccountBalanceUnlocked(accountId);
        } finally {
            acc.getLock().unlock();
        }
    }

    /**
     * Caller must hold account lock for strict consistency with post.
     * Replays journalLog and sums signed deltas for this account — source of truth is the journal.
     */
    private long getAccountBalanceUnlocked(String accountId) {
        Account acc = getAccount(accountId);
        long balance = 0;
        for (Transaction txn : journalLog) {
            for (Entry entry : txn.getEntries()) {
                if (!entry.getAccountId().equals(accountId)) continue;
                if (acc.getType() == AccountType.ASSET) {
                    balance += (entry.getType() == EntryType.DEBIT)
                            ? entry.getAmountCents() : -entry.getAmountCents();
                } else {
                    // LIABILITY
                    balance += (entry.getType() == EntryType.CREDIT)
                            ? entry.getAmountCents() : -entry.getAmountCents();
                }
            }
        }
        return balance;
    }

    /** Demo/assert: every posted txn already balanced ⇒ global DR == CR. */
    boolean isLedgerBalanced() {
        long dr = 0;
        long cr = 0;
        for (Transaction txn : journalLog) {
            for (Entry e : txn.getEntries()) {
                if (e.getType() == EntryType.DEBIT) dr += e.getAmountCents();
                else cr += e.getAmountCents();
            }
        }
        return dr == cr;
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) {
        LedgerService ledger = new LedgerService();

        Account bank = Account.asset("ACC_BANK", "Bank Settlement Asset");
        Account alice = Account.wallet("ACC_ALICE", "Alice Wallet");
        Account bob = Account.wallet("ACC_BOB", "Bob Wallet");
        ledger.registerAccount(bank);
        ledger.registerAccount(alice);
        ledger.registerAccount(bob);

        System.out.println("=== TEST 1: DEPOSIT $100 → ALICE ===");
        ledger.deposit("ACC_BANK", "ACC_ALICE", 10000, "Deposit $100 for Alice");
        printBal(ledger, "ACC_ALICE", "ACC_BANK");

        System.out.println("\n=== TEST 2: ALICE → BOB $40 ===");
        Transaction transfer = ledger.transfer("ACC_ALICE", "ACC_BOB", 4000, "Alice transfers $40 to Bob");
        printBal(ledger, "ACC_ALICE", "ACC_BOB");

        System.out.println("\n=== TEST 3: OVERDRAFT (Alice tries $100, has $60) ===");
        try {
            ledger.transfer("ACC_ALICE", "ACC_BOB", 10000, "Alice overdraft");
        } catch (Exception e) {
            System.out.println("Expected: " + e.getMessage());
        }

        System.out.println("\n=== TEST 4: COMPENSATING REVERSAL (no UPDATE/DELETE) ===");
        Transaction reverse = ledger.reverse(transfer, "Undo Alice→Bob $40");
        System.out.println("Reversal txn=" + reverse.getTransactionId());
        printBal(ledger, "ACC_ALICE", "ACC_BOB");

        System.out.println("\nLedger globally balanced? " + ledger.isLedgerBalanced());
    }

    private static void printBal(LedgerService ledger, String... ids) {
        for (String id : ids) {
            long cents = ledger.getAccountBalance(id);
            System.out.println(id + " = $" + (cents / 100.0));
        }
    }
}
