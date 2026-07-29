import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Payment Gateway — ONE FILE (interview style)
 * Patterns: Facade + Adapter (banks) + ordered fallback
 *
 * Fixes vs naive draft:
 * - Idempotency caches terminal SUCCESS and FAILED; same key + different payload → reject
 * - Thin txn lifecycle: INITIATED → PENDING → SUCCESS | FAILED
 * - Fallback = registration order among adapters that support the method (not "success-rate smart")
 * - Demo registers Razorpay before Stripe so fail→fallback is visible
 *
 *   javac Main.java && java Main
 */

// ========== DOMAIN ==========

enum PaymentStatus {
    INITIATED,
    PENDING,
    SUCCESS,
    FAILED
}

enum PaymentMethod {
    CREDIT_CARD,
    UPI,
    NET_BANKING
}

class PaymentRequest {
    private final String idempotencyKey;
    private final String accountId;
    private final long amountCents;
    private final PaymentMethod paymentMethod;

    PaymentRequest(String idempotencyKey, String accountId, long amountCents, PaymentMethod method) {
        this.idempotencyKey = idempotencyKey;
        this.accountId = accountId;
        this.amountCents = amountCents;
        this.paymentMethod = method;
    }

    String getIdempotencyKey() { return idempotencyKey; }
    String getAccountId() { return accountId; }
    long getAmountCents() { return amountCents; }
    PaymentMethod getPaymentMethod() { return paymentMethod; }

    /** Fingerprint so same key cannot be reused with a different charge. */
    String payloadFingerprint() {
        return accountId + "|" + amountCents + "|" + paymentMethod;
    }
}

class PaymentResponse {
    private final String transactionId;
    private final PaymentStatus status;
    private final String gatewayReference;
    private final String providerName;
    private final String errorMessage;

    PaymentResponse(String transactionId, PaymentStatus status, String gatewayReference,
                    String providerName, String errorMessage) {
        this.transactionId = transactionId;
        this.status = status;
        this.gatewayReference = gatewayReference;
        this.providerName = providerName;
        this.errorMessage = errorMessage;
    }

    static PaymentResponse success(String txnId, String ref, String provider) {
        return new PaymentResponse(txnId, PaymentStatus.SUCCESS, ref, provider, null);
    }

    static PaymentResponse failure(String txnId, String error) {
        return new PaymentResponse(txnId, PaymentStatus.FAILED, null, null, error);
    }

    String getTransactionId() { return transactionId; }
    PaymentStatus getStatus() { return status; }
    String getGatewayReference() { return gatewayReference; }
    String getProviderName() { return providerName; }
    String getErrorMessage() { return errorMessage; }

    @Override
    public String toString() {
        return "PaymentResponse{txnId='" + transactionId + "', status=" + status
                + ", provider='" + providerName + "', ref='" + gatewayReference
                + "', error='" + errorMessage + "'}";
    }
}

/** Thin lifecycle record for interview state-machine talk. */
class PaymentTransaction {
    private final String transactionId;
    private final PaymentRequest request;
    private PaymentStatus status;

    PaymentTransaction(String transactionId, PaymentRequest request) {
        this.transactionId = transactionId;
        this.request = request;
        this.status = PaymentStatus.INITIATED;
    }

    String getTransactionId() { return transactionId; }
    PaymentRequest getRequest() { return request; }
    PaymentStatus getStatus() { return status; }

    void markPending() { status = PaymentStatus.PENDING; }
    void markSuccess() { status = PaymentStatus.SUCCESS; }
    void markFailed() { status = PaymentStatus.FAILED; }
}

// ========== BANK ADAPTER ==========

interface BankAdapter {
    String getProviderName();
    boolean supportsMethod(PaymentMethod method);
    PaymentResponse processPayment(String transactionId, PaymentRequest request);
}

class StripeBankAdapter implements BankAdapter {
    @Override
    public String getProviderName() { return "STRIPE"; }

    @Override
    public boolean supportsMethod(PaymentMethod method) {
        return method == PaymentMethod.CREDIT_CARD;
    }

    @Override
    public PaymentResponse processPayment(String transactionId, PaymentRequest request) {
        System.out.println("   [Stripe] charging " + request.getAmountCents() + " cents...");
        return PaymentResponse.success(transactionId, "STRIPE_CHARGE_99812", getProviderName());
    }
}

class RazorpayBankAdapter implements BankAdapter {
    private volatile boolean simulateFailure;

    void setSimulateFailure(boolean fail) { this.simulateFailure = fail; }

    @Override
    public String getProviderName() { return "RAZORPAY"; }

    @Override
    public boolean supportsMethod(PaymentMethod method) {
        return method == PaymentMethod.UPI || method == PaymentMethod.CREDIT_CARD;
    }

    @Override
    public PaymentResponse processPayment(String transactionId, PaymentRequest request) {
        System.out.println("   [Razorpay] charging " + request.getAmountCents()
                + " cents via " + request.getPaymentMethod() + "...");
        if (simulateFailure) {
            System.out.println("   [Razorpay] simulated outage");
            return PaymentResponse.failure(transactionId, "RAZORPAY_BANK_DOWN");
        }
        return PaymentResponse.success(transactionId, "RZP_PAYMENT_33101", getProviderName());
    }
}

