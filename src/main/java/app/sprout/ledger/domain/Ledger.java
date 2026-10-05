package app.sprout.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Posts balanced journal entries and answers balances.
 *
 * <p>Posting is one transaction: check the idempotency key, create any new accounts, lock every
 * account the entry touches (always in name order, so two entries can never deadlock), apply the
 * postings, refuse the whole entry if a guarded account would go below zero, then write the entry.
 */
@Service
public class Ledger {

    public enum Side { DEBIT, CREDIT }

    public enum Kind { ASSET, LIABILITY }

    public record Posting(String account, Side side, long paise) {}

    public record Entry(UUID id, String idempotencyKey, String description, String reference,
                        List<Posting> postings, Instant postedAt) {}

    /** What posting returned: the entry, and whether it was posted now or found from before. */
    public record Posted(Entry entry, boolean created) {}

    public record AccountType(Kind kind, boolean allowNegative) {}

    public record TrialBalance(long assets, long liabilities, int accounts) {}

    private static final Map<Pattern, AccountType> KINDS = Map.of(
            Pattern.compile("^customer:[0-9a-f-]{36}:cash$"), new AccountType(Kind.LIABILITY, false),
            Pattern.compile("^customer:[0-9a-f-]{36}:withdrawal-hold$"), new AccountType(Kind.LIABILITY, false),
            Pattern.compile("^sprout:bank$"), new AccountType(Kind.ASSET, false));

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;

