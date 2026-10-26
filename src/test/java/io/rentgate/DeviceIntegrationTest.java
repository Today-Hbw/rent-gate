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
@WithMockUser(username="admin",roles = "ADMIN")
class DeviceIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DeviceService service;
    @Autowired MutableClock clock;
    @Autowired DeviceProvisioningService provisioning;

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
        java.util.concurrent.ExecutorService pool = new org.springframework.security.concurrent.DelegatingSecurityContextExecutorService(
                Executors.newFixedThreadPool(6),org.springframework.security.test.context.TestSecurityContextHolder.getContext());
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

    @Test void viewableKeysAreEncryptedAndLegacyKeysBackfillAfterAuthenticatedPoll() throws Exception {
        String key=create().get("device_key").asText();
        assertThat(jdbc.queryForObject("SELECT key_cipher FROM device",String.class)).doesNotContain(key);
        mvc.perform(get("/admin/devices/demo-001/credentials")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.device_key").value(key));
        jdbc.update("UPDATE device SET key_cipher=NULL");
        mvc.perform(get("/admin/devices/demo-001/credentials")).andExpect(jsonPath("$.device_key").isEmpty());
        mvc.perform(post("/poll").with(anonymous()).header("X-Device-Key","bad").contentType(MediaType.APPLICATION_JSON).content("{\"device_id\":\"demo-001\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT key_cipher FROM device",String.class)).isNull();
        service.poll("demo-001",key,false);
        asAdmin();
        assertThat(service.credentials("demo-001").deviceKey()).isEqualTo(key);
        mvc.perform(get("/admin/devices/demo-001/credentials").with(anonymous())).andExpect(status().isUnauthorized());
    }

    @Test void setupRequiresIdleOnlineSupportedFirmwareAndIsRetriedUntilAcknowledged() throws Exception {
        String key=create().get("device_key").asText();
        mvc.perform(post("/admin/devices/demo-001/setup").with(csrf())).andExpect(status().isConflict());
        service.poll("demo-001",key,false);
        mvc.perform(post("/admin/devices/demo-001/setup").with(csrf())).andExpect(status().isConflict());
        service.poll("demo-001",key,false,null,"0.2.0");
        release(1);
        mvc.perform(post("/admin/devices/demo-001/setup").with(csrf())).andExpect(status().isConflict());
        asAdmin();
        service.stop("demo-001");
        String request=provisioning.requestSetup("demo-001").requestId();
        assertThat(provisioning.requestSetup("demo-001").requestId()).isEqualTo(request);
        assertThat(service.poll("demo-001",key,false).configRequestId()).isEqualTo(request);
        assertThat(service.poll("demo-001",key,false,"wrong",null).configRequestId()).isEqualTo(request);
        mvc.perform(post("/admin/release").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"device_id\":\"demo-001\",\"duration_min\":1}"))
                .andExpect(status().isConflict());
        assertThat(service.poll("demo-001",key,false,request,null).configRequestId()).isNull();
        asAdmin();
        String next=provisioning.requestSetup("demo-001").requestId();
        assertThat(next).isNotEqualTo(request); clock.seconds+=300;
        assertThat(service.poll("demo-001",key,false).configRequestId()).isNull();
    }

    private JsonNode pair(String token) throws Exception {
        JsonNode result=json.readTree(mvc.perform(post("/device/pair").with(anonymous()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("device_id","demo-001","token",token))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString());
        asAdmin();return result;
    }
    private void asAdmin() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin","",java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @Test void pairingNeedsExplicitWindowPhysicalCodeAndMatchingSecretToken() throws Exception {
        String old=create().get("device_key").asText(), token="a".repeat(43), other="b".repeat(43);
        assertThat(pair(token).get("status").asText()).isEqualTo("waiting");
        provisioning.beginPairing("demo-001");
        JsonNode pending=pair(token); String code=pending.get("pairing_code").asText();
        assertThat(code).matches("[0-9]{6}");assertThat(pending.get("device_key").isNull()).isTrue();
        assertThat(pair(other).get("status").asText()).isEqualTo("busy");
        String wrong=code.equals("000000")?"111111":"000000";
        mvc.perform(post("/admin/devices/demo-001/pairing/confirm").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\""+wrong+"\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM device_pairing",Integer.class)).isEqualTo(1);
        asAdmin();
        provisioning.confirmPairing("demo-001",code);
        String key=pair(token).get("device_key").asText();assertThat(key).hasSize(43).isNotEqualTo(old);
        provisioning.confirmPairing("demo-001",code);assertThat(pair(token).get("device_key").asText()).isEqualTo(key);
        assertThat(pair(other).get("device_key").isNull()).isTrue();
        assertThat(service.credentials("demo-001").deviceKey()).isEqualTo(key);
        assertThat(service.poll("demo-001",key,false).action()).isEqualTo("off");
        mvc.perform(post("/poll").with(anonymous()).header("X-Device-Key",old).contentType(MediaType.APPLICATION_JSON).content("{\"device_id\":\"demo-001\"}"))
                .andExpect(status().isUnauthorized());
        clock.seconds+=300;assertThat(pair(token).get("device_key").isNull()).isTrue();
    }

    @Test void pairingExpiryAttemptLimitAndRotationRevokePendingSecrets() throws Exception {
        create();asAdmin();provisioning.beginPairing("demo-001");String token="x".repeat(43),code=pair(token).get("pairing_code").asText();
        String wrong=code.equals("000000")?"111111":"000000";
        for(int i=0;i<5;i++) mvc.perform(post("/admin/devices/demo-001/pairing/confirm").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\""+wrong+"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/devices/demo-001/pairing/confirm").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\""+code+"\"}"))
                .andExpect(status().isConflict());
        assertThat(pair(token).get("status").asText()).isEqualTo("waiting");
        provisioning.beginPairing("demo-001");pair(token);service.rotateKey("demo-001");
        assertThat(pair(token).get("status").asText()).isEqualTo("waiting");
    }

    @Test void provisioningAdminWritesRequireCsrfAndPublicPairingValidatesTokens() throws Exception {
        create();
        for(String endpoint:new String[]{"setup","setup/close","pairing","pairing-code","pairing/confirm"}) {
            mvc.perform(post("/admin/devices/demo-001/"+endpoint).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/admin/devices/demo-001/"+endpoint).with(anonymous()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/device/pair").with(anonymous()).contentType(MediaType.APPLICATION_JSON).content("{\"device_id\":\"demo-001\",\"token\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void generatedCodeAutomaticallyReturnsCredentialsWithoutDeviceIdAndCannotBeReusedByAnotherDevice() throws Exception {
        String old=create().get("device_key").asText();asAdmin();
        var code=provisioning.generateCode("demo-001");
        assertThat(code.code()).matches("[0-9]{8}");
        assertThat(jdbc.queryForObject("SELECT enrollment_code_hash FROM device_pairing",String.class)).isNotEqualTo(code.code());
        assertThat(provisioning.pollPairing("demo-001","a".repeat(43)).status()).isEqualTo("waiting");
        String body=json.writeValueAsString(java.util.Map.of("code",code.code(),"token","a".repeat(43),"hardware_id","AABBCCDDEEFF"));
        JsonNode credentials=json.readTree(mvc.perform(post("/device/enroll").with(anonymous()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString());
        assertThat(credentials.get("device_id").asText()).isEqualTo("demo-001");
        String key=credentials.get("device_key").asText();assertThat(key).hasSize(43).isNotEqualTo(old);
        assertThat(provisioning.enroll(code.code(),"a".repeat(43),"aabbccddeeff","code-retry").deviceKey()).isEqualTo(key);
        for(String attack:new String[]{"token","hardware"}) {
            String payload=json.writeValueAsString(java.util.Map.of("code",code.code(),"token",(attack.equals("token")?"b":"a").repeat(43),"hardware_id",attack.equals("hardware")?"112233445566":"AABBCCDDEEFF"));
            mvc.perform(post("/device/enroll").with(anonymous()).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isUnauthorized());
        }
        asAdmin();service.rotateKey("demo-001");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->provisioning.enroll(code.code(),"a".repeat(43),"AABBCCDDEEFF","rotated"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void codesExpireCancelAndSeparateConcurrentDevices() {
        asAdmin();service.create("code-a","A");service.create("code-b","B");
        var a=provisioning.generateCode("code-a");var b=provisioning.generateCode("code-b");
        assertThat(a.code()).isNotEqualTo(b.code());
        assertThat(provisioning.enroll(a.code(),"a".repeat(43),"000000000001","device-a").deviceId()).isEqualTo("code-a");
        assertThat(provisioning.enroll(b.code(),"b".repeat(43),"000000000002","device-b").deviceId()).isEqualTo("code-b");
        provisioning.cancelPairing("code-b");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->provisioning.enroll(b.code(),"b".repeat(43),"000000000002","cancelled"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        clock.seconds+=300;
        org.assertj.core.api.Assertions.assertThatThrownBy(()->provisioning.enroll(a.code(),"a".repeat(43),"000000000001","expired"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void concurrentRedemptionHasOnlyOneWinnerAndWinnerCanRetry() throws Exception {
        asAdmin();service.create("concurrent-code","Concurrent");String code=provisioning.generateCode("concurrent-code").code();
        var pool=Executors.newFixedThreadPool(2);
        try {
            var tasks=new java.util.ArrayList<java.util.concurrent.Callable<String>>();
            for(String token:new String[]{"x".repeat(43),"y".repeat(43)}) tasks.add(()->{
                try { provisioning.enroll(code,token,"AABBCC000001","parallel");return token; }
                catch(org.springframework.web.server.ResponseStatusException rejected) { return null; }
            });
            var results=pool.invokeAll(tasks);String first=results.get(0).get(),second=results.get(1).get();
            assertThat((first==null) != (second==null)).isTrue();String winner=first==null?second:first;
            String key=provisioning.enroll(code,winner,"AABBCC000001","parallel").deviceKey();
            assertThat(provisioning.enroll(code,winner,"AABBCC000001","parallel").deviceKey()).isEqualTo(key);
        } finally {pool.shutdownNow();}
    }

    @Test void enrollmentFailuresAreRateLimitedAndMalformedRequestsRejected() throws Exception {
        for(int i=0;i<20;i++) mvc.perform(post("/device/enroll").with(anonymous()).with(r->{r.setRemoteAddr("198.51.100.88");return r;})
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"00000000\",\"token\":\""+"z".repeat(43)+"\",\"hardware_id\":\"AABBCCDDEEFF\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/device/enroll").with(anonymous()).with(r->{r.setRemoteAddr("198.51.100.88");return r;}).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"00000000\",\"token\":\""+"z".repeat(43)+"\",\"hardware_id\":\"AABBCCDDEEFF\"}"))
                .andExpect(status().isTooManyRequests());
        mvc.perform(post("/device/enroll").with(anonymous()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"short\",\"token\":\"x\",\"hardware_id\":\"bad\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void backendCanCloseAnActivePortalAndAcknowledgementEndsTheCommand() throws Exception {
        String key=create().get("device_key").asText();asAdmin();
        service.poll("demo-001",key,false,null,"0.2.0");
        mvc.perform(post("/admin/devices/demo-001/setup/close").with(csrf())).andExpect(status().isConflict());
        service.poll("demo-001",key,false,null,"0.3.0",true,null);asAdmin();
        assertThat(service.device("demo-001").setupPending()).isTrue();
        provisioning.generateCode("demo-001");
        String close=provisioning.closeSetup("demo-001").requestId();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_pairing",Integer.class)).isZero();
        var response=service.poll("demo-001",key,false,null,"0.3.0",true,null);
        assertThat(response.configCloseId()).isEqualTo(close);assertThat(response.configRequestId()).isNull();
        assertThat(service.poll("demo-001",key,false,null,"0.3.0",false,close).configCloseId()).isNull();
        assertThat(service.device("demo-001").setupPending()).isFalse();
        assertThat(service.credentials("demo-001").deviceKey()).isEqualTo(key);
    }
}
