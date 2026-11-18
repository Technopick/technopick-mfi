package ke.co.technopick.mfi.core.web;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 problem details. Business-rule violations raised by the database carry a stable
 * code (e.g. SAVINGS_INSUFFICIENT_AVAILABLE) which is returned; SQL text and stack traces never are.
 */
@RestControllerAdvice
public class ProblemHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemHandler.class);
    private static final Pattern RULE_CODE = Pattern.compile("\\b([A-Z]+_[A-Z_]+):");

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, "conflict", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail denied(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", "access denied");
    }

    /** A concurrent duplicate lost the race on a unique key; retrying returns the original result. */
    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicate(DuplicateKeyException e) {
        ProblemDetail p = problem(HttpStatus.CONFLICT, "duplicate", "concurrent duplicate request; retry");
        p.setProperty("code", "DUPLICATE_IN_FLIGHT");
        return p;
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail database(DataAccessException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        Matcher m = RULE_CODE.matcher(message);
        if (m.find()) {
            ProblemDetail p = problem(HttpStatus.UNPROCESSABLE_CONTENT, "business-rule", "rejected by a ledger rule");
            p.setProperty("code", m.group(1));
            return p;
        }
        LOG.error("database error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "unexpected error");
    }

    private static ProblemDetail problem(HttpStatus status, String type, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("https://technopick.co.ke/problems/" + type));
        return p;
    }
}
