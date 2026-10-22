package io.rentgate;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class PersistenceTest {
    @TempDir Path directory;

    private ConfigurableApplicationContext start(String url) {
        return new SpringApplicationBuilder(RentGateApplication.class).run(
                "--server.port=0", "--spring.datasource.url="+url, "--RENT_GATE_ADMIN_PASSWORD=test-only-password",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }

    @Test void restartRestoresDeviceKeyExpiryAndEmptyOrderTable() {
        String url = "jdbc:h2:file:"+directory.resolve("persistent").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE";
        String key;
        long expiry;
        try (var first = start(url)) {
            DeviceService service = first.getBean(DeviceService.class);
            key = service.create("persistent-001", "重启测试").deviceKey();
            expiry = service.release("persistent-001", 30).expireTs();
        }
        try (var second = start(url)) {
            DeviceService service = second.getBean(DeviceService.class);
            var poll = service.poll("persistent-001", key, false);
            assertThat(poll.action()).isEqualTo("on");
            assertThat(poll.expireTs()).isEqualTo(expiry);
            assertThat(second.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM rental_order", Integer.class)).isZero();
        }
    }
}
