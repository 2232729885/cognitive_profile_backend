package com.idata.profile.controller;

import com.idata.profile.auth.JwtAuthFilter;
import com.idata.profile.auth.JwtTokenProvider;
import com.idata.profile.auth.LoginResponse;
import com.idata.profile.auth.UserInfo;
import com.idata.profile.common.response.Result;
import com.idata.profile.entity.system.User;
import com.idata.profile.mapper.system.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String AUTH_FAILED_CODE = "AUTH_FAILED";
    private static final String AUTH_FAILED_MESSAGE = "\u7528\u6237\u540d\u6216\u5bc6\u7801\u9519\u8bef";

    private final UserMapper userMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest request) {
        if (request == null || request.getUsername() == null || request.getPassword() == null) {
            return Result.fail(AUTH_FAILED_CODE, AUTH_FAILED_MESSAGE);
        }

        User user = userMapper.selectByUsername(request.getUsername());
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())
                || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            return Result.fail(AUTH_FAILED_CODE, AUTH_FAILED_MESSAGE);
        }

        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole());
        user.setLastLoginAt(OffsetDateTime.now());
        userMapper.updateById(user);

        LoginResponse response = new LoginResponse();
        response.setToken(token);
        response.setUsername(user.getUsername());
        response.setRole(user.getRole());
        response.setExpiresIn(jwtTokenProvider.getExpirationMs());
        return Result.ok(response);
    }

    @GetMapping("/me")
    public Result<UserInfo> me(HttpServletRequest request) {
        UserInfo userInfo = new UserInfo();
        userInfo.setUserId((UUID) request.getAttribute(JwtAuthFilter.ATTR_USER_ID));
        userInfo.setUsername((String) request.getAttribute(JwtAuthFilter.ATTR_USERNAME));
        userInfo.setRole((String) request.getAttribute(JwtAuthFilter.ATTR_ROLE));
        return Result.ok(userInfo);
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }
}