// ========== IDEMPOTENCY ==========

class IdempotencyRecord {
    final String payloadFingerprint;
    final PaymentResponse response;

    IdempotencyRecord(String payloadFingerprint, PaymentResponse response) {
        this.payloadFingerprint = payloadFingerprint;
        this.response = response;
    }
}

class IdempotencyEngine {
    private final Map<String, IdempotencyRecord> responseCache = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    PaymentResponse executeIdempotent(PaymentRequest request, Supplier<PaymentResponse> executionBlock) {
        String key = request.getIdempotencyKey();
        ReentrantLock lock = keyLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            IdempotencyRecord cached = responseCache.get(key);
            if (cached != null) {
                if (!Objects.equals(cached.payloadFingerprint, request.payloadFingerprint())) {
                    System.out.println("[Idempotency] KEY REUSED with different payload → reject");
                    return PaymentResponse.failure("TXN_NA", "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
                }
                System.out.println("[Idempotency] duplicate key='" + key + "' → cached response");
                return cached.response;
            }

            PaymentResponse response = executionBlock.get();
            // Cache terminal outcomes so retries return the same answer
            if (response.getStatus() == PaymentStatus.SUCCESS
                    || response.getStatus() == PaymentStatus.FAILED) {
                responseCache.put(key, new IdempotencyRecord(request.payloadFingerprint(), response));
            }
            return response;
        } finally {
            lock.unlock();
        }
    }
}

// ========== FACADE ==========

class PaymentGatewayFacade {
    private final List<BankAdapter> adapters = new ArrayList<>();
    private final IdempotencyEngine idempotencyEngine = new IdempotencyEngine();
    private final Map<String, PaymentTransaction> transactions = new ConcurrentHashMap<>();

    void registerAdapter(BankAdapter adapter) {
        adapters.add(adapter);
        System.out.println("[Registry] " + adapter.getProviderName());
    }

    PaymentResponse processPayment(PaymentRequest request) {
        return idempotencyEngine.executeIdempotent(request, () -> doProcess(request));
    }

    private PaymentResponse doProcess(PaymentRequest request) {
        String transactionId = "TXN_" + UUID.randomUUID().toString().substring(0, 8);
        PaymentTransaction txn = new PaymentTransaction(transactionId, request);
        transactions.put(transactionId, txn);

        System.out.println("\n[Gateway] " + transactionId + " key=" + request.getIdempotencyKey()
                + " status=" + txn.getStatus());
        txn.markPending();

        List<BankAdapter> candidates = new ArrayList<>();
        for (BankAdapter adapter : adapters) {
            if (adapter.supportsMethod(request.getPaymentMethod())) {
                candidates.add(adapter);
            }
        }
        if (candidates.isEmpty()) {
            txn.markFailed();
            return PaymentResponse.failure(transactionId, "NO_SUPPORTED_ACQUIRER");
        }

        // Ordered fallback: try adapters in registration order
        for (BankAdapter adapter : candidates) {
            System.out.println("[Gateway] try " + adapter.getProviderName()
                    + " (txn=" + txn.getStatus() + ")");
            PaymentResponse response = adapter.processPayment(transactionId, request);
            if (response.getStatus() == PaymentStatus.SUCCESS) {
                txn.markSuccess();
                return response;
            }
            System.out.println("[Gateway] " + adapter.getProviderName() + " failed → fallback");
        }

        txn.markFailed();
        return PaymentResponse.failure(transactionId, "ALL_ACQUIRERS_EXHAUSTED");
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) {
        PaymentGatewayFacade gateway = new PaymentGatewayFacade();

        StripeBankAdapter stripe = new StripeBankAdapter();
        RazorpayBankAdapter razorpay = new RazorpayBankAdapter();

        // Razorpay first so Test 3 can show fail → Stripe fallback
        gateway.registerAdapter(razorpay);
        gateway.registerAdapter(stripe);

        System.out.println("=== TEST 1: UPI → RAZORPAY SUCCESS ===");
        PaymentRequest req1 = new PaymentRequest("KEY_1001", "ACC_77", 5000, PaymentMethod.UPI);
        System.out.println("Result 1: " + gateway.processPayment(req1));

        System.out.println("\n=== TEST 2: IDEMPOTENT REPLAY (same key + payload) ===");
        System.out.println("Result 2: " + gateway.processPayment(req1));

        System.out.println("\n=== TEST 2b: SAME KEY, DIFFERENT AMOUNT → REJECT ===");
        PaymentRequest req1b = new PaymentRequest("KEY_1001", "ACC_77", 9999, PaymentMethod.UPI);
        System.out.println("Result 2b: " + gateway.processPayment(req1b));

        System.out.println("\n=== TEST 3: FALLBACK (Razorpay down → Stripe) ===");
        razorpay.setSimulateFailure(true);
        PaymentRequest req3 = new PaymentRequest("KEY_1002", "ACC_88", 12000, PaymentMethod.CREDIT_CARD);
        System.out.println("Result 3: " + gateway.processPayment(req3));

        System.out.println("\n=== TEST 4: IDEMPOTENT REPLAY AFTER SUCCESS ===");
        System.out.println("Result 4: " + gateway.processPayment(req3));
    }
}
