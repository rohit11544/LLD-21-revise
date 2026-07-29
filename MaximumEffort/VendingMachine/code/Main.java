import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vending Machine LLD — ONE FILE (interview style)
 * Patterns: State + Command + Chain of Responsibility
 *
 *   javac Main.java && java Main
 */

// ========== DOMAIN & HARDWARE ==========

enum Coin {
    QUARTER(0.25),
    ONE(1.00),
    FIVE(5.00);

    private final double value;
    Coin(double value) { this.value = value; }
    double getValue() { return value; }
}

class Item {
    private final String code;
    private final String name;
    private final double price;

    Item(String code, String name, double price) {
        this.code = code;
        this.name = name;
        this.price = price;
    }

    String getCode() { return code; }
    String getName() { return name; }
    double getPrice() { return price; }
}

class ItemSlot {
    private final Item item;
    private int quantity;

    ItemSlot(Item item, int quantity) {
        this.item = item;
        this.quantity = quantity;
    }

    Item getItem() { return item; }
    synchronized int getQuantity() { return quantity; }

    synchronized boolean dispense() {
        if (quantity > 0) {
            quantity--;
            return true;
        }
        return false;
    }
}

class ItemMotor {
    void releaseItem(String slotCode) {
        System.out.println("[ItemMotor] Dispensing from slot: " + slotCode);
    }
}

class CoinReturnTray {
    void returnCoins(double amount) {
        System.out.println("[CoinReturnTray] Returned $" + String.format("%.2f", amount));
    }
}

class DisplayScreen {
    void showMessage(String msg) {
        System.out.println("[Display] " + msg);
    }
}

// ========== CHAIN OF RESPONSIBILITY (CHANGE) ==========
// Only mutates inventory if the FULL amount can be made.

abstract class ChangeDispenser {
    protected ChangeDispenser next;
    protected double coinValue;
    protected int count;

    ChangeDispenser(double coinValue, int count) {
        this.coinValue = coinValue;
        this.count = count;
    }

    void setNext(ChangeDispenser next) { this.next = next; }

    boolean dispenseChange(double amount) {
        amount = Math.round(amount * 100.0) / 100.0;
        if (amount == 0) return true;

        int use = Math.min((int) (amount / coinValue), count);
        double remaining = Math.round((amount - use * coinValue) * 100.0) / 100.0;

        if (remaining > 0) {
            if (next == null || !next.dispenseChange(remaining)) {
                System.out.println("[ChangeDispenser] Cannot make exact change.");
                return false;
            }
        }
        if (use > 0) {
            count -= use;
            System.out.println("[ChangeDispenser] Dispensed " + use + " x $" + coinValue);
        }
        return true;
    }
}

class FiveDollarDispenser extends ChangeDispenser {
    FiveDollarDispenser(int count) { super(5.00, count); }
}

class OneDollarDispenser extends ChangeDispenser {
    OneDollarDispenser(int count) { super(1.00, count); }
}

class QuarterDispenser extends ChangeDispenser {
    QuarterDispenser(int count) { super(0.25, count); }
}

// ========== COMMAND ==========

abstract class Transaction {
    protected final String transactionId = UUID.randomUUID().toString();
    abstract boolean execute(VendingMachine machine);
}

class PurchaseTransaction extends Transaction {
    private final String slotCode;

    PurchaseTransaction(String slotCode) { this.slotCode = slotCode; }

    boolean execute(VendingMachine machine) {
        ItemSlot slot = machine.getInventory().get(slotCode);
        if (slot == null || slot.getQuantity() == 0) {
            machine.getScreen().showMessage("Item out of stock!");
            return false;
        }

        Item item = slot.getItem();
        double credit = machine.getInsertedCredit();
        if (credit < item.getPrice()) {
            machine.getScreen().showMessage("Insufficient credit. Price: $" + item.getPrice());
            return false;
        }

        double changeDue = Math.round((credit - item.getPrice()) * 100.0) / 100.0;

        // Dispense item first; then change (better atomicity for interview story)
        if (!slot.dispense()) {
            machine.getScreen().showMessage("Dispense failed.");
            return false;
        }
        machine.getItemMotor().releaseItem(slotCode);

        if (changeDue > 0) {
            if (!machine.getChangeDispenserChain().dispenseChange(changeDue)) {
                // Rollback item + refund full credit (interview-level compensate)
                machine.getScreen().showMessage("No change available. Cancelling.");
                // put item back
                machine.getInventory().put(slotCode,
                        new ItemSlot(item, slot.getQuantity() + 1));
                return false;
            }
        }

        machine.resetCredit();
        machine.getScreen().showMessage("Enjoy your " + item.getName() + "! TXN=" + transactionId);
        return true;
    }
}

