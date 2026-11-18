package ke.co.technopick.mfi.core.savings.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.savings.persistence.JdbcDepositAccounts;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Staff balance enquiry. The tenant comes only from the caller's token, never from the request. */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    public record BalanceResponse(String accountNumber, String ledgerBalance, String activeHolds,
                                  String availableBalance) { }

    private final CurrentCaller caller;
    private final TenantSession session;
    private final JdbcDepositAccounts accounts;

    public AccountController(CurrentCaller caller, TenantSession session, JdbcDepositAccounts accounts) {
        this.caller = caller;
        this.session = session;
        this.accounts = accounts;
    }

    @GetMapping("/{accountNumber}/balance")
    @PreAuthorize("hasAnyRole('TELLER', 'BRANCH_MANAGER')")
    @Transactional(readOnly = true)
    public BalanceResponse balance(@PathVariable String accountNumber) {
        session.bind(caller.tenantId());
        BigDecimal[] b = accounts.balances(accountNumber).orElseThrow(() -> new NotFoundException("account not found"));
        return new BalanceResponse(accountNumber, plain(b[0]), plain(b[1]), plain(b[2]));
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
