package io.rentgate;

import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AccountBootstrap implements ApplicationRunner {
    private final AccountService accounts;
    private final JdbcTemplate jdbc;
    private final String password;

    public AccountBootstrap(AccountService accounts, JdbcTemplate jdbc, @Value("${RENT_GATE_ADMIN_PASSWORD:}") String password) {
        this.accounts=accounts; this.jdbc=jdbc; this.password=password;
    }

    @Override public void run(ApplicationArguments arguments) {
        String initial = password.isBlank() ? UUID.randomUUID().toString() : password;
        if (accounts.bootstrapAdmin(initial) && password.isBlank()) {
            LoggerFactory.getLogger(getClass()).warn("Initial admin login: admin / {} (saved in database; change it after login)",initial);
        }
        for (String id : jdbc.queryForList("SELECT device_id FROM device WHERE public_token IS NULL",String.class)) {
            jdbc.update("UPDATE device SET public_token=? WHERE device_id=? AND public_token IS NULL",UUID.randomUUID().toString(),id);
        }
    }
}
