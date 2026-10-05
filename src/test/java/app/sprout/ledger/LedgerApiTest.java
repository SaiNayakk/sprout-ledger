package app.sprout.ledger;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.sprout.contracts.Contracts;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The ledger contract on a real Postgres, every response checked against ledger-v1.yaml. */
@Testcontainers
@SpringBootTest(properties = "spring.config.name=ledger")
@AutoConfigureMockMvc
class LedgerApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=ledger");
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForInlineApiSpecification(Contracts.read(Contracts.LEDGER_V1))
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
            .build();
    static final ResultMatcher MATCHES_CONTRACT = openApi().isValid(CONTRACT);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient db;

    static String cash(UUID user) {
        return "customer:" + user + ":cash";
    }

    static String hold(UUID user) {
        return "customer:" + user + ":withdrawal-hold";
    }

    Map<String, Object> entry(String key, String... legs) {
        List<Map<String, String>> postings = new ArrayList<>();
        for (int i = 0; i < legs.length; i += 3) {
            postings.add(Map.of("account", legs[i], "side", legs[i + 1], "amount", legs[i + 2]));
        }
        return Map.of("idempotencyKey", key, "description", "test", "postings", postings);
    }

    ResultActions postEntry(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/v1/journal-entries").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    /** Puts money into a customer's cash, as a completed deposit would. */
    void deposit(UUID user, String amount) throws Exception {
        postEntry(entry("deposit:" + UUID.randomUUID(), "sprout:bank", "DEBIT", amount, cash(user), "CREDIT", amount))
                .andExpect(status().isCreated());
    }

    String balance(String account) throws Exception {
        return json.readTree(mvc.perform(get("/v1/accounts/" + account)).andReturn().getResponse().getContentAsString())
                .path("balance").asText();
    }

    @Test
    void aBalancedEntryIsPostedAndMovesBothBalances() throws Exception {
        UUID user = UUID.randomUUID();
        String before = balance("sprout:bank");
        postEntry(entry("deposit:" + UUID.randomUUID(), "sprout:bank", "DEBIT", "1500.50", cash(user), "CREDIT", "1500.50"))
                .andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.postings[0].amount").value("1500.50"));
        assertThat(balance(cash(user))).isEqualTo("1500.50");
        assertThat(Double.parseDouble(balance("sprout:bank")) - Double.parseDouble(before)).isEqualTo(1500.50);
        mvc.perform(get("/v1/accounts/" + cash(user))).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.kind").value("LIABILITY"));
        mvc.perform(get("/v1/accounts/" + cash(user) + "/entries")).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.entries.length()").value(1));
    }

    @Test
    void aSharePurchaseWithItsChargesIsBookedFromTheOrderHold() throws Exception {
        UUID user = UUID.randomUUID();
        String held = "customer:" + user + ":order-hold";
        deposit(user, "10000");
        // the order blocks money, then the fill takes the trade value and every charge, and the rest goes back
        postEntry(entry("order-hold:" + UUID.randomUUID(), cash(user), "DEBIT", "5200", held, "CREDIT", "5200"))
                .andExpect(status().isCreated());
        postEntry(entry("fill:" + UUID.randomUUID(), held, "DEBIT", "5200",
                "sprout:clearing-payable", "CREDIT", "5000",
                "sprout:payable:stt", "CREDIT", "5",
                "sprout:payable:stamp-duty", "CREDIT", "0.75",
                "sprout:payable:exchange-charges", "CREDIT", "0.15",
                "sprout:payable:sebi-fees", "CREDIT", "0.01",
                "sprout:payable:gst", "CREDIT", "0.03",
                "sprout:income:brokerage", "CREDIT", "1",
                cash(user), "CREDIT", "193.06")).andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT);
        assertThat(balance(cash(user))).isEqualTo("4993.06");
        assertThat(balance(held)).isEqualTo("0.00");
        mvc.perform(get("/v1/accounts/customer:" + user + ":dues")).andExpect(jsonPath("$.kind").value("ASSET"));
        mvc.perform(get("/v1/accounts/customer:" + user + ":unsettled")).andExpect(jsonPath("$.kind").value("LIABILITY"));
        mvc.perform(get("/v1/accounts/sprout:payable:income-tax")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void theSameKeyTwiceIsOneEntryAndADifferentEntryUnderItIsRefused() throws Exception {
        UUID user = UUID.randomUUID();
        String key = "deposit:" + UUID.randomUUID();
        var body = entry(key, "sprout:bank", "DEBIT", "100", cash(user), "CREDIT", "100");
        String first = json.readTree(postEntry(body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();
        postEntry(body).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.id").value(first));
        assertThat(balance(cash(user))).isEqualTo("100.00");
        postEntry(entry(key, "sprout:bank", "DEBIT", "999", cash(user), "CREDIT", "999"))
                .andExpect(status().isConflict()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(balance(cash(user))).isEqualTo("100.00");
    }

    @Test
    void unbalancedUnknownAndMalformedEntriesAreRefused() throws Exception {
        UUID user = UUID.randomUUID();
        postEntry(entry("x:" + UUID.randomUUID(), "sprout:bank", "DEBIT", "100", cash(user), "CREDIT", "99.99"))
                .andExpect(status().isUnprocessableEntity()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.code").value("UNBALANCED"));
        postEntry(entry("x:" + UUID.randomUUID(), "sprout:bank", "DEBIT", "1", "someone:else", "CREDIT", "1"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNKNOWN_ACCOUNT"));
        postEntry(entry("x:" + UUID.randomUUID(), "sprout:bank", "DEBIT", "1.005", cash(user), "CREDIT", "1.005"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postEntry(entry("x:" + UUID.randomUUID(), "sprout:bank", "DEBIT", "0", cash(user), "CREDIT", "0"))
                .andExpect(status().isBadRequest());
        postEntry(Map.of("idempotencyKey", "x", "description", "one leg",
                "postings", List.of(Map.of("account", "sprout:bank", "side", "DEBIT", "amount", "1"))))
                .andExpect(status().isBadRequest());
        assertThat(balance(cash(user))).isEqualTo("0.00");
    }

    @Test
    void aCustomerCanNeverGoBelowZeroAndNothingMovesWhenRefused() throws Exception {
        UUID user = UUID.randomUUID();
        deposit(user, "300");
        postEntry(entry("hold:" + UUID.randomUUID(), cash(user), "DEBIT", "300.01", hold(user), "CREDIT", "300.01"))
                .andExpect(status().isUnprocessableEntity()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        assertThat(balance(cash(user))).isEqualTo("300.00");
        assertThat(balance(hold(user))).isEqualTo("0.00");
    }

    @Test
    void racingWithdrawalsCanNeverSpendTheSameMoneyTwice() throws Exception {
        UUID user = UUID.randomUUID();
        deposit(user, "500");
        var pool = Executors.newFixedThreadPool(10);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            var body = entry("hold:" + UUID.randomUUID(), cash(user), "DEBIT", "100", hold(user), "CREDIT", "100");
            results.add(pool.submit((Callable<Integer>) () -> postEntry(body).andReturn().getResponse().getStatus()));
        }
        int created = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 201) {
                created++;
            } else {
                assertThat(f.get()).isEqualTo(422);
            }
        }
        pool.shutdown();
        assertThat(created).as("₹500 covers exactly five ₹100 withdrawals").isEqualTo(5);
        assertThat(balance(cash(user))).isEqualTo("0.00");
        assertThat(balance(hold(user))).isEqualTo("500.00");
    }

    @Test
    void theBooksAlwaysBalance() throws Exception {
        UUID user = UUID.randomUUID();
        deposit(user, "250.75");
        postEntry(entry("hold:" + UUID.randomUUID(), cash(user), "DEBIT", "50.25", hold(user), "CREDIT", "50.25"));
        mvc.perform(get("/v1/trial-balance")).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.balanced").value(true));
    }

    @Test
    void theJournalCannotBeEditedEvenDirectlyInTheDatabase() throws Exception {
        deposit(UUID.randomUUID(), "10");
        assertThatThrownBy(() -> db.sql("UPDATE ledger.postings SET amount_paise = 1").update())
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> db.sql("DELETE FROM ledger.journal_entries").update())
                .hasMessageContaining("immutable");
    }
}
