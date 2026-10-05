package app.sprout.ledger.domain;

import org.springframework.http.HttpStatus;

/** The stable error codes of the ledger contract. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request isn't valid"),
    UNBALANCED(HttpStatus.UNPROCESSABLE_ENTITY, "Debits and credits differ"),
    UNKNOWN_ACCOUNT(HttpStatus.UNPROCESSABLE_ENTITY, "No such kind of account"),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY, "Not enough money"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Idempotency key already used"),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
