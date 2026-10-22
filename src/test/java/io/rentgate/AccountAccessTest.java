package io.rentgate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:accounts;DB_CLOSE_DELAY=-1", "RENT_GATE_ADMIN_PASSWORD=test-only-password"})
@AutoConfigureMockMvc
@WithMockUser(username="admin",roles="ADMIN")
class AccountAccessTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach void clear() {
        jdbc.update("DELETE FROM rental_order"); jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM price_plan"); jdbc.update("DELETE FROM merchant");
        jdbc.update("DELETE FROM device_type WHERE id<>1");
        jdbc.update("DELETE FROM sys_user WHERE role='user'");
        jdbc.update("DELETE FROM sys_user WHERE role='provider'");
    }

    private JsonNode create(String username,String role,Long parent) throws Exception {
        var input=new java.util.HashMap<String,Object>(java.util.Map.of("username",username,"display_name",username,"role",role,"password","Account123"));
        if (parent!=null) input.put("provider_id",parent);
        String body=json.writeValueAsString(input);
        return json.readTree(mvc.perform(post("/admin/accounts").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private MockHttpSession login(String username,String password) throws Exception {
        return (MockHttpSession)mvc.perform(post("/login").with(anonymous()).with(csrf()).param("username",username).param("password",password))
                .andExpect(redirectedUrl("/")).andReturn().getRequest().getSession(false);
    }

    @Test @WithMockUser(username="admin",roles="ADMIN")
    void adminCanCreateProviderWithoutExposingPasswordHash() throws Exception {
        mvc.perform(post("/admin/accounts").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"provider_slice\",\"display_name\":\"服务商甲\",\"role\":\"provider\",\"password\":\"Provider123\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("provider"))
                .andExpect(jsonPath("$.password_hash").doesNotExist());
    }

    @Test void providerCannotEscalateOrReadOtherProviderAccounts() throws Exception {
        long a=create("provider_a","provider",null).get("id").asLong();
        long b=create("provider_b","provider",null).get("id").asLong();
        long owner=create("owner_b","user",b).get("id").asLong();
        mvc.perform(post("/admin/accounts").with(user("provider_a").roles("PROVIDER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"evil_admin\",\"display_name\":\"x\",\"role\":\"admin\",\"password\":\"Account123\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/accounts").with(user("provider_a").roles("PROVIDER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"spoof_owner\",\"display_name\":\"x\",\"role\":\"user\",\"provider_id\":"+b+",\"password\":\"Account123\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/accounts").with(user("provider_a").roles("PROVIDER")))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(a));
        mvc.perform(put("/admin/accounts/"+owner).with(user("provider_a").roles("PROVIDER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"display_name\":\"stolen\",\"enabled\":false}"))
                .andExpect(status().isNotFound());
    }

    @Test void ownerCannotManageAccountsAndCannotChangePasswordWithWrongCurrentPassword() throws Exception {
        long provider=create("provider_a","provider",null).get("id").asLong();
        create("owner_a","user",provider);
        mvc.perform(get("/admin/accounts").with(user("owner_a").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/me/password").with(user("owner_a").roles("USER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"current_password\":\"wrong\",\"password\":\"NewPass123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void resettingPasswordInvalidatesExistingSessionAndAcceptsNewPassword() throws Exception {
        long provider=create("provider_a","provider",null).get("id").asLong();
        MockHttpSession session=login("provider_a","Account123");
        mvc.perform(post("/admin/accounts/"+provider+"/password").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Changed123\"}"))
                .andExpect(status().isOk());
        var context=(org.springframework.security.core.context.SecurityContext)session.getAttribute("SPRING_SECURITY_CONTEXT");
        mvc.perform(get("/admin/me").session(session).with(authentication(context.getAuthentication()))).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
        login("provider_a","Changed123");
        mvc.perform(post("/login").with(anonymous()).with(csrf()).param("username","provider_a").param("password","Account123"))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test void disablingProviderAlsoBlocksOwnerLogin() throws Exception {
        long provider=create("provider_a","provider",null).get("id").asLong();
        create("owner_a","user",provider);
        mvc.perform(put("/admin/accounts/"+provider).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"display_name\":\"disabled\",\"enabled\":false}")).andExpect(status().isOk());
        mvc.perform(post("/login").with(anonymous()).with(csrf()).param("username","owner_a").param("password","Account123"))
                .andExpect(redirectedUrl("/login?error"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant",Integer.class)).isEqualTo(2);
    }
}
