package io.rentgate;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PairingAttemptLimiter {
    private record Attempts(long started,int count) {}
    private final Map<String,Attempts> failures=new HashMap<>();
    private final Clock clock;
    public PairingAttemptLimiter(Clock clock) { this.clock=clock; }
    public synchronized void check(String address) {
        long now=clock.instant().getEpochSecond();
        failures.entrySet().removeIf(entry->now-entry.getValue().started>=300);
        Attempts attempts=failures.get(address);
        if (failures.size()>=4096 && attempts==null || attempts!=null && attempts.count>=20)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"配对尝试过多，请稍后重试");
    }
    public synchronized void failed(String address) {
        check(address);long now=clock.instant().getEpochSecond();Attempts old=failures.get(address);
        failures.put(address,new Attempts(old==null?now:old.started,old==null?1:old.count+1));
    }
}
