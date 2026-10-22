package io.rentgate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeviceService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public DeviceService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 1000)
    public void expireLeases() {
        jdbc.update("UPDATE device SET status='off' WHERE status='on' AND expire_ts<=?", clock.instant().getEpochSecond());
    }

    public record Device(String deviceId, String name, String status, long expireTs,
                         Long lastSeenTs, Boolean relayOn, boolean online, long remainingSeconds) {}
    public record Credentials(String deviceId, String deviceKey) {}
    public record Poll(String action, long expireTs, long serverTs, long remainingSeconds) {}
    private record Stored(String id, String name, String hash, long expiry, Long seen, Boolean relay) {}

    private Stored read(String id, boolean lock) {
        List<Stored> found = jdbc.query("SELECT * FROM device WHERE device_id = ?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Stored(rs.getString("device_id"), rs.getString("name"), rs.getString("key_hash"),
                        rs.getLong("expire_ts"), rs.getObject("last_seen_ts", Long.class),
                        rs.getObject("relay_on", Boolean.class)), id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在");
        return found.get(0);
    }

    private Device view(Stored d, long now) {
        return new Device(d.id, d.name, d.expiry > now ? "on" : "off", d.expiry, d.seen, d.relay,
                d.seen != null && now - d.seen <= 12, Math.max(0, d.expiry - now));
    }

    public List<Device> list() {
        long now = clock.instant().getEpochSecond();
        return jdbc.query("SELECT * FROM device ORDER BY created_ts DESC, device_id", (rs, row) ->
                view(new Stored(rs.getString("device_id"), rs.getString("name"), "", rs.getLong("expire_ts"),
                        rs.getObject("last_seen_ts", Long.class), rs.getObject("relay_on", Boolean.class)), now));
    }

    @Transactional
    public Credentials create(String id, String name) {
        String key = newKey();
        try {
            jdbc.update("INSERT INTO device(device_id,name,key_hash,created_ts) VALUES(?,?,?,?)",
                    id, name.strip(), hash(key), clock.instant().getEpochSecond());
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备编号已存在");
        }
        return new Credentials(id, key);
    }

    @Transactional
    public Device release(String id, int minutes) {
        read(id, true);
        long now = clock.instant().getEpochSecond();
        // 重复放行从当前时间重新计时，不在旧时长上叠加。
        jdbc.update("UPDATE device SET status='on', expire_ts=? WHERE device_id=?", now + minutes * 60L, id);
        return view(read(id, false), now);
    }

    @Transactional
    public Device stop(String id) {
        read(id, true);
        jdbc.update("UPDATE device SET status='off', expire_ts=0 WHERE device_id=?", id);
        return view(read(id, false), clock.instant().getEpochSecond());
    }

    @Transactional
    public Credentials rotateKey(String id) {
        read(id, true);
        String key = newKey();
        jdbc.update("UPDATE device SET key_hash=? WHERE device_id=?", hash(key), id);
        return new Credentials(id, key);
    }

    @Transactional
    public Poll poll(String id, String key, Boolean relayOn) {
        Stored d;
        try {
            d = read(id, true);
        } catch (ResponseStatusException ex) {
            // 未知设备和密钥错误采用相同响应，避免公开设备枚举。
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "设备认证失败");
        }
        if (key == null || key.length() > 128 || !MessageDigest.isEqual(
                d.hash.getBytes(StandardCharsets.UTF_8), hash(key).getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "设备认证失败");
        }
        long now = clock.instant().getEpochSecond();
        boolean on = d.expiry > now;
        jdbc.update("UPDATE device SET status=?,last_seen_ts=?,relay_on=? WHERE device_id=?",
                on ? "on" : "off", now, relayOn, id);
        return new Poll(on ? "on" : "off", d.expiry, now, Math.max(0, d.expiry - now));
    }

    private String newKey() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
