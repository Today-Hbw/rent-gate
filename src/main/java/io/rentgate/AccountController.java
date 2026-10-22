package io.rentgate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountController {
    private final AccountService accounts;
    public AccountController(AccountService accounts) { this.accounts=accounts; }

    public record Create(@NotBlank @Pattern(regexp="[A-Za-z0-9_][A-Za-z0-9_.-]{2,39}") String username,
                         @NotBlank @Size(max=80) String displayName, @NotBlank String role, Long providerId,
                         @NotBlank @Size(min=8,max=64) String password) {}
    public record Update(@NotBlank @Size(max=80) String displayName, boolean enabled) {}
    public record Password(@NotBlank @Size(min=8,max=64) String password) {}
    public record OwnPassword(@NotBlank @Size(max=72) String currentPassword,@NotBlank @Size(min=8,max=64) String password) {}

    @GetMapping("/admin/me") public AccountService.Account me() { return accounts.me(); }
    @GetMapping("/admin/accounts") public List<AccountService.Account> list() { return accounts.list(); }
    @PostMapping("/admin/accounts") @ResponseStatus(HttpStatus.CREATED)
    public AccountService.Account create(@Valid @RequestBody Create request) {
        return accounts.create(request.username,request.displayName,request.role,request.providerId,request.password);
    }
    @PutMapping("/admin/accounts/{id}") public AccountService.Account update(@PathVariable long id,@Valid @RequestBody Update request) {
        return accounts.update(id,request.displayName,request.enabled);
    }
    @PostMapping("/admin/accounts/{id}/password") public Map<String,Boolean> reset(@PathVariable long id,@Valid @RequestBody Password request) {
        accounts.resetPassword(id,request.password); return Map.of("success",true);
    }
    @PostMapping("/admin/me/password") public Map<String,Boolean> own(@Valid @RequestBody OwnPassword request) {
        accounts.changeOwnPassword(request.currentPassword,request.password); return Map.of("success",true);
    }
}
