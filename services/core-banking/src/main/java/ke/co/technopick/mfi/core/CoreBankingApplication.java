package ke.co.technopick.mfi.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Core banking modular monolith: the only component that decides balances and posts to the ledger. */
@SpringBootApplication
public class CoreBankingApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreBankingApplication.class, args);
    }
}
