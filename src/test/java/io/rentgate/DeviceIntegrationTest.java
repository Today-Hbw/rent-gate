package io.rentgate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1", "RENT_GATE_ADMIN_PASSWORD=test-only-password"})
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class DeviceIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DeviceService service;
    @Autowired MutableClock clock;

    static class MutableClock extends Clock {
        volatile long seconds = 1700000000;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochSecond(seconds); }
    }
    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM rental_order"); jdbc.update("DELETE FROM device"); clock.seconds = 1700000000;
    }
    private JsonNode create() throws Exception {
        return json.readTree(mvc.perform(post("/admin/devices").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_id\":\"demo-001\",\"name\":\"一号桌\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    private void release(int minutes) throws Exception {
        mvc.perform(post("/admin/release").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_id\":\"demo-001\",\"duration_min\":" + minutes + "}"))
                .andExpect(status().isOk());
    }

    @Test void manualReleasePollExpiryAndNoOrders() throws Exception {
        String key = create().get("device_key").asText();
        release(30);
        mvc.perform(post("/poll").with(anonymous()).header("X-Device-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_id\":\"demo-001\",\"relay_on\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.action").value("on"))
                .andExpect(jsonPath("$.expire_ts").value(1700001800))
                .andExpect(jsonPath("$.server_ts").value(1700000000))
                .andExpect(jsonPath("$.remaining_seconds").value(1800));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rental_order", Integer.class)).isZero();
        clock.seconds += 1800;
        mvc.perform(post("/poll").with(anonymous()).header("X-Device-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_id\":\"demo-001\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.action").value("off"))
                .andExpect(jsonPath("$.remaining_seconds").value(0));
        assertThat(jdbc.queryForObject("SELECT status FROM device", String.class)).isEqualTo("off");
    }

    @Test void stopTakesEffectOnNextPoll() throws Exception {
        String key = create().get("device_key").asText(); release(1);
        mvc.perform(post("/admin/stop").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"device_id\":\"demo-001\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("off"));
        assertThat(service.poll("demo-001", key, false).action()).isEqualTo("off");
        assertThat(service.poll("demo-001", key, false).expireTs()).isZero();
    }

    @Test void repeatReleaseReplacesExpiryAndDoesNotAccumulate() throws Exception {
        create(); release(30); clock.seconds += 60; release(1);
        assertThat(jdbc.queryForObject("SELECT expire_ts FROM device", Long.class)).isEqualTo(1700000120L);
    }

    @Test void scheduledExpiryWorksWithoutDevicePolling() throws Exception {
        create(); release(1); clock.seconds += 60; service.expireLeases();
        assertThat(jdbc.queryForObject("SELECT status FROM device", String.class)).isEqualTo("off");
    }

    @Test void heartbeatDistinguishesInstructionFromReportedRelay() throws Exception {
        String key = create().get("device_key").asText(); release(30);
        service.poll("demo-001", key, false);
        mvc.perform(get("/admin/devices")).andExpect(jsonPath("$[0].online").value(true))
                .andExpect(jsonPath("$[0].status").value("on"))
                .andExpect(jsonPath("$[0].relay_on").value(false));
        clock.seconds += 13;
        mvc.perform(get("/admin/devices")).andExpect(jsonPath("$[0].online").value(false))
                .andExpect(jsonPath("$[0].remaining_seconds").value(1787));
    }

    @Test void credentialsAreHashedAndNeverListed() throws Exception {
        String key = create().get("device_key").asText();
        assertThat(key).hasSize(43);
        assertThat(jdbc.queryForObject("SELECT key_hash FROM device", String.class)).hasSize(64).isNotEqualTo(key);
        mvc.perform(get("/admin/devices")).andExpect(jsonPath("$[0].device_key").doesNotExist())
                .andExpect(jsonPath("$[0].key_hash").doesNotExist());
    }

    @Test void unknownDeviceAndWrongOrMissingKeyCannotPoll() throws Exception {
        create();
        for (String body : new String[]{"{\"device_id\":\"demo-001\"}", "{\"device_id\":\"unknown\"}"}) {
            mvc.perform(post("/poll").with(anonymous()).header("X-Device-Key", "wrong").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("设备认证失败"));
        }
        mvc.perform(post("/poll").with(anonymous()).contentType(MediaType.APPLICATION_JSON).content("{\"device_id\":\"demo-001\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT last_seen_ts FROM device", Long.class)).isNull();
    }

    @Test void keyRotationRevokesOldCredentials() throws Exception {
        String old = create().get("device_key").asText();
        String key = json.readTree(mvc.perform(post("/admin/rotate-key").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_id\":\"demo-001\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("device_key").asText();
        assertThat(key).isNotEqualTo(old);
        mvc.perform(post("/poll").with(anonymous()).header("X-Device-Key", old).contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_id\":\"demo-001\"}")).andExpect(status().isUnauthorized());
        assertThat(service.poll("demo-001", key, false).action()).isEqualTo("off");
    }

    @Test void duplicateDeviceIsAConflict() throws Exception {
        create();
        mvc.perform(post("/admin/devices").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_id\":\"demo-001\",\"name\":\"重复\"}")).andExpect(status().isConflict());
    }

    @Test void releaseValidationRejectsMissingNegativeFractionAndOverLimit() throws Exception {
        create();
        for (String minutes : new String[]{"0", "-1", "1441", "1.5", "null", "\"x\""}) {
            mvc.perform(post("/admin/release").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"device_id\":\"demo-001\",\"duration_min\":"+minutes+"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/admin/release").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_id\":\"demo-001\"}")).andExpect(status().isBadRequest());
        release(1440);
        assertThat(jdbc.queryForObject("SELECT expire_ts FROM device", Long.class)).isEqualTo(1700086400L);
    }

    @Test void invalidDeviceNameIdAndMalformedJsonRejected() throws Exception {
        for (String body : new String[]{"{}", "{", "{\"device_id\":\"../x\",\"name\":\"test\"}",
                "{\"device_id\":\"valid\",\"name\":\"   \"}"}) {
            mvc.perform(post("/admin/devices").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test void missingDeviceCannotBeReleasedOrStopped() throws Exception {
        for (String endpoint : new String[]{"release", "stop", "rotate-key"}) {
            mvc.perform(post("/admin/"+endpoint).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"device_id\":\"missing\",\"duration_min\":1}")).andExpect(status().isNotFound());
        }
    }

    @Test void adminRoutesNeedLoginAndCsrf() throws Exception {
        mvc.perform(get("/admin/devices").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get("/").with(anonymous())).andExpect(status().is3xxRedirection());
        mvc.perform(post("/admin/release").contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_id\":\"x\",\"duration_min\":1}")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/csrf")).andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(get("/health").with(anonymous())).andExpect(status().isOk());
    }

    @Test void actualFormLoginAcceptsConfiguredPasswordOnly() throws Exception {
        mvc.perform(post("/login").with(anonymous()).with(csrf()).param("username","admin").param("password","wrong"))
                .andExpect(redirectedUrl("/login?error"));
        mvc.perform(post("/login").with(anonymous()).with(csrf()).param("username","admin").param("password","test-only-password"))
                .andExpect(redirectedUrl("/"));
    }

    @Test void concurrentPollsDoNotLoseRelease() throws Exception {
        String key = create().get("device_key").asText();
        var pool = Executors.newFixedThreadPool(6);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int i=0;i<20;i++) {
                tasks.add(() -> { service.poll("demo-001", key, false); return null; });
                tasks.add(() -> { service.release("demo-001", 30); return null; });
            }
            for (var result : pool.invokeAll(tasks)) result.get();
        } finally { pool.shutdownNow(); }
        assertThat(service.poll("demo-001", key, true).remainingSeconds()).isEqualTo(1800);
    }
}
