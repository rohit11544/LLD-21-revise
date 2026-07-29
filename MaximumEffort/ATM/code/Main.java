import java.util.*;

/**
 * ATM LLD — ONE FILE for interview speed.
 * Patterns: State + Command + Chain of Responsibility + DIP (BankService)
 *
 * Run from this folder:
 *   javac Main.java && java Main
 */

// ========== DOMAIN & HARDWARE ==========

class Card {
    private final String cardNumber;
    private final String pin;

    Card(String cardNumber, String pin) {
        this.cardNumber = cardNumber;
        this.pin = pin;
    }

    String getCardNumber() { return cardNumber; }
    boolean validatePin(String inputPin) { return pin.equals(inputPin); }
}

class Account {
    private final String accountNumber;
    private double balance;

    Account(String accountNumber, double balance) {
        this.accountNumber = accountNumber;
        this.balance = balance;
    }

    String getAccountNumber() { return accountNumber; }
    synchronized double getBalance() { return balance; }
    synchronized void deduct(double amount) { balance -= amount; }
    synchronized void add(double amount) { balance += amount; }
}

class CardReader {
    void ejectCard() { System.out.println("[CardReader] Card ejected."); }
}

class ReceiptPrinter {
    void printReceipt(String details) {
        System.out.println("[ReceiptPrinter] --- RECEIPT ---\n" + details);
    }
}

// ========== CHAIN OF RESPONSIBILITY ==========

abstract class CashDispenser {
    protected CashDispenser next;
    protected int denomination;
    protected int noteCount;

    CashDispenser(int denomination, int noteCount) {
        this.denomination = denomination;
        this.noteCount = noteCount;
    }

    void setNext(CashDispenser next) { this.next = next; }

    boolean dispense(int amount) {
        if (amount == 0) return true;

        int use = Math.min(amount / denomination, noteCount);
        int remaining = amount - use * denomination;

        if (remaining > 0) {
            if (next == null || !next.dispense(remaining)) {
                System.out.println("[CashDispenser] Cannot dispense exact amount.");
                return false;
            }
        }
        if (use > 0) {
            noteCount -= use;
            System.out.println("[CashDispenser] Dispensed " + use + " x $" + denomination);
        }
        return true;
    }
}

class FiftyDispenser extends CashDispenser {
    FiftyDispenser(int count) { super(50, count); }
}

class TwentyDispenser extends CashDispenser {
    TwentyDispenser(int count) { super(20, count); }
}

class TenDispenser extends CashDispenser {
    TenDispenser(int count) { super(10, count); }
}

// ========== BANK (DIP) ==========

interface BankService {
    boolean authenticate(Card card, String pin);
    Account getAccount(String cardNumber);
    boolean hasSufficientBalance(String accountNumber, double amount);
    void debitAccount(String accountNumber, double amount);
    void creditAccount(String accountNumber, double amount);
}

class MockBankService implements BankService {
    private final Map<String, Account> byCard = new HashMap<>();

    MockBankService() {
        byCard.put("1234-5678", new Account("ACC-1001", 1000.00));
    }

    public boolean authenticate(Card card, String pin) {
        return card.validatePin(pin);
    }

    public Account getAccount(String cardNumber) {
        return byCard.get(cardNumber);
    }

    private Account byAcc(String accountNumber) {
        for (Account a : byCard.values()) {
            if (a.getAccountNumber().equals(accountNumber)) return a;
        }
        return null;
    }

    public boolean hasSufficientBalance(String accountNumber, double amount) {
        Account a = byAcc(accountNumber);
        return a != null && a.getBalance() >= amount;
    }

    public synchronized void debitAccount(String accountNumber, double amount) {
        Account a = byAcc(accountNumber);
        if (a != null) a.deduct(amount);
    }

    public synchronized void creditAccount(String accountNumber, double amount) {
        Account a = byAcc(accountNumber);
        if (a != null) a.add(amount);
    }
}

// ========== COMMAND ==========

abstract class Transaction {
    protected final String transactionId = UUID.randomUUID().toString();
    protected final BankService bankService;
    protected final Account account;

    Transaction(BankService bankService, Account account) {
        this.bankService = bankService;
        this.account = account;
    }

    abstract boolean execute(ATM atm);
}

class WithdrawTransaction extends Transaction {
    private final int amount;

    WithdrawTransaction(BankService bankService, Account account, int amount) {
        super(bankService, account);
        this.amount = amount;
    }

    boolean execute(ATM atm) {
        if (!bankService.hasSufficientBalance(account.getAccountNumber(), amount)) {
            System.out.println("Failed: Insufficient balance.");
            return false;
        }
        if (!atm.getCashDispenser().dispense(amount)) return false;

        bankService.debitAccount(account.getAccountNumber(), amount);
        atm.getPrinter().printReceipt(
                "TXN: " + transactionId + "\nWithdraw: $" + amount
                        + "\nBalance: $" + account.getBalance());
        return true;
    }
}