    public Ledger(JdbcClient db, TransactionTemplate tx, Clock clock) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
    }

    public static AccountType typeOf(String account) {
        if (account != null) {
            for (var e : KINDS.entrySet()) {
                if (e.getKey().matcher(account).matches()) {
                    return e.getValue();
                }
            }
        }
        throw new ApiException(ErrorCode.UNKNOWN_ACCOUNT, "No such kind of account: " + account + ".");
    }

    public Posted post(String key, String description, String reference, List<Posting> postings) {
        validate(postings);
        String hash = hash(description, reference, postings);
        Optional<Posted> earlier = existing(key, hash);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        try {
            return tx.execute(status -> apply(key, hash, description, reference, postings));
        } catch (DuplicateKeyException e) {
            // the same key raced in from another request and won: answer as if we'd been second
            return existing(key, hash).orElseThrow(() -> e);
        }
    }

    private void validate(List<Posting> postings) {
        long debits = 0;
        long credits = 0;
        for (Posting p : postings) {
            typeOf(p.account());
            if (p.paise() <= 0) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Every posting needs an amount above zero.");
            }
            if (p.side() == Side.DEBIT) {
                debits = Math.addExact(debits, p.paise());
            } else {
                credits = Math.addExact(credits, p.paise());
            }
        }
        if (debits != credits) {
            throw new ApiException(ErrorCode.UNBALANCED, "Debits (" + Money.rupees(debits) + ") and credits ("
                    + Money.rupees(credits) + ") must be equal.");
        }
    }

    private Optional<Posted> existing(String key, String hash) {
        return db.sql("SELECT id, request_hash FROM journal_entries WHERE idempotency_key = ?").param(key)
                .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("request_hash")})
                .optional()
                .map(row -> {
                    if (!row[1].equals(hash)) {
                        throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                                "This idempotency key was already used for a different entry.");
                    }
                    return new Posted(entry((UUID) row[0]), false);
                });
    }

    private Posted apply(String key, String hash, String description, String reference, List<Posting> postings) {
        TreeMap<String, Long> delta = new TreeMap<>();
        for (Posting p : postings) {
            AccountType t = typeOf(p.account());
            boolean increases = (t.kind() == Kind.ASSET) == (p.side() == Side.DEBIT);
            delta.merge(p.account(), increases ? p.paise() : -p.paise(), Math::addExact);
        }
        for (String account : delta.keySet()) {
            AccountType t = typeOf(account);
            db.sql("INSERT INTO accounts (name, kind, allow_negative) VALUES (?, ?, ?) ON CONFLICT (name) DO NOTHING")
                    .params(account, t.kind().name(), t.allowNegative()).update();
        }
        Map<String, Long> balances = new TreeMap<>();
        db.sql("SELECT name, balance_paise FROM accounts WHERE name = ANY(?) ORDER BY name FOR UPDATE")
                .param(delta.keySet().toArray(String[]::new))
                .query((rs, n) -> balances.put(rs.getString("name"), rs.getLong("balance_paise")))
                .list();
        for (var e : delta.entrySet()) {
            long after = Math.addExact(balances.get(e.getKey()), e.getValue());
            if (after < 0 && !typeOf(e.getKey()).allowNegative()) {
                throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS, "Not enough money in " + e.getKey()
                        + ": it has " + Money.rupees(balances.get(e.getKey())) + ".");
            }
            db.sql("UPDATE accounts SET balance_paise = ? WHERE name = ?").params(after, e.getKey()).update();
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        db.sql("INSERT INTO journal_entries (id, idempotency_key, request_hash, description, reference, posted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")
                .params(id, key, hash, description, reference, java.sql.Timestamp.from(now)).update();
        int seq = 0;
        for (Posting p : postings) {
            db.sql("INSERT INTO postings (entry_id, seq, account, side, amount_paise) VALUES (?, ?, ?, ?, ?)")
                    .params(id, seq++, p.account(), p.side().name(), p.paise()).update();
        }
        return new Posted(new Entry(id, key, description, reference, List.copyOf(postings), now), true);
    }

    public Entry entry(UUID id) {
        var head = db.sql("SELECT id, idempotency_key, description, reference, posted_at FROM journal_entries WHERE id = ?")
                .param(id)
                .query((rs, n) -> new Object[] {rs.getString("idempotency_key"), rs.getString("description"),
                        rs.getString("reference"), rs.getTimestamp("posted_at").toInstant()})
                .single();
        List<Posting> postings = db.sql("SELECT account, side, amount_paise FROM postings WHERE entry_id = ? ORDER BY seq")
                .param(id)
                .query((rs, n) -> new Posting(rs.getString("account"), Side.valueOf(rs.getString("side")), rs.getLong("amount_paise")))
                .list();
        return new Entry(id, (String) head[0], (String) head[1], (String) head[2], postings, (Instant) head[3]);
    }

    public long balance(String account) {
        typeOf(account);
        return db.sql("SELECT balance_paise FROM accounts WHERE name = ?").param(account)
                .query(Long.class).optional().orElse(0L);
    }

    public List<Entry> entries(String account, int limit) {
        typeOf(account);
        List<UUID> ids = db.sql("""
                        SELECT e.id FROM journal_entries e
                        WHERE EXISTS (SELECT 1 FROM postings p WHERE p.entry_id = e.id AND p.account = ?)
                        ORDER BY e.posted_at DESC, e.id DESC LIMIT ?""")
                .params(account, limit).query(UUID.class).list();
        List<Entry> out = new ArrayList<>();
        ids.forEach(id -> out.add(entry(id)));
        return out;
    }

    public TrialBalance trialBalance() {
        return db.sql("""
                        SELECT COALESCE(SUM(balance_paise) FILTER (WHERE kind = 'ASSET'), 0) AS assets,
                               COALESCE(SUM(balance_paise) FILTER (WHERE kind = 'LIABILITY'), 0) AS liabilities,
                               COUNT(*) AS accounts
                        FROM accounts""")
                .query((rs, n) -> new TrialBalance(rs.getLong("assets"), rs.getLong("liabilities"), rs.getInt("accounts")))
                .single();
    }

    static String hash(String description, String reference, List<Posting> postings) {
        StringBuilder canonical = new StringBuilder().append(description).append('\u0000').append(reference).append('\u0000');
        for (Posting p : postings) {
            canonical.append(p.account()).append('|').append(p.side()).append('|').append(p.paise()).append('\u0000');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
