import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Shopping Cart & Inventory — ONE FILE (Google L4)
 * Patterns: Facade + per-SKU locks + cart TTL sweeper + Discount Strategy
 *
 *   javac Main.java && java Main
 */

// ========== CATALOG / INVENTORY ==========

class Product {
    private final String sku;
    private final String name;
    private final double price;

    Product(String sku, String name, double price) {
        this.sku = sku;
        this.name = name;
        this.price = price;
    }

    String getSku() { return sku; }
    String getName() { return name; }
    double getPrice() { return price; }
}

class InventoryItem {
    private final String sku;
    private int totalQuantity;
    private int availableQuantity;
    private int reservedQuantity;
    private final ReentrantLock lock = new ReentrantLock();

    InventoryItem(String sku, int totalQuantity) {
        this.sku = sku;
        this.totalQuantity = totalQuantity;
        this.availableQuantity = totalQuantity;
        this.reservedQuantity = 0;
    }

    String getSku() { return sku; }
    int getAvailableQuantity() { return availableQuantity; }
    int getReservedQuantity() { return reservedQuantity; }
    ReentrantLock getLock() { return lock; }

    void addStock(int quantity) {
        if (quantity <= 0) return;
        totalQuantity += quantity;
        availableQuantity += quantity;
    }

    boolean reserve(int quantity) {
        if (quantity <= 0 || availableQuantity < quantity) {
            return false;
        }
        availableQuantity -= quantity;
        reservedQuantity += quantity;
        return true;
    }

    void release(int quantity) {
        if (quantity <= 0) return;
        int releaseQty = Math.min(quantity, reservedQuantity);
        reservedQuantity -= releaseQty;
        availableQuantity += releaseQty;
    }

    /** Payment success: reserved units leave the warehouse permanently. */
    void deductOnPurchase(int quantity) {
        if (quantity <= 0) return;
        int deduct = Math.min(quantity, reservedQuantity);
        reservedQuantity -= deduct;
        totalQuantity -= deduct;
    }
}

class InventoryManager {
    private final Map<String, InventoryItem> inventory = new ConcurrentHashMap<>();

    void addStock(String sku, int quantity) {
        inventory.compute(sku, (k, existing) -> {
            if (existing == null) {
                return new InventoryItem(sku, quantity);
            }
            existing.getLock().lock();
            try {
                existing.addStock(quantity);
                return existing;
            } finally {
                existing.getLock().unlock();
            }
        });
    }

    boolean reserveStock(String sku, int quantity) {
        InventoryItem item = inventory.get(sku);
        if (item == null) return false;
        item.getLock().lock();
        try {
            return item.reserve(quantity);
        } finally {
            item.getLock().unlock();
        }
    }

    void releaseStock(String sku, int quantity) {
        InventoryItem item = inventory.get(sku);
        if (item == null) return;
        item.getLock().lock();
        try {
            item.release(quantity);
        } finally {
            item.getLock().unlock();
        }
    }

    void finalizePurchase(String sku, int quantity) {
        InventoryItem item = inventory.get(sku);
        if (item == null) return;
        item.getLock().lock();
        try {
            item.deductOnPurchase(quantity);
        } finally {
            item.getLock().unlock();
        }
    }

    int getAvailableStock(String sku) {
        InventoryItem item = inventory.get(sku);
        return item != null ? item.getAvailableQuantity() : 0;
    }
}

// ========== DISCOUNT STRATEGY ==========

interface DiscountStrategy {
    double applyDiscount(double subtotal);
}

class NoDiscountStrategy implements DiscountStrategy {
    @Override
    public double applyDiscount(double subtotal) {
        return subtotal;
    }
}

class PercentageDiscountStrategy implements DiscountStrategy {
    private final double percentage;

    PercentageDiscountStrategy(double percentage) {
        this.percentage = percentage;
    }

    @Override
    public double applyDiscount(double subtotal) {
        return subtotal * (1.0 - percentage / 100.0);
    }
}

