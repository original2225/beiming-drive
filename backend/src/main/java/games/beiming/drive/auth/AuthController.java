package games.beiming.drive.auth;

import games.beiming.drive.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final CurrentUser currentUser;

    public AuthController(AuthService authService, CurrentUser currentUser) {
        this.authService = authService;
        this.currentUser = currentUser;
    }

    public record RegisterReq(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 1, max = 64) String nickname,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record LoginReq(@Email @NotBlank String email, @NotBlank String password) {
    }

    @PostMapping("/register")
    public ApiResponse<AuthService.LoginResult> register(@Valid @RequestBody RegisterReq req) {
        return ApiResponse.ok(authService.register(req.email(), req.nickname(), req.password()));
    }

    @PostMapping("/login")
    public ApiResponse<AuthService.LoginResult> login(@Valid @RequestBody LoginReq req) {
        return ApiResponse.ok(authService.login(req.email(), req.password()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout(currentUser.require().tokenId());
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<AuthService.UserView> me() {
        return ApiResponse.ok(authService.me(currentUser.require().userId()));
    }
}