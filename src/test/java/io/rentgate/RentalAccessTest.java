package io.rentgate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:rentals;DB_CLOSE_DELAY=-1","RENT_GATE_ADMIN_PASSWORD=test-only-password"})
@AutoConfigureMockMvc @WithMockUser(username="admin",roles="ADMIN")
class RentalAccessTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountService accounts;
    @Autowired DeviceService devices;
    @Autowired ObjectMapper json;
    @Autowired ManagementService management;
    @Autowired TestClock clock;
    @Autowired DeviceProvisioningService provisioning;
    long providerA,providerB,ownerA,ownerB;
    String keyA;

    static class TestClock extends Clock {
        volatile long seconds=1700000000;
        @Override public Instant instant() { return Instant.ofEpochSecond(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
    @TestConfiguration static class Time {
        @Bean @Primary TestClock rentalClock() { return new TestClock(); }
    }

    @BeforeEach void fixture() {
        jdbc.update("DELETE FROM rental_order"); jdbc.update("DELETE FROM device"); jdbc.update("DELETE FROM price_plan");
        jdbc.update("DELETE FROM merchant"); jdbc.update("DELETE FROM device_type WHERE id<>1");
        jdbc.update("DELETE FROM sys_user WHERE role='user'"); jdbc.update("DELETE FROM sys_user WHERE role='provider'");
        jdbc.update("UPDATE device_type SET trial_minutes=15,cooldown_minutes=120,enabled=TRUE WHERE id=1");
        clock.seconds=1700000000;
        jdbc.update("UPDATE system_settings SET site_name='Rent Gate',guest_notice='测试',online_timeout_seconds=12");
        providerA=accounts.create("provider_a","服务商甲","provider",null,"Account123").id();
        providerB=accounts.create("provider_b","服务商乙","provider",null,"Account123").id();
        ownerA=accounts.create("owner_a","老板甲","user",providerA,"Account123").id();
        ownerB=accounts.create("owner_b","老板乙","user",providerB,"Account123").id();
        keyA=devices.create("device-a","一号桌").deviceKey();
        devices.create("device-b","二号桌");
        jdbc.update("UPDATE device SET user_id=?,merchant_id=(SELECT id FROM merchant WHERE user_id=?),public_token='public-a',last_seen_ts=? WHERE device_id='device-a'",ownerA,ownerA,clock.seconds);
        jdbc.update("UPDATE device SET user_id=?,merchant_id=(SELECT id FROM merchant WHERE user_id=?),public_token='public-b',last_seen_ts=? WHERE device_id='device-b'",ownerB,ownerB,clock.seconds);
    }

    @Test void guestTrialStartsOnceAndCooldownBeginsAfterTrialWithoutCreatingOrders() throws Exception {
        mvc.perform(get("/api/public/devices/public-a").with(anonymous()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trial_available").value(true));
        mvc.perform(post("/api/public/devices/public-a/trial").with(anonymous()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("trial"))
                .andExpect(jsonPath("$.expire_ts").value(1700000900))
                .andExpect(jsonPath("$.cooldown_until").value(1700008100));
        mvc.perform(post("/api/public/devices/public-a/trial").with(anonymous()).with(csrf()))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rental_order",Integer.class)).isZero();
    }

    @Test void pairingWindowBlocksGuestTrialAndManualReleaseUntilCompletedOrExpired() throws Exception {
        provisioning.beginPairing("device-a");
        mvc.perform(get("/api/public/devices/public-a").with(anonymous())).andExpect(jsonPath("$.trial_available").value(false));
        mvc.perform(post("/api/public/devices/public-a/trial").with(anonymous()).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/admin/release").with(user("owner_a").roles("USER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_id\":\"device-a\",\"duration_min\":1}")).andExpect(status().isConflict());
        clock.seconds+=300;jdbc.update("UPDATE device SET last_seen_ts=? WHERE device_id='device-a'",clock.seconds);
        assertThat(devices.guest("public-a").trialAvailable()).isTrue();
    }

    @Test void adminCanMoveTypeBetweenCommonAndCompatibleProviderButCannotBreakExistingDevices() throws Exception {
        long type=management.saveType(null,"通用可调整",null,1,0,true).id();
        assertThat(management.saveType(type,"服务商类型",providerA,1,0,true).providerId()).isEqualTo(providerA);
        devices.update("device-a","一号桌",ownerA,type,true);
        mvc.perform(put("/admin/device-types/"+type).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"错误归属\",\"provider_id\":"+providerB+",\"trial_minutes\":1,\"cooldown_minutes\":0,\"enabled\":true}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/admin/device-types/"+type).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"平台通用\",\"provider_id\":null,\"trial_minutes\":1,\"cooldown_minutes\":0,\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.provider_id").isEmpty());
    }

    @Test void deleteTypeRejectsAttachedDevicesAndRemovesUnusedTypeWithItsPlans() throws Exception {
        mvc.perform(delete("/admin/device-types/1").with(csrf())).andExpect(status().isConflict());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin","",java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        long type=management.saveType(null,"待删除类型",providerA,1,0,true).id();
        management.savePlan(null,ownerA,type,"随类型删除的套餐",1,new java.math.BigDecimal("1.00"),true);
        mvc.perform(delete("/admin/device-types/"+type).with(user("provider_b").roles("PROVIDER")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/admin/device-types/"+type).with(user("owner_a").roles("USER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(delete("/admin/device-types/"+type).with(user("provider_a").roles("PROVIDER")).with(csrf())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_type WHERE id=?",Integer.class,type)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_plan WHERE device_type_id=?",Integer.class,type)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device WHERE device_type_id=1",Integer.class)).isEqualTo(2);
    }

    @Test void existingQrReflectsCurrentOwnerAndDefaultMerchantNameWithoutReplacingCustomShopNames() {
        // Historical account renames left a copied merchant label behind before name tracking existed.
        jdbc.update("UPDATE merchant SET name='历史老板名' WHERE user_id=?",ownerA);
        assertThat(devices.guest("public-a").merchantName()).isEqualTo("老板甲");
        assertThat(management.merchants().stream().filter(m->m.userId()==ownerA).findFirst().orElseThrow().name()).isEqualTo("老板甲");
        accounts.update(ownerA,"老板甲改名",true);
        var renamed=devices.guest("public-a");
        assertThat(renamed.ownerName()).isEqualTo("老板甲改名");
        assertThat(renamed.merchantName()).isEqualTo("老板甲改名");
        long merchant=jdbc.queryForObject("SELECT id FROM merchant WHERE user_id=?",Long.class,ownerA);
        management.saveMerchant(merchant,"自定义门店","","","");
        accounts.update(ownerA,"老板甲再次改名",true);
        var custom=devices.guest("public-a");
        assertThat(custom.ownerName()).isEqualTo("老板甲再次改名");
        assertThat(custom.merchantName()).isEqualTo("自定义门店");
        devices.update("device-a","一号桌",ownerB,1,true);
        var reassigned=devices.guest("public-a");
        assertThat(reassigned.ownerName()).isEqualTo("老板乙");
        assertThat(reassigned.merchantName()).isEqualTo("老板乙");
    }

    @Test void ownerAndProviderOnlySeeTheirOwnDevicesAndCannotControlOtherDevices() throws Exception {
        for (String role:new String[]{"owner_a","provider_a"}) {
            mvc.perform(get("/admin/devices/device-b/credentials").with(user(role).roles(role.startsWith("owner")?"USER":"PROVIDER"))).andExpect(status().isNotFound());
            mvc.perform(get("/admin/devices/device-a/credentials").with(user(role).roles(role.startsWith("owner")?"USER":"PROVIDER"))).andExpect(status().isOk()).andExpect(jsonPath("$.device_key").value(keyA));
            for(String endpoint:new String[]{"setup","setup/close","pairing","pairing-code","pairing/confirm"}) mvc.perform(post("/admin/devices/device-b/"+endpoint).with(user(role).roles(role.startsWith("owner")?"USER":"PROVIDER")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}")).andExpect(status().isNotFound());
            mvc.perform(get("/admin/devices").with(user(role).roles(role.startsWith("owner")?"USER":"PROVIDER")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].device_id").value("device-a"));
            for (String endpoint:new String[]{"release","stop","rotate-key"}) {
                mvc.perform(post("/admin/"+endpoint).with(user(role).roles(role.startsWith("owner")?"USER":"PROVIDER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"device_id\":\"device-b\",\"duration_min\":1}"))
                        .andExpect(status().isNotFound());
            }
            mvc.perform(get("/admin/devices/device-b/qr.png").with(user(role).roles(role.startsWith("owner")?"USER":"PROVIDER")))
                    .andExpect(status().isNotFound());
        }
    }

    @Test void publicPageNeverLeaksCredentialsAndQrContainsGuestUrlOnly() throws Exception {
        mvc.perform(get("/api/public/devices/public-a").with(anonymous())).andExpect(status().isOk())
                .andExpect(jsonPath("$.device_key").doesNotExist()).andExpect(jsonPath("$.key_hash").doesNotExist())
                .andExpect(jsonPath("$.user_id").doesNotExist()).andExpect(jsonPath("$.payment_enabled").value(false));
        byte[] png=mvc.perform(get("/admin/devices/device-a/qr.png")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var buffered=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        var luminance=new com.google.zxing.client.j2se.BufferedImageLuminanceSource(buffered);
        var decoded=new com.google.zxing.MultiFormatReader().decode(new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(luminance)));
        assertThat(decoded.getText()).isEqualTo("http://localhost/r/public-a").doesNotContain(keyA);
    }

    @Test void cooldownExpiresAtExactBoundaryAndEarlyStopDoesNotBypassIt() throws Exception {
        devices.trial("public-a"); devices.stop("device-a");
        mvc.perform(post("/api/public/devices/public-a/trial").with(anonymous()).with(csrf())).andExpect(status().isConflict());
        clock.seconds=1700008099; jdbc.update("UPDATE device SET last_seen_ts=? WHERE device_id='device-a'",clock.seconds);
        assertThat(devices.guest("public-a").trialAvailable()).isFalse();
        clock.seconds=1700008100; jdbc.update("UPDATE device SET last_seen_ts=? WHERE device_id='device-a'",clock.seconds);
        assertThat(devices.guest("public-a").trialAvailable()).isTrue();
        assertThat(devices.trial("public-a").status()).isEqualTo("trial");
    }

    @Test void trialEndsIntoCooldownAndManualReleaseStillWorks() {
        devices.trial("public-a"); clock.seconds+=900;
        assertThat(devices.poll("device-a",keyA,false).action()).isEqualTo("off");
        assertThat(devices.guest("public-a").status()).isEqualTo("cooldown");
        devices.release("device-a",30);
        assertThat(devices.guest("public-a").status()).isEqualTo("running");
        assertThat(devices.guest("public-a").cooldownUntil()).isEqualTo(1700008100);
    }

    @Test void concurrentGuestTrialsGrantOnlyOnce() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var tasks=new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
            for(int i=0;i<24;i++)tasks.add(()-> {
                try { devices.trial("public-a"); return true; }
                catch(org.springframework.web.server.ResponseStatusException error) {
                    assertThat(error.getStatusCode().value()).isEqualTo(409); return false;
                }
            });
            int success=0; for(var result:pool.invokeAll(tasks))if(result.get())success++;
            assertThat(success).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rental_order",Integer.class)).isZero();
    }

    @Test void guestTrialNeedsCsrfOnlineDeviceAndActiveMerchant() throws Exception {
        mvc.perform(post("/api/public/devices/public-a/trial").with(anonymous())).andExpect(status().isForbidden());
        clock.seconds+=13;
        mvc.perform(post("/api/public/devices/public-a/trial").with(anonymous()).with(csrf())).andExpect(status().isConflict());
        jdbc.update("UPDATE device SET last_seen_ts=? WHERE device_id='device-a'",clock.seconds);
        jdbc.update("UPDATE sys_user SET enabled=FALSE WHERE id=?",providerA);
        mvc.perform(post("/api/public/devices/public-a/trial").with(anonymous()).with(csrf())).andExpect(status().isConflict());
    }

    @Test void disablingDeviceSendsOffAndRemovesPublicAccess() throws Exception {
        devices.trial("public-a"); devices.update("device-a","一号桌",ownerA,1,false);
        assertThat(devices.poll("device-a",keyA,true).action()).isEqualTo("off");
        mvc.perform(get("/api/public/devices/public-a").with(anonymous())).andExpect(status().isNotFound());
    }

    @Test void runningDeviceCannotBeReassigned() throws Exception {
        devices.release("device-a",30);
        mvc.perform(put("/admin/devices/device-a").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"moved\",\"user_id\":"+ownerB+",\"device_type_id\":1,\"enabled\":true}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/admin/devices").with(user("owner_a").roles("USER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_id\":\"forged\",\"name\":\"x\",\"user_id\":"+ownerB+"}"))
                .andExpect(status().isForbidden());
    }

    @Test void orderQueriesAndRevenueUseOwnerScopeAndOnlyPaidOrders() throws Exception {
        jdbc.update("INSERT INTO rental_order(order_no,device_id,user_id,duration_min,amount,status,created_ts) VALUES('paid-a','device-a',?,60,30.30,'paid',?)",ownerA,clock.seconds);
        jdbc.update("INSERT INTO rental_order(order_no,device_id,user_id,duration_min,amount,status,created_ts) VALUES('pending-a','device-a',?,60,99.00,'pending',?)",ownerA,clock.seconds);
        jdbc.update("INSERT INTO rental_order(order_no,device_id,user_id,duration_min,amount,status,created_ts) VALUES('refund-a','device-a',?,60,5.00,'refunded',?)",ownerA,clock.seconds);
        jdbc.update("INSERT INTO rental_order(order_no,device_id,user_id,duration_min,amount,status,created_ts) VALUES('paid-b','device-b',?,60,50.00,'paid',?)",ownerB,clock.seconds);
        mvc.perform(get("/admin/orders").with(user("owner_a").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3));
        mvc.perform(get("/admin/orders").param("device_id","device-b").with(user("owner_a").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/admin/statistics").with(user("provider_a").roles("PROVIDER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total_devices").value(1))
                .andExpect(jsonPath("$.total_revenue").value(30.30)).andExpect(jsonPath("$.paid_orders").value(1));
    }

    @Test void tenantTypesPlansAndMerchantsCannotBeReadOrChangedAcrossProviders() throws Exception {
        long typeA=management.saveType(null,"甲类型",providerA,1,2,true).id();
        long typeB=management.saveType(null,"乙类型",providerB,1,2,true).id();
        long planA=management.savePlan(null,ownerA,typeA,"甲套餐",60,new java.math.BigDecimal("5.50"),true).id();
        long planB=management.savePlan(null,ownerB,typeB,"乙套餐",60,new java.math.BigDecimal("9.00"),true).id();
        long merchantB=jdbc.queryForObject("SELECT id FROM merchant WHERE user_id=?",Long.class,ownerB);
        mvc.perform(get("/admin/device-types").with(user("owner_a").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/admin/plans").with(user("provider_a").roles("PROVIDER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(planA));
        mvc.perform(put("/admin/plans/"+planB).with(user("owner_a").roles("USER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"user_id\":"+ownerA+",\"device_type_id\":1,\"name\":\"spoof\",\"duration_min\":30,\"price\":1,\"enabled\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/admin/plans").with(user("owner_a").roles("USER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_type_id\":"+typeB+",\"name\":\"spoof\",\"duration_min\":30,\"price\":1,\"enabled\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/admin/merchants/"+merchantB).with(user("provider_a").roles("PROVIDER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"spoof\",\"wechat_mch_id\":\"\",\"wechat_sub_mch_id\":\"123\",\"alipay_app_id\":\"\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/admin/device-types/1").with(user("provider_a").roles("PROVIDER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"global\",\"trial_minutes\":1,\"cooldown_minutes\":1,\"enabled\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/admin/device-types").with(user("owner_a").roles("USER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"spoof\",\"trial_minutes\":1,\"cooldown_minutes\":1,\"enabled\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test void guestOnlySeesItsOwnActivePlansAndDisabledTypesStopNewTrials() throws Exception {
        management.savePlan(null,ownerA,1,"可见套餐",60,new java.math.BigDecimal("5.50"),true);
        management.savePlan(null,ownerA,1,"已停套餐",60,java.math.BigDecimal.ONE,false);
        management.savePlan(null,ownerB,1,"他人套餐",60,java.math.BigDecimal.ONE,true);
        mvc.perform(get("/api/public/devices/public-a").with(anonymous()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.plans.length()").value(1))
                .andExpect(jsonPath("$.plans[0].name").value("可见套餐"));
        org.springframework.security.core.context.SecurityContextHolder.setContext(
                org.springframework.security.test.context.TestSecurityContextHolder.getContext());
        management.saveType(1L,"洗牌机",null,15,120,false);
        mvc.perform(post("/api/public/devices/public-a/trial").with(anonymous()).with(csrf())).andExpect(status().isConflict());
        org.springframework.security.core.context.SecurityContextHolder.setContext(
                org.springframework.security.test.context.TestSecurityContextHolder.getContext());
        management.savePlan(management.plans().get(0).id(),ownerA,1,"可见套餐",60,new java.math.BigDecimal("5.50"),false);
        jdbc.update("UPDATE sys_user SET enabled=FALSE WHERE id=?",providerA);
        assertThat(devices.update("device-a","一号桌",ownerA,1,false).enabled()).isFalse();
    }

    @Test void settingsAndValidationRejectUnauthorizedOrInvalidWrites() throws Exception {
        mvc.perform(put("/admin/settings").with(user("owner_a").roles("USER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"site_name\":\"spoof\",\"guest_notice\":\"\",\"online_timeout_seconds\":12}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/plans").with(user("owner_a").roles("USER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_type_id\":1,\"name\":\"bad\",\"duration_min\":30,\"price\":1.001,\"enabled\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/orders").param("page_size","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/orders").param("from","2026-10-22").param("to","2026-10-21")).andExpect(status().isBadRequest());
        mvc.perform(put("/admin/settings").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"site_name\":\"新站点\",\"guest_notice\":\"欢迎\",\"online_timeout_seconds\":30}"))
                .andExpect(status().isOk());
        clock.seconds+=20;
        mvc.perform(get("/api/public/devices/public-a").with(anonymous()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.online").value(true)).andExpect(jsonPath("$.site_name").value("新站点"));
    }

    @Test void revenueDatesUseBeijingMidnightAndOrdersArePaginatedAfterScoping() throws Exception {
        clock.seconds=java.time.Instant.parse("2026-10-22T00:00:00Z").getEpochSecond();
        long midnight=java.time.Instant.parse("2026-10-21T16:00:00Z").getEpochSecond();
        jdbc.update("INSERT INTO rental_order(order_no,device_id,user_id,duration_min,amount,status,created_ts) VALUES('yesterday','device-a',?,60,3.00,'paid',?)",ownerA,midnight-1);
        jdbc.update("INSERT INTO rental_order(order_no,device_id,user_id,duration_min,amount,status,created_ts) VALUES('today','device-a',?,60,7.00,'paid',?)",ownerA,midnight);
        mvc.perform(get("/admin/statistics").with(user("owner_a").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.today_revenue").value(7.0))
                .andExpect(jsonPath("$.daily_revenue[5].revenue").value(3.0)).andExpect(jsonPath("$.daily_revenue[6].revenue").value(7.0));
        mvc.perform(get("/admin/orders").param("page_size","1").param("page","2").with(user("owner_a").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items[0].order_no").value("yesterday"));
        mvc.perform(get("/admin/orders").param("from","2026-10-22").param("to","2026-10-22").with(user("owner_a").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
    }
}
