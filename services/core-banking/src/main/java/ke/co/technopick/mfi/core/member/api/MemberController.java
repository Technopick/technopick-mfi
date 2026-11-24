package ke.co.technopick.mfi.core.member.api;

import java.time.LocalDate;
import java.util.UUID;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.member.application.MemberRateLimiter;
import ke.co.technopick.mfi.core.member.application.MemberService;
import ke.co.technopick.mfi.core.member.application.MemberService.MemberLoanDetail;
import ke.co.technopick.mfi.core.member.application.MemberService.MemberOverview;
import ke.co.technopick.mfi.core.member.application.MemberService.MemberPayment;
import ke.co.technopick.mfi.core.member.application.MemberService.MemberPaymentRequest;
import ke.co.technopick.mfi.core.savings.application.SavingsAccountService.SavingsStatement;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The member's own API (ADR-0033), for a USSD menu or mobile app acting with the member's token.
 * Only tokens with the MEMBER role reach it, and members reach nothing else; the customer is
 * always the one named in the token, never in the request.
 */
@RestController
@RequestMapping(path = "/api/v1/member", produces = "application/json")
@PreAuthorize("hasRole('MEMBER')")
public class MemberController {

    private final MemberService service;
    private final MemberRateLimiter limiter;
    private final CurrentCaller caller;

    public MemberController(MemberService service, MemberRateLimiter limiter, CurrentCaller caller) {
        this.service = service;
        this.limiter = limiter;
        this.caller = caller;
    }

    private String me() {
        limiter.check(caller.tenantId() + "|" + caller.actor());
        return caller.memberCustomerNumber();
    }

    @GetMapping("/me")
    public MemberOverview overview() {
        String me = me();
        return service.overview(caller.tenantId(), me);
    }

    @GetMapping("/accounts/{accountNumber}/statement")
    public SavingsStatement statement(@PathVariable String accountNumber,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String me = me();
        return service.statement(caller.tenantId(), me, accountNumber, from, to);
    }

    @GetMapping("/loans/{loanNumber}")
    public MemberLoanDetail loan(@PathVariable String loanNumber) {
        String me = me();
        return service.loan(caller.tenantId(), me, loanNumber);
    }

    @PostMapping(path = "/payments", consumes = "application/json")
    public MemberPayment pay(@RequestBody MemberPaymentRequest request) {
        String me = me();
        return service.pay(caller.tenantId(), me, request, caller.actor());
    }

    @GetMapping("/payments/{requestId}")
    public MemberPayment payment(@PathVariable UUID requestId) {
        String me = me();
        return service.payment(caller.tenantId(), me, requestId);
    }
}
