package com.cms.core;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DEV ONLY: an in-memory core banking system that implements the provisional contract of
 * {@link CoreBankingClient}, so CORE_BANKING accounts can be tested without the bank's host.
 * State is lost on restart. Unknown accounts are created on first use (ACTIVE, balance 0).
 * Test controls: PUT /accounts/{ref} (balance, status), PUT /state (down = answer 503, latencyMs).
 */
@Profile("dev")
@RestController
@RequestMapping("/api/dev/core-sim")
public class CoreBankingSimulator {

    static final class Acct {
        String ref, currency = "EGP", status = "ACTIVE";
        long ledger, held;
        Acct(String ref) { this.ref = ref; }
    }
    record Hold(String id, String account, long amount, boolean open) {}
    record Entry(String reference, String account, String type, long amount, long fee, long reversed, String at) {}

    private final Map<String, Acct> accounts = new LinkedHashMap<>();
    private final Map<String, Hold> holds = new LinkedHashMap<>();
    private final Map<String, Entry> postings = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> answered = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong(1000);
    private volatile boolean down;
    private volatile long latencyMs;

    // ---------------- the contract ----------------

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        pause();
        return down ? unavailable() : ResponseEntity.ok(Map.of("status", "UP"));
    }

    @PostMapping("/accounts/{ref}/balance")
    public synchronized ResponseEntity<Map<String, Object>> balance(@PathVariable String ref, @RequestBody Map<String, Object> b) {
        if (gate()) return unavailable();
        Acct a = acct(ref, (String) b.get("currency"));
        if ("CLOSED".equals(a.status)) return ok(declined("ACCOUNT_CLOSED", a));
        return ok(approved(a, null));
    }

    @PostMapping("/accounts/{ref}/debits")
    public synchronized ResponseEntity<Map<String, Object>> debit(@PathVariable String ref, @RequestBody Map<String, Object> b) {
        if (gate()) return unavailable();
        return ok(once(b, () -> {
            Acct a = acct(ref, str(b, "currency"));
            long amount = num(b, "amount"), fee = num(b, "fee");
            String refusal = refuseDebit(a, amount + fee, bool(b, "force"));
            if (refusal != null) return declined(refusal, a);
            a.ledger -= amount + fee;
            postings.put(str(b, "reference"), new Entry(str(b, "reference"), a.ref, str(b, "type"), -amount, fee, 0, now()));
            return approved(a, "CB" + seq.incrementAndGet());
        }));
    }

    @PostMapping("/accounts/{ref}/credits")
    public synchronized ResponseEntity<Map<String, Object>> credit(@PathVariable String ref, @RequestBody Map<String, Object> b) {
        if (gate()) return unavailable();
        return ok(once(b, () -> {
            Acct a = acct(ref, str(b, "currency"));
            if ("CLOSED".equals(a.status)) return declined("ACCOUNT_CLOSED", a);
            long amount = num(b, "amount");
            a.ledger += amount;
            postings.put(str(b, "reference"), new Entry(str(b, "reference"), a.ref, str(b, "type"), amount, 0, 0, now()));
            return approved(a, "CB" + seq.incrementAndGet());
        }));
    }

    @PostMapping("/accounts/{ref}/holds")
    public synchronized ResponseEntity<Map<String, Object>> hold(@PathVariable String ref, @RequestBody Map<String, Object> b) {
        if (gate()) return unavailable();
        return ok(once(b, () -> {
            Acct a = acct(ref, str(b, "currency"));
            long amount = num(b, "amount");
            String refusal = refuseDebit(a, amount, bool(b, "force"));
            if (refusal != null) return declined(refusal, a);
            String id = "H" + seq.incrementAndGet();
            holds.put(id, new Hold(id, a.ref, amount, true));
            a.held += amount;
            return approved(a, id);
        }));
    }

    @PostMapping("/holds/{holdRef}/capture")
    public synchronized ResponseEntity<Map<String, Object>> capture(@PathVariable String holdRef, @RequestBody Map<String, Object> b) {
        if (gate()) return unavailable();
        return ok(once(b, () -> {
            Hold h = holds.get(holdRef);
            if (h == null || !h.open()) return Map.of("status", "DECLINED", "reason", "HOLD_NOT_FOUND");
            Acct a = accounts.get(h.account());
            long amount = num(b, "amount");
            if (amount > h.amount() && !bool(b, "force") && a.ledger - a.held + h.amount() < amount) {
                return declined("INSUFFICIENT_FUNDS", a);
            }
            a.held -= h.amount();
            a.ledger -= amount;
            holds.put(holdRef, new Hold(h.id(), h.account(), h.amount(), false));
            postings.put(str(b, "reference"), new Entry(str(b, "reference"), a.ref, "CAPTURE", -amount, 0, 0, now()));
            return approved(a, "CB" + seq.incrementAndGet());
        }));
    }

    @PostMapping("/holds/{holdRef}/release")
    public synchronized ResponseEntity<Map<String, Object>> release(@PathVariable String holdRef, @RequestBody Map<String, Object> b) {
        if (gate()) return unavailable();
        return ok(once(b, () -> {
            Hold h = holds.get(holdRef);
            if (h == null) return Map.of("status", "DECLINED", "reason", "HOLD_NOT_FOUND");
            Acct a = accounts.get(h.account());
            if (h.open()) {
                a.held -= h.amount();
                holds.put(holdRef, new Hold(h.id(), h.account(), h.amount(), false));
            }
            return approved(a, holdRef);
        }));
    }

    @PostMapping("/postings/{originalRef}/reverse")
    public synchronized ResponseEntity<Map<String, Object>> reverse(@PathVariable String originalRef, @RequestBody Map<String, Object> b) {
        if (gate()) return unavailable();
        return ok(once(b, () -> {
            Entry o = postings.get(originalRef);
            if (o == null) return Map.of("status", "DECLINED", "reason", "ORIGINAL_NOT_FOUND");
            Acct a = accounts.get(o.account());
            long amount = num(b, "amount");
            if (o.reversed() + amount > Math.abs(o.amount())) return declined("AMOUNT_EXCEEDS_ORIGINAL", a);
            long back = (o.amount() < 0 ? amount : -amount) + (bool(b, "includeFee") ? o.fee() : 0);
            a.ledger += back;
            postings.put(originalRef, new Entry(o.reference(), o.account(), o.type(), o.amount(), bool(b, "includeFee") ? 0 : o.fee(),
                    o.reversed() + amount, o.at()));
            postings.put(str(b, "reference"), new Entry(str(b, "reference"), a.ref, "REVERSAL", back, 0, 0, now()));
            return approved(a, "CB" + seq.incrementAndGet());
        }));
    }

    // ---------------- test controls ----------------

    @GetMapping("/accounts")
    public synchronized List<Map<String, Object>> accounts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Acct a : accounts.values()) out.add(view(a));
        return out;
    }

    /** body: balance (ledger, minor units), status (ACTIVE, BLOCKED, DEBIT_BLOCKED, CLOSED), currency */
    @PutMapping("/accounts/{ref}")
    public synchronized Map<String, Object> setAccount(@PathVariable String ref, @RequestBody Map<String, Object> b) {
        Acct a = acct(ref, str(b, "currency"));
        if (b.get("balance") != null) a.ledger = num(b, "balance");
        if (b.get("status") != null) a.status = str(b, "status");
        return view(a);
    }

    @GetMapping("/accounts/{ref}/postings")
    public synchronized List<Entry> postings(@PathVariable String ref) {
        return postings.values().stream().filter(e -> e.account().equals(ref))
                .sorted(Comparator.comparing(Entry::at).reversed()).toList();
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        return Map.of("down", down, "latencyMs", latencyMs);
    }

    @PutMapping("/state")
    public Map<String, Object> setState(@RequestBody Map<String, Object> b) {
        if (b.get("down") != null) down = Boolean.TRUE.equals(b.get("down"));
        if (b.get("latencyMs") != null) latencyMs = Math.max(0, Math.min(30000, ((Number) b.get("latencyMs")).longValue()));
        return state();
    }

    // ---------------- helpers ----------------

    private boolean gate() {
        pause();
        return down;
    }

    private void pause() {
        if (latencyMs <= 0) return;
        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Acct acct(String ref, String currency) {
        return accounts.computeIfAbsent(ref, r -> {
            Acct a = new Acct(r);
            if (currency != null) a.currency = currency;
            return a;
        });
    }

    private static String refuseDebit(Acct a, long amount, boolean force) {
        if ("CLOSED".equals(a.status)) return "ACCOUNT_CLOSED";
        if (force) return null;
        if ("BLOCKED".equals(a.status)) return "ACCOUNT_BLOCKED";
        if ("DEBIT_BLOCKED".equals(a.status)) return "DEBIT_BLOCKED";
        return a.ledger - a.held < amount ? "INSUFFICIENT_FUNDS" : null;
    }

    /** Same reference = same answer (idempotency, as the contract requires). */
    private Map<String, Object> once(Map<String, Object> b, java.util.function.Supplier<Map<String, Object>> work) {
        String ref = str(b, "reference");
        if (ref != null && answered.containsKey(ref)) return answered.get(ref);
        Map<String, Object> r = work.get();
        if (ref != null) answered.put(ref, r);
        return r;
    }

    private static Map<String, Object> approved(Acct a, String coreRef) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "APPROVED");
        m.put("ledgerBalance", a.ledger);
        m.put("availableBalance", a.ledger - a.held);
        if (coreRef != null) m.put("coreRef", coreRef);
        return m;
    }

    private static Map<String, Object> declined(String reason, Acct a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "DECLINED");
        m.put("reason", reason);
        m.put("ledgerBalance", a.ledger);
        m.put("availableBalance", a.ledger - a.held);
        return m;
    }

    private static Map<String, Object> view(Acct a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", a.ref);
        m.put("currency", a.currency);
        m.put("status", a.status);
        m.put("ledgerBalance", a.ledger);
        m.put("heldAmount", a.held);
        m.put("availableBalance", a.ledger - a.held);
        return m;
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> m) {
        return ResponseEntity.ok(m);
    }

    private static ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "core banking simulator is down"));
    }

    private static String str(Map<String, Object> b, String k) {
        Object v = b.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static long num(Map<String, Object> b, String k) {
        Object v = b.get(k);
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static boolean bool(Map<String, Object> b, String k) {
        return Boolean.TRUE.equals(b.get(k));
    }

    private static String now() {
        return java.time.OffsetDateTime.now().toString();
    }
}
