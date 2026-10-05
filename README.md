# sprout-ledger

Sprout's books: the single source of truth for money. Internal only; the gateway never routes here.

- **Double entry.** Every change is a journal entry whose debits equal its credits.
- **Immutable.** Entries are never edited or deleted, enforced by database triggers, not just the code; a mistake is corrected with a reversing entry.
- **Idempotent.** Each entry carries an idempotency key: the same key and content returns the original entry, a different entry under it is refused (`409`).
- **No overdrafts.** Customer accounts can't go below zero. Posting locks every account the entry touches, always in name order (so two entries can't deadlock), and refuses the whole entry if any guarded balance would go negative. A test races 20 withdrawals against money for 5: exactly 5 succeed.
- **Money is never a float.** Amounts are decimal strings in the API and paise (`long`) inside.
- **Trial balance.** `GET /v1/trial-balance` checks assets equal liabilities.

| Account | Kind |
|---|---|
| `customer:{userId}:cash` | liability: money Sprout owes the customer |
| `customer:{userId}:withdrawal-hold` | liability: on its way out |
| `sprout:bank` | asset: client money held at Sprout Bank |

**Statements and reconciliation.** An account's statement between two dates comes with its opening
balance and the balance after every line (computed from the journal), and every account matching a
pattern (`customer:*:order-hold`) can be listed with its balance.

## Part of Sprout

[Sprout](https://sainayakk.github.io/sprout-platform/) is a simulated brokerage built from scratch as
separate services, each with its own repository and contract. Architecture, environments and test
evidence live in [sprout-platform](https://github.com/SaiNayakk/sprout-platform); this service's API is
[`ledger-v1.yaml`](https://github.com/SaiNayakk/sprout-contracts/blob/main/src/main/resources/sprout/contracts/openapi/ledger-v1.yaml)
in sprout-contracts. It runs inside the **money** host.

`./mvnw verify` runs the tests on a real Postgres (Docker needed), every JSON response checked against
the contract.

## License

MIT
