package io.rentgate;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class PersistenceTest {
    @TempDir Path directory;

    private ConfigurableApplicationContext start(String url) {
        var context=new SpringApplicationBuilder(RentGateApplication.class).run(
                "--server.port=0", "--spring.datasource.url="+url, "--RENT_GATE_ADMIN_PASSWORD=test-only-password",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN");
        var principal=context.getBean(AccountService.class).principal("admin");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
        return context;
    }

    @AfterEach void clearSecurity() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    @Test void upgradesV4PairingHardwareIntoPersistentBindingMetadata() throws Exception {
        String url="jdbc:h2:file:"+directory.resolve("v4").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE";
        org.flywaydb.core.Flyway.configure().dataSource(url,"sa","").target("4").load().migrate();
        try(var connection=java.sql.DriverManager.getConnection(url,"sa", "");var statement=connection.createStatement()) {
            statement.executeUpdate("INSERT INTO device(device_id,name,key_hash,created_ts) VALUES('paired-v4','旧版配对设备','old-hash',1)");
            statement.executeUpdate("INSERT INTO device_pairing(device_id,expires_ts,approved,hardware_id) VALUES('paired-v4',1,TRUE,'AABBCC112233')");
        }
        try(var upgraded=start(url)) {
            assertThat(upgraded.getBean(DeviceService.class).device("paired-v4").hardwareId()).isEqualTo("AABBCC112233");
        }
    }

    @Test void restartRestoresDeviceKeyExpiryAndEmptyOrderTable() {
        String url = "jdbc:h2:file:"+directory.resolve("persistent").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE";
        String key;
        long expiry;
        try (var first = start(url)) {
            DeviceService service = first.getBean(DeviceService.class);
            key = service.create("persistent-001", "重启测试").deviceKey();
            expiry = service.release("persistent-001", 30).expireTs();
            first.getBean(AccountService.class).changeOwnPassword("test-only-password","Changed123");
        }
        try (var second = start(url)) {
            DeviceService service = second.getBean(DeviceService.class);
            var poll = service.poll("persistent-001", key, false);
            assertThat(poll.action()).isEqualTo("on");
            assertThat(poll.expireTs()).isEqualTo(expiry);
            assertThat(service.credentials("persistent-001").deviceKey()).isEqualTo(key);
            var account=second.getBean(AccountService.class).principal("admin");
            var encoder=second.getBean(org.springframework.security.crypto.password.PasswordEncoder.class);
            assertThat(encoder.matches("Changed123",account.passwordHash())).isTrue();
            assertThat(encoder.matches("test-only-password",account.passwordHash())).isFalse();
            assertThat(second.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM rental_order", Integer.class)).isZero();
        }
    }

    @Test void upgradesLegacyDatabaseWithoutChangingDeviceCredentialsOrLease() throws Exception {
        String url="jdbc:h2:file:"+directory.resolve("legacy").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE";
        String key="legacy-device-secret";
        long expiry=java.time.Instant.now().getEpochSecond()+1800;
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try(var connection=java.sql.DriverManager.getConnection(url,"sa", "")) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ClassPathResource("db/migration/V1__device_control.sql"));
            try(var statement=connection.prepareStatement("INSERT INTO device(device_id,name,key_hash,status,expire_ts,created_ts) VALUES('legacy-001','旧设备',?,'on',?,1)")) {
                statement.setString(1,hash);statement.setLong(2,expiry);statement.executeUpdate();
            }
            try(var statement=connection.createStatement()) {
                statement.executeUpdate("INSERT INTO rental_order(order_no,device_id,duration_min,amount,status,created_ts) VALUES('legacy-order','legacy-001',30,12.50,'paid',1)");
            }
        }
        try(var upgraded=start(url)) {
            var service=upgraded.getBean(DeviceService.class);
            assertThat(service.poll("legacy-001",key,false).expireTs()).isEqualTo(expiry);
            assertThat(service.credentials("legacy-001").deviceKey()).isEqualTo(key);
            var device=service.device("legacy-001");
            assertThat(device.userId()).isNull();
            assertThat(device.publicToken()).isNotBlank();
            assertThat(service.guest(device.publicToken()).trialAvailable()).isFalse();
            assertThat(upgraded.getBean(JdbcTemplate.class).queryForObject("SELECT amount FROM rental_order WHERE order_no='legacy-order'",java.math.BigDecimal.class)).isEqualByComparingTo("12.50");
        }
    }
}