class RefundTransaction extends Transaction {
    boolean execute(VendingMachine machine) {
        double credit = machine.getInsertedCredit();
        if (credit <= 0) return false;
        machine.getCoinTray().returnCoins(credit);
        machine.resetCredit();
        machine.getScreen().showMessage("Refund processed.");
        return true;
    }
}

// ========== STATE ==========

interface VendingMachineState {
    void insertCoin(VendingMachine machine, Coin coin);
    void selectItem(VendingMachine machine, String slotCode);
    void dispenseItem(VendingMachine machine, String slotCode);
    void refund(VendingMachine machine);
}

class IdleState implements VendingMachineState {
    public void insertCoin(VendingMachine machine, Coin coin) {
        machine.addCredit(coin.getValue());
        machine.setState(new HasMoneyState());
        machine.getScreen().showMessage("Credit: $" + machine.getInsertedCredit());
    }
    public void selectItem(VendingMachine machine, String slotCode) {
        machine.getScreen().showMessage("Insert coins first.");
    }
    public void dispenseItem(VendingMachine machine, String slotCode) {
        machine.getScreen().showMessage("Insert coins first.");
    }
    public void refund(VendingMachine machine) {
        machine.getScreen().showMessage("No credit to refund.");
    }
}

class HasMoneyState implements VendingMachineState {
    public void insertCoin(VendingMachine machine, Coin coin) {
        machine.addCredit(coin.getValue());
        machine.getScreen().showMessage("Credit: $" + machine.getInsertedCredit());
    }

    public void selectItem(VendingMachine machine, String slotCode) {
        machine.setState(new DispensingState());
        machine.dispenseItem(slotCode);
    }

    public void dispenseItem(VendingMachine machine, String slotCode) {
        machine.getScreen().showMessage("Select an item first.");
    }

    public void refund(VendingMachine machine) {
        new RefundTransaction().execute(machine);
        machine.setState(new IdleState());
    }
}

class DispensingState implements VendingMachineState {
    public void insertCoin(VendingMachine machine, Coin coin) {
        machine.getScreen().showMessage("Please wait, dispensing.");
    }
    public void selectItem(VendingMachine machine, String slotCode) {
        machine.getScreen().showMessage("Please wait, dispensing.");
    }

    public void dispenseItem(VendingMachine machine, String slotCode) {
        boolean ok = new PurchaseTransaction(slotCode).execute(machine);
        if (!ok) {
            new RefundTransaction().execute(machine);
        }
        machine.setState(new IdleState());
    }

    public void refund(VendingMachine machine) {
        machine.getScreen().showMessage("Refund not allowed while dispensing.");
    }
}

// ========== CONTEXT ==========

class VendingMachine {
    private VendingMachineState currentState = new IdleState();
    private double insertedCredit;

    private final DisplayScreen screen = new DisplayScreen();
    private final ItemMotor itemMotor = new ItemMotor();
    private final CoinReturnTray coinTray = new CoinReturnTray();
    private final ChangeDispenser changeDispenserChain;
    private final Map<String, ItemSlot> inventory = new ConcurrentHashMap<>();

    VendingMachine() {
        inventory.put("A1", new ItemSlot(new Item("A1", "Coke", 1.50), 5));
        inventory.put("B2", new ItemSlot(new Item("B2", "Chips", 1.25), 3));

        ChangeDispenser c5 = new FiveDollarDispenser(10);
        ChangeDispenser c1 = new OneDollarDispenser(20);
        ChangeDispenser c025 = new QuarterDispenser(30);
        c5.setNext(c1);
        c1.setNext(c025);
        this.changeDispenserChain = c5;
    }

    synchronized void setState(VendingMachineState state) { this.currentState = state; }
    synchronized void addCredit(double amount) { insertedCredit += amount; }
    synchronized double getInsertedCredit() { return insertedCredit; }
    synchronized void resetCredit() { insertedCredit = 0.0; }

    DisplayScreen getScreen() { return screen; }
    ItemMotor getItemMotor() { return itemMotor; }
    CoinReturnTray getCoinTray() { return coinTray; }
    ChangeDispenser getChangeDispenserChain() { return changeDispenserChain; }
    Map<String, ItemSlot> getInventory() { return inventory; }

    void insertCoin(Coin coin) { currentState.insertCoin(this, coin); }
    void selectItem(String slotCode) { currentState.selectItem(this, slotCode); }
    void dispenseItem(String slotCode) { currentState.dispenseItem(this, slotCode); }
    void requestRefund() { currentState.refund(this); }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) {
        VendingMachine machine = new VendingMachine();

        System.out.println("=== TEST 1: SUCCESSFUL PURCHASE ===");
        machine.insertCoin(Coin.ONE);
        machine.insertCoin(Coin.ONE); // $2.00
        machine.selectItem("A1");     // $1.50 → change $0.50 (2 quarters)

        System.out.println("\n=== TEST 2: REFUND FLOW ===");
        machine.insertCoin(Coin.FIVE);
        machine.requestRefund();
    }
}
