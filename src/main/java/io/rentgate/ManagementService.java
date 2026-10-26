package io.rentgate;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManagementService {
    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    public ManagementService(JdbcTemplate jdbc,AccountService accounts) { this.jdbc=jdbc; this.accounts=accounts; }
    public record DeviceType(long id,String name,Long providerId,String providerName,int trialMinutes,int cooldownMinutes,boolean enabled) {}
    public record Merchant(long id,long userId,Long providerId,String name,String ownerName,String mchType,String wechatMchId,String wechatSubMchId,String alipayAppId) {}
    public record Plan(long id,long userId,String ownerName,long deviceTypeId,String typeName,String name,int durationMin,BigDecimal price,boolean enabled) {}
    public record PublicPlan(long id,String name,int durationMin,BigDecimal price) {}
    public record Settings(String siteName,String guestNotice,int onlineTimeoutSeconds) {}
    public record Scope(String sql,Object[] params) {}

    static Scope ownerScope(AccountService.Actor actor,String column) {
        if (actor.admin()) return new Scope("1=1",new Object[0]);
        if (actor.provider()) return new Scope(column+" IN (SELECT id FROM sys_user WHERE provider_id=?)",new Object[]{actor.id()});
        return new Scope(column+"=?",new Object[]{actor.id()});
    }

    public List<DeviceType> types() {
        var actor=accounts.actor(); Long provider=actor.provider()?Long.valueOf(actor.id()):actor.providerId();
        return jdbc.query("SELECT t.*,p.display_name AS provider_name FROM device_type t LEFT JOIN sys_user p ON p.id=t.provider_id"
                +(actor.admin()?"":" WHERE t.provider_id IS NULL OR t.provider_id=?")+" ORDER BY t.id",(rs,row)->new DeviceType(rs.getLong("id"),rs.getString("name"),
                rs.getObject("provider_id",Long.class),rs.getString("provider_name"),rs.getInt("trial_minutes"),rs.getInt("cooldown_minutes"),rs.getBoolean("enabled")),
                actor.admin()?new Object[0]:new Object[]{provider});
    }

    public DeviceType type(long id) {
        var found=jdbc.query("SELECT t.*,p.display_name AS provider_name FROM device_type t LEFT JOIN sys_user p ON p.id=t.provider_id WHERE t.id=?",
                (rs,row)->new DeviceType(rs.getLong("id"),rs.getString("name"),rs.getObject("provider_id",Long.class),rs.getString("provider_name"),
                rs.getInt("trial_minutes"),rs.getInt("cooldown_minutes"),rs.getBoolean("enabled")),id);
        if (found.isEmpty()) throw Db.missing(); return found.get(0);
    }

    public DeviceType requireTypeForOwner(long id,AccountService.Account owner,boolean enabled) {
        lockType(id);
        DeviceType type=type(id);
        if (type.providerId!=null && (owner==null || !type.providerId.equals(owner.providerId()))) throw Db.bad("该设备类型不属于老板所在的服务商");
        if (enabled && !type.enabled) throw Db.bad("设备类型已停用");
        return type;
    }

    @Transactional
    public DeviceType saveType(Long id,String name,Long providerId,int trialMinutes,int cooldownMinutes,boolean enabled) {
        var actor=accounts.actor(); if (actor.owner()) throw Db.forbidden();
        if (actor.provider()) {
            if (providerId!=null && providerId!=actor.id()) throw Db.forbidden(); providerId=actor.id();
        }
        if (providerId!=null) {
            var provider=accounts.account(providerId);
            if (!provider.role().equals("provider") || enabled && !provider.enabled()) throw Db.bad("服务商无效或已停用");
        }
        if (id==null) id=Db.insert(jdbc,"INSERT INTO device_type(name,provider_id,trial_minutes,cooldown_minutes,enabled) VALUES(?,?,?,?,?)",name.strip(),providerId,trialMinutes,cooldownMinutes,enabled);
        else {
            lockType(id);
            DeviceType old=type(id);
            if (!actor.admin() && !Long.valueOf(actor.id()).equals(old.providerId)) throw Db.missing();
            if (!java.util.Objects.equals(providerId,old.providerId) && providerId!=null) {
                int incompatible=jdbc.queryForObject("SELECT COUNT(*) FROM device d LEFT JOIN sys_user u ON u.id=d.user_id WHERE d.device_type_id=? AND (u.provider_id IS NULL OR u.provider_id<>?)",Integer.class,id,providerId);
                int plans=jdbc.queryForObject("SELECT COUNT(*) FROM price_plan p JOIN sys_user u ON u.id=p.user_id WHERE p.device_type_id=? AND u.provider_id<>?",Integer.class,id,providerId);
                if (incompatible>0 || plans>0) throw Db.conflict("该类型存在不属于目标服务商的设备或套餐，请先调整后再修改归属");
            }
            jdbc.update("UPDATE device_type SET name=?,provider_id=?,trial_minutes=?,cooldown_minutes=?,enabled=? WHERE id=?",name.strip(),providerId,trialMinutes,cooldownMinutes,enabled,id);
        }
        return type(id);
    }

    private void lockType(long id) {
        if (jdbc.queryForList("SELECT id FROM device_type WHERE id=? FOR UPDATE",Long.class,id).isEmpty()) throw Db.missing();
    }
    @Transactional public void deleteType(long id) {
        var actor=accounts.actor();if (actor.owner()) throw Db.forbidden();
        lockType(id);var type=type(id);
        if (!actor.admin() && !Long.valueOf(actor.id()).equals(type.providerId)) throw Db.missing();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM device WHERE device_type_id=?",Integer.class,id)>0) throw Db.conflict("该类型仍有设备使用，不能删除；请先调整设备类型");
        jdbc.update("DELETE FROM price_plan WHERE device_type_id=?",id);
        jdbc.update("DELETE FROM device_type WHERE id=?",id);
    }

    public List<Merchant> merchants() {
        var actor=accounts.actor();
        String scope=actor.admin()?"1=1":actor.provider()?"(m.user_id=? OR m.provider_id=?)":"m.user_id=?";
        Object[] params=actor.admin()?new Object[0]:actor.provider()?new Object[]{actor.id(),actor.id()}:new Object[]{actor.id()};
        return jdbc.query("SELECT m.*,u.display_name AS owner_name FROM merchant m JOIN sys_user u ON u.id=m.user_id WHERE "+scope+" ORDER BY m.id",
                (rs,row)->new Merchant(rs.getLong("id"),rs.getLong("user_id"),rs.getObject("provider_id",Long.class),rs.getString("name"),rs.getString("owner_name"),
                rs.getString("mch_type"),rs.getString("wechat_mch_id"),rs.getString("wechat_sub_mch_id"),rs.getString("alipay_app_id")),params);
    }

    @Transactional public Merchant saveMerchant(long id,String name,String mchId,String subMchId,String alipayId) {
        Merchant merchant=merchants().stream().filter(m->m.id==id).findFirst().orElseThrow(Db::missing);
        if (merchant.mchType.equals("user") && !mchId.isEmpty()) throw Db.bad("普通用户仅填写小微商户号，上级商户号由服务商管理");
        if (merchant.mchType.equals("provider") && !subMchId.isEmpty()) throw Db.bad("服务商应填写普通商户号，不填写小微商户号");
        jdbc.update("UPDATE merchant SET name=?,wechat_mch_id=?,wechat_sub_mch_id=?,alipay_app_id=? WHERE id=?",name.strip(),mchId,subMchId,alipayId,id);
        return merchants().stream().filter(m->m.id==id).findFirst().orElseThrow(Db::missing);
    }

    public List<Plan> plans() {
        Scope scope=ownerScope(accounts.actor(),"p.user_id");
        return jdbc.query("SELECT p.*,u.display_name AS owner_name,t.name AS type_name FROM price_plan p JOIN sys_user u ON u.id=p.user_id JOIN device_type t ON t.id=p.device_type_id WHERE "
                +scope.sql+" ORDER BY p.id",(rs,row)->new Plan(rs.getLong("id"),rs.getLong("user_id"),rs.getString("owner_name"),rs.getLong("device_type_id"),
                rs.getString("type_name"),rs.getString("name"),rs.getInt("duration_min"),rs.getBigDecimal("price"),rs.getBoolean("enabled")),scope.params);
    }

    public List<PublicPlan> publicPlans(long ownerId,long typeId) {
        return jdbc.query("SELECT id,name,duration_min,price FROM price_plan WHERE user_id=? AND device_type_id=? AND enabled=TRUE ORDER BY duration_min,price,id",
                (rs,row)->new PublicPlan(rs.getLong("id"),rs.getString("name"),rs.getInt("duration_min"),rs.getBigDecimal("price")),ownerId,typeId);
    }

    @Transactional public Plan savePlan(Long id,Long ownerId,long typeId,String name,int duration,BigDecimal price,boolean enabled) {
        var actor=accounts.actor();
        if (actor.owner()) { if (ownerId!=null && ownerId!=actor.id()) throw Db.forbidden(); ownerId=actor.id(); }
        if (ownerId==null) throw Db.bad("请选择套餐所属老板");
        var owner=accounts.requireOwner(actor,ownerId,enabled); requireTypeForOwner(typeId,owner,enabled);
        if (id==null) id=Db.insert(jdbc,"INSERT INTO price_plan(user_id,device_type_id,name,duration_min,price,enabled) VALUES(?,?,?,?,?,?)",ownerId,typeId,name.strip(),duration,price,enabled);
        else {
            final long planId=id;
            Plan old=plans().stream().filter(p->p.id==planId).findFirst().orElseThrow(Db::missing);
            if (old.userId!=ownerId) throw Db.bad("套餐归属不可变更，请新建套餐");
            jdbc.update("UPDATE price_plan SET device_type_id=?,name=?,duration_min=?,price=?,enabled=? WHERE id=?",typeId,name.strip(),duration,price,enabled,id);
        }
        final long planId=id;
        return plans().stream().filter(p->p.id==planId).findFirst().orElseThrow(Db::missing);
    }

    public Settings settings() {
        return jdbc.queryForObject("SELECT * FROM system_settings WHERE singleton_id=1",(rs,row)->new Settings(rs.getString("site_name"),rs.getString("guest_notice"),rs.getInt("online_timeout_seconds")));
    }

    @Transactional public Settings saveSettings(String siteName,String notice,int timeout) {
        if (!accounts.actor().admin()) throw Db.forbidden();
        jdbc.update("UPDATE system_settings SET site_name=?,guest_notice=?,online_timeout_seconds=? WHERE singleton_id=1",siteName.strip(),notice.strip(),timeout);
        return settings();
    }
}