class DepositTransaction extends Transaction {
    private final int amount;

    DepositTransaction(BankService bankService, Account account, int amount) {
        super(bankService, account);
        this.amount = amount;
    }

    boolean execute(ATM atm) {
        bankService.creditAccount(account.getAccountNumber(), amount);
        atm.getPrinter().printReceipt(
                "TXN: " + transactionId + "\nDeposit: $" + amount
                        + "\nBalance: $" + account.getBalance());
        return true;
    }
}

// ========== STATE ==========

interface ATMState {
    void insertCard(ATM atm, Card card);
    void authenticatePin(ATM atm, String pin);
    void executeTransaction(ATM atm, Transaction tx);
    void ejectCard(ATM atm);
}

class IdleState implements ATMState {
    public void insertCard(ATM atm, Card card) {
        atm.setCurrentCard(card);
        atm.setState(new HasCardState());
        System.out.println("Card inserted. Enter PIN.");
    }
    public void authenticatePin(ATM atm, String pin) { System.out.println("Insert card first."); }
    public void executeTransaction(ATM atm, Transaction tx) { System.out.println("Insert card first."); }
    public void ejectCard(ATM atm) { System.out.println("No card."); }
}

class HasCardState implements ATMState {
    public void insertCard(ATM atm, Card card) { System.out.println("Card already inside."); }

    public void authenticatePin(ATM atm, String pin) {
        if (atm.getBankService().authenticate(atm.getCurrentCard(), pin)) {
            atm.setCurrentAccount(atm.getBankService().getAccount(atm.getCurrentCard().getCardNumber()));
            atm.setState(new AuthenticatedState());
            System.out.println("PIN OK. Choose operation.");
        } else {
            System.out.println("Invalid PIN.");
            ejectCard(atm);
        }
    }

    public void executeTransaction(ATM atm, Transaction tx) { System.out.println("Authenticate first."); }

    public void ejectCard(ATM atm) {
        atm.getCardReader().ejectCard();
        atm.setCurrentCard(null);
        atm.setState(new IdleState());
    }
}

class AuthenticatedState implements ATMState {
    public void insertCard(ATM atm, Card card) { System.out.println("Card already inside."); }
    public void authenticatePin(ATM atm, String pin) { System.out.println("Already authenticated."); }

    public void executeTransaction(ATM atm, Transaction tx) {
        System.out.println(tx.execute(atm) ? "Transaction success." : "Transaction failed.");
        ejectCard(atm);
    }

    public void ejectCard(ATM atm) {
        atm.getCardReader().ejectCard();
        atm.setCurrentCard(null);
        atm.setCurrentAccount(null);
        atm.setState(new IdleState());
    }
}

// ========== ATM CONTEXT ==========

class ATM {
    private ATMState state = new IdleState();
    private final CardReader cardReader = new CardReader();
    private final ReceiptPrinter printer = new ReceiptPrinter();
    private final CashDispenser cashDispenser;
    private final BankService bankService;
    private Card currentCard;
    private Account currentAccount;

    ATM(BankService bankService) {
        this.bankService = bankService;
        CashDispenser c50 = new FiftyDispenser(10);
        CashDispenser c20 = new TwentyDispenser(20);
        CashDispenser c10 = new TenDispenser(30);
        c50.setNext(c20);
        c20.setNext(c10);
        this.cashDispenser = c50;
    }

    synchronized void setState(ATMState state) { this.state = state; }
    CardReader getCardReader() { return cardReader; }
    ReceiptPrinter getPrinter() { return printer; }
    CashDispenser getCashDispenser() { return cashDispenser; }
    BankService getBankService() { return bankService; }
    void setCurrentCard(Card card) { this.currentCard = card; }
    Card getCurrentCard() { return currentCard; }
    void setCurrentAccount(Account account) { this.currentAccount = account; }
    Account getCurrentAccount() { return currentAccount; }

    void insertCard(Card card) { state.insertCard(this, card); }
    void authenticatePin(String pin) { state.authenticatePin(this, pin); }
    void executeTransaction(Transaction tx) { state.executeTransaction(this, tx); }
    void ejectCard() { state.ejectCard(this); }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) {
        BankService bank = new MockBankService();
        ATM atm = new ATM(bank);
        Card card = new Card("1234-5678", "1234");

        System.out.println("=== WITHDRAW $130 ===");
        atm.insertCard(card);
        atm.authenticatePin("1234");
        atm.executeTransaction(new WithdrawTransaction(bank, atm.getCurrentAccount(), 130));
    }
}