// ========== CART STATE MACHINE ==========

enum CartStatus {
    ACTIVE,
    CHECKED_OUT,   // reserved stock held; awaiting pay (still TTL-bound)
    PAID,
    CANCELLED_EXPIRED
}

class CartItem {
    private final Product product;
    private int quantity;

    CartItem(Product product, int quantity) {
        this.product = product;
        this.quantity = quantity;
    }

    Product getProduct() { return product; }
    int getQuantity() { return quantity; }
    void setQuantity(int quantity) { this.quantity = quantity; }
}

class Cart {
    private final String cartId;
    private final String userId;
    private final Map<String, CartItem> items = new ConcurrentHashMap<>();
    private volatile CartStatus status;
    private final long expirationNano;
    private final ReentrantLock lock = new ReentrantLock();

    Cart(String userId, long ttlMillis) {
        this.cartId = UUID.randomUUID().toString();
        this.userId = userId;
        this.status = CartStatus.ACTIVE;
        this.expirationNano = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ttlMillis);
    }

    String getCartId() { return cartId; }
    String getUserId() { return userId; }
    Map<String, CartItem> getItems() { return items; }
    CartStatus getStatus() { return status; }
    void setStatus(CartStatus status) { this.status = status; }
    ReentrantLock getLock() { return lock; }

    boolean isExpired() {
        return System.nanoTime() >= expirationNano;
    }

    double calculateSubtotal() {
        return items.values().stream()
                .mapToDouble(i -> i.getProduct().getPrice() * i.getQuantity())
                .sum();
    }
}

// ========== FACADE ==========

class ShoppingCartService {
    private final InventoryManager inventoryManager;
    private final Map<String, Product> productCatalog = new ConcurrentHashMap<>();
    private final Map<String, Cart> activeCarts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService expirationSweeper =
            Executors.newSingleThreadScheduledExecutor();

    ShoppingCartService(InventoryManager inventoryManager, long sweepIntervalMs) {
        this.inventoryManager = inventoryManager;
        expirationSweeper.scheduleAtFixedRate(
                this::sweepExpiredCarts,
                sweepIntervalMs,
                sweepIntervalMs,
                TimeUnit.MILLISECONDS);
    }

    void registerProduct(Product product, int initialStock) {
        productCatalog.put(product.getSku(), product);
        inventoryManager.addStock(product.getSku(), initialStock);
    }

    Cart createCart(String userId, long ttlMillis) {
        Cart cart = new Cart(userId, ttlMillis);
        activeCarts.put(cart.getCartId(), cart);
        return cart;
    }

    boolean addItemToCart(String cartId, String sku, int quantity) {
        Cart cart = activeCarts.get(cartId);
        Product product = productCatalog.get(sku);
        if (cart == null || product == null || quantity <= 0) {
            return false;
        }

        cart.getLock().lock();
        try {
            if (cart.getStatus() != CartStatus.ACTIVE || cart.isExpired()) {
                return false;
            }
            if (!inventoryManager.reserveStock(sku, quantity)) {
                System.out.println("[Cart] Reserve failed for SKU: " + sku);
                return false;
            }
            cart.getItems().compute(sku, (k, existing) -> {
                if (existing == null) return new CartItem(product, quantity);
                existing.setQuantity(existing.getQuantity() + quantity);
                return existing;
            });
            System.out.println("[Cart] +" + quantity + "x " + product.getName()
                    + " -> " + cartId);
            return true;
        } finally {
            cart.getLock().unlock();
        }
    }

    boolean checkout(String cartId, DiscountStrategy discountStrategy) {
        Cart cart = activeCarts.get(cartId);
        if (cart == null) return false;

        cart.getLock().lock();
        try {
            if (cart.getStatus() != CartStatus.ACTIVE || cart.isExpired()) {
                System.out.println("[Checkout] Failed — invalid/expired: " + cartId);
                return false;
            }
            cart.setStatus(CartStatus.CHECKED_OUT);
            double finalAmount = discountStrategy.applyDiscount(cart.calculateSubtotal());
            System.out.println("[Checkout] OK " + cartId + " amount=$"
                    + String.format("%.2f", finalAmount)
                    + " (pay before cart TTL or stock is released)");
            return true;
        } finally {
            cart.getLock().unlock();
        }
    }

