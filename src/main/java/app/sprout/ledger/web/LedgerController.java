package app.sprout.ledger.web;

import app.sprout.ledger.domain.ApiException;
import app.sprout.ledger.domain.ErrorCode;
import app.sprout.ledger.domain.Ledger;
import app.sprout.ledger.domain.Ledger.Entry;
import app.sprout.ledger.domain.Ledger.Posted;
import app.sprout.ledger.domain.Ledger.Posting;
import app.sprout.ledger.domain.Ledger.Side;
import app.sprout.ledger.domain.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The ledger API. See ledger-v1.yaml in sprout-contracts. Internal: only services call it. */
@RestController
public class LedgerController {

    public record PostingDto(@NotBlank String account, @NotNull Side side, @NotBlank String amount) {}

    public record PostEntryRequest(@NotBlank @Size(max = 120) String idempotencyKey,
                                   @NotNull @Size(max = 200) String description,
                                   @Size(max = 120) String reference,
                                   @NotNull @Size(min = 2, max = 20) List<@Valid PostingDto> postings) {}

    public record EntryDto(String id, String idempotencyKey, String description, String reference,
                           List<PostingDto> postings, String postedAt) {}

    private final Ledger ledger;

    public LedgerController(Ledger ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/v1/journal-entries")
    public ResponseEntity<EntryDto> post(@Valid @RequestBody PostEntryRequest req) {
        List<Posting> postings = req.postings().stream()
                .map(p -> new Posting(p.account(), p.side(), Money.paise(p.amount()))).toList();
        Posted posted = ledger.post(req.idempotencyKey(), req.description(), req.reference(), postings);
        return ResponseEntity.status(posted.created() ? HttpStatus.CREATED : HttpStatus.OK).body(dto(posted.entry()));
    }

    @GetMapping("/v1/accounts/{account}")
    public Map<String, String> account(@PathVariable String account) {
        return Map.of("account", account, "kind", Ledger.typeOf(account).kind().name(),
                "balance", Money.rupees(ledger.balance(account)));
    }

    @GetMapping("/v1/accounts/{account}/entries")
    public Map<String, List<EntryDto>> entries(@PathVariable String account, @RequestParam(required = false) Integer limit) {
        int n = limit == null ? 50 : limit;
        if (n < 1 || n > 200) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and 200.");
        }
        return Map.of("entries", ledger.entries(account, n).stream().map(LedgerController::dto).toList());
    }

    @GetMapping("/v1/accounts/{account}/statement")
    public Map<String, Object> statement(@PathVariable String account, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        Ledger.Statement s = ledger.statement(account, from, to);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("account", s.account());
        m.put("from", s.from().toString());
        m.put("to", s.to().toString());
        m.put("openingBalance", Money.rupees(s.opening()));
        m.put("closingBalance", Money.rupees(s.closing()));
        m.put("complete", s.complete());
        m.put("lines", s.lines().stream().map(l -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("entryId", l.entryId().toString());
            line.put("postedAt", l.postedAt().toString());
            line.put("description", l.description());
            if (l.reference() != null) {
                line.put("reference", l.reference());
            }
            line.put("side", l.side().name());
            line.put("amount", Money.rupees(l.paise()));
            line.put("balanceAfter", Money.rupees(l.balanceAfter()));
            return line;
        }).toList());
        return m;
    }

    @GetMapping("/v1/balances")
    public Map<String, Object> balances(@RequestParam String pattern) {
        return Map.of("balances", ledger.balances(pattern).stream()
                .map(b -> Map.of("account", b.account(), "kind", b.kind().name(), "balance", Money.rupees(b.balance()))).toList());
    }

    @GetMapping("/v1/trial-balance")
    public Map<String, Object> trialBalance() {
        Ledger.TrialBalance t = ledger.trialBalance();
        return Map.of("assets", Money.rupees(t.assets()), "liabilities", Money.rupees(t.liabilities()),
                "balanced", t.assets() == t.liabilities(), "accounts", t.accounts());
    }

    static EntryDto dto(Entry e) {
        return new EntryDto(e.id().toString(), e.idempotencyKey(), e.description(), e.reference(),
                e.postings().stream().map(p -> new PostingDto(p.account(), p.side(), Money.rupees(p.paise()))).toList(),
                e.postedAt().toString());
    }
}
