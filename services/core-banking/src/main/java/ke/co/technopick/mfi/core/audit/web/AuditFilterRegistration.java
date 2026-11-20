package ke.co.technopick.mfi.core.audit.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps the audit filter out of the plain servlet chain: it runs only inside the security chain. */
@Configuration
public class AuditFilterRegistration {

    @Bean
    FilterRegistrationBean<StaffAuditFilter> staffAuditFilterRegistration(StaffAuditFilter filter) {
        FilterRegistrationBean<StaffAuditFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
