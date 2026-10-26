package io.rentgate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final PasswordEncoder encoder;

    public AccountService(JdbcTemplate jdbc, Clock clock, PasswordEncoder encoder) {
        this.jdbc = jdbc; this.clock = clock; this.encoder = encoder;
    }

    public record Account(long id, String username, String displayName, String role, Long providerId,
                          String providerName, boolean enabled, long createdTs) {}
    public record Actor(long id, String username, String role, Long providerId) {
        public boolean admin() { return role.equals("admin"); }
        public boolean provider() { return role.equals("provider"); }
        public boolean owner() { return role.equals("user"); }
    }
    public record Principal(long id, String username, String passwordHash, String role, Long providerId,
                            boolean enabled, long version) implements UserDetails {
        @Override public String getUsername() { return username; }
        @Override public String getPassword() { return passwordHash; }
        @Override public boolean isEnabled() { return enabled; }
        @Override public Collection<? extends GrantedAuthority> getAuthorities() {
            return List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)));
        }
    }

    public Principal principal(String username) {
        List<Principal> result = jdbc.query("""
                SELECT u.*, p.enabled AS provider_enabled FROM sys_user u
                LEFT JOIN sys_user p ON p.id=u.provider_id WHERE u.username=?
                """, (rs, row) -> new Principal(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                rs.getString("role"), rs.getObject("provider_id", Long.class), rs.getBoolean("enabled") &&
                !Boolean.FALSE.equals(rs.getObject("provider_enabled", Boolean.class)), rs.getLong("auth_version")), username);
        if (result.isEmpty()) throw new UsernameNotFoundException("账号不存在");
        return result.get(0);
    }

    public Actor actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) throw unauthorized();
        Principal account;
        try { account = principal(authentication.getName()); }
        catch (UsernameNotFoundException error) { throw unauthorized(); }
        if (!account.enabled || authentication.getPrincipal() instanceof Principal session && session.version != account.version) {
            throw unauthorized();
        }
        return new Actor(account.id, account.username, account.role, account.providerId);
    }

    static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }

    public boolean bootstrapAdmin(String password) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username='admin'", Integer.class) > 0) return false;
        jdbc.update("INSERT INTO sys_user(username,display_name,password_hash,role,created_ts) VALUES('admin','管理员',?,'admin',?)",
                passwordHash(password), clock.instant().getEpochSecond());
        return true;
    }

    public Account me() { return account(actor().id); }

    public Account account(long id) {
        var result = jdbc.query("""
                SELECT u.*,p.display_name AS provider_name FROM sys_user u
                LEFT JOIN sys_user p ON p.id=u.provider_id WHERE u.id=?
                """, (rs,row) -> new Account(rs.getLong("id"),rs.getString("username"),rs.getString("display_name"),
                rs.getString("role"),rs.getObject("provider_id",Long.class),rs.getString("provider_name"),
                rs.getBoolean("enabled"),rs.getLong("created_ts")),id);
        if (result.isEmpty()) throw Db.missing();
        return result.get(0);
    }

    public void requireAccountAccess(Actor actor, Account target) {
        if (!actor.admin() && !(actor.provider() && target.role.equals("user") && Long.valueOf(actor.id).equals(target.providerId))
                && actor.id != target.id) throw Db.missing();
    }

    public Account requireOwner(Actor actor, long ownerId, boolean requireEnabled) {
        Account owner = account(ownerId);
        requireAccountAccess(actor, owner);
        if (!owner.role.equals("user")) throw Db.bad("设备和套餐必须归属普通用户（老板）");
        if (requireEnabled && !principal(owner.username).enabled) throw Db.bad("老板或其所属服务商已停用");
        return owner;
    }

    public List<Account> list() {
        Actor actor = actor();
        String where = actor.admin() ? "" : " WHERE u.id=? OR u.provider_id=?";
        Object[] params = actor.admin() ? new Object[0] : new Object[]{actor.id,actor.id};
        return jdbc.query("SELECT u.*,p.display_name AS provider_name FROM sys_user u LEFT JOIN sys_user p ON p.id=u.provider_id"
                + where + " ORDER BY u.id",(rs,row) -> new Account(rs.getLong("id"),rs.getString("username"),rs.getString("display_name"),
                rs.getString("role"),rs.getObject("provider_id",Long.class),rs.getString("provider_name"),
                rs.getBoolean("enabled"),rs.getLong("created_ts")),params);
    }

    @Transactional
    public Account create(String username, String displayName, String role, Long providerId, String password) {
        Actor actor = actor();
        if (actor.owner() || !List.of("provider","user").contains(role)) throw Db.forbidden();
        if (actor.provider()) {
            if (!role.equals("user") || providerId != null && providerId != actor.id) throw Db.forbidden();
            providerId = actor.id;
        }
        if (role.equals("provider")) {
            if (providerId != null) throw Db.bad("服务商不能再归属其他服务商");
        } else {
            if (providerId == null) throw Db.bad("请选择上级服务商");
            Account provider = account(providerId);
            if (!provider.role.equals("provider") || !provider.enabled) throw Db.bad("上级服务商无效或已停用");
        }
        long id;
        try {
            id = Db.insert(jdbc,"INSERT INTO sys_user(username,display_name,password_hash,role,provider_id,created_ts) VALUES(?,?,?,?,?,?)",
                    username,displayName.strip(),passwordHash(password),role,providerId,clock.instant().getEpochSecond());
        } catch (DuplicateKeyException error) { throw Db.conflict("登录账号已存在"); }
        jdbc.update("INSERT INTO merchant(user_id,provider_id,name,mch_type) VALUES(?,?,?,?)",id,providerId,displayName.strip(),role);
        return account(id);
    }

    @Transactional
    public Account update(long id, String displayName, boolean enabled) {
        Actor actor = actor();
        if (actor.owner()) throw Db.forbidden();
        Account target = account(id);
        requireAccountAccess(actor,target);
        if (target.role.equals("admin") || id == actor.id) throw Db.bad("当前账号请在个人设置中修改，不能停用管理员");
        jdbc.update("UPDATE sys_user SET display_name=?,enabled=?,auth_version=auth_version+1 WHERE id=?",displayName.strip(),enabled,id);
        // Keep the initial merchant label in sync; separately customized shop names stay intact.
        jdbc.update("UPDATE merchant SET name=? WHERE user_id=? AND name=?",displayName.strip(),id,target.displayName);
        return account(id);
    }

    @Transactional
    public void resetPassword(long id, String password) {
        Actor actor = actor();
        if (actor.owner() || actor.id == id) throw Db.forbidden();
        requireAccountAccess(actor,account(id));
        jdbc.update("UPDATE sys_user SET password_hash=?,auth_version=auth_version+1 WHERE id=?",passwordHash(password),id);
    }

    @Transactional
    public void changeOwnPassword(String current, String password) {
        Actor actor = actor();
        if (!encoder.matches(current,principal(actor.username).passwordHash)) throw Db.bad("当前密码不正确");
        jdbc.update("UPDATE sys_user SET password_hash=?,auth_version=auth_version+1 WHERE id=?",passwordHash(password),actor.id);
    }

    private String passwordHash(String password) {
        if (password == null || password.length() < 8 || password.length() > 64 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw Db.bad("密码需为 8–64 个字符，UTF-8 编码不超过 72 字节");
        }
        return encoder.encode(password);
    }
}