    boolean completePayment(String cartId) {
        Cart cart = activeCarts.get(cartId);
        if (cart == null) return false;

        cart.getLock().lock();
        try {
            if (cart.getStatus() != CartStatus.CHECKED_OUT || cart.isExpired()) {
                return false;
            }
            for (CartItem item : cart.getItems().values()) {
                inventoryManager.finalizePurchase(item.getProduct().getSku(), item.getQuantity());
            }
            cart.setStatus(CartStatus.PAID);
            activeCarts.remove(cartId);
            System.out.println("[Payment] Completed for cart: " + cartId);
            return true;
        } finally {
            cart.getLock().unlock();
        }
    }

    /**
     * Frees stock for expired ACTIVE or unpaid CHECKED_OUT carts.
     * (In-memory path = pessimistic per-SKU locks; DB OCC is verbal scale-out only.)
     */
    private void sweepExpiredCarts() {
        for (Cart cart : activeCarts.values()) {
            if (!cart.isExpired()) continue;
            if (cart.getStatus() != CartStatus.ACTIVE
                    && cart.getStatus() != CartStatus.CHECKED_OUT) {
                continue;
            }

            cart.getLock().lock();
            try {
                if (!cart.isExpired()) continue;
                if (cart.getStatus() != CartStatus.ACTIVE
                        && cart.getStatus() != CartStatus.CHECKED_OUT) {
                    continue;
                }
                cart.setStatus(CartStatus.CANCELLED_EXPIRED);
                for (CartItem item : cart.getItems().values()) {
                    inventoryManager.releaseStock(
                            item.getProduct().getSku(), item.getQuantity());
                    System.out.println("[Sweeper] Released " + item.getQuantity()
                            + "x " + item.getProduct().getSku()
                            + " from " + cart.getCartId());
                }
                activeCarts.remove(cart.getCartId());
            } finally {
                cart.getLock().unlock();
            }
        }
    }

    void shutdown() {
        expirationSweeper.shutdownNow();
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) throws InterruptedException {
        InventoryManager inventoryManager = new InventoryManager();
        ShoppingCartService cartService = new ShoppingCartService(inventoryManager, 500);

        Product iphone = new Product("SKU-IPHONE", "iPhone 15 Pro", 999.00);
        cartService.registerProduct(iphone, 2);

        System.out.println("=== TEST 1: PURCHASE FLOW ===");
        Cart aliceCart = cartService.createCart("user_alice", 5000);
        cartService.addItemToCart(aliceCart.getCartId(), "SKU-IPHONE", 1);
        System.out.println("Available after Alice reserve: "
                + inventoryManager.getAvailableStock("SKU-IPHONE"));

        cartService.checkout(aliceCart.getCartId(), new PercentageDiscountStrategy(10));
        cartService.completePayment(aliceCart.getCartId());
        System.out.println("Available after Alice pay: "
                + inventoryManager.getAvailableStock("SKU-IPHONE"));

        System.out.println("\n=== TEST 2: TTL RELEASES STOCK ===");
        Cart bobCart = cartService.createCart("user_bob", 800);
        cartService.addItemToCart(bobCart.getCartId(), "SKU-IPHONE", 1);
        System.out.println("Available after Bob reserve: "
                + inventoryManager.getAvailableStock("SKU-IPHONE"));

        System.out.println("Sleeping 1.2s for TTL...");
        Thread.sleep(1200);

        System.out.println("Available after sweeper: "
                + inventoryManager.getAvailableStock("SKU-IPHONE"));

        cartService.shutdown();
    }
}
