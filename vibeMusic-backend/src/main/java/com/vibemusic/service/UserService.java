package com.vibemusic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.common.utils.CookieCryptoService;
import com.vibemusic.entity.User;
import com.vibemusic.mapper.UserMapper;
import com.vibemusic.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService implements UserDetailsService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final CookieCryptoService cookieCryptoService;

    /** 网易云 Cookie 明文上限（字符数，约 8KB，防超大输入撑爆加密/TEXT 字段） */
    private static final int NETEASE_COOKIE_MAX_CHARS = 8192;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) throw new UsernameNotFoundException("用户不存在: " + username);
        return new CustomUserDetails(user);
    }

    public User register(String username, String password, String nickname) {
        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .nickname(nickname != null ? nickname : username)
                .enabled(true)
                .build();
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "用户名已存在");
        }
        return user;
    }

    public User findByUsername(String username) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) throw new BusinessException(404, "用户不存在");
        return user;
    }

    public User findById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) throw new BusinessException(404, "用户不存在");
        return user;
    }

    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(404, "用户不存在");
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException(400, "原密码错误");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }

    @Transactional
    public User updateProfile(Long userId, String nickname, String gender, String birthday) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(404, "用户不存在");
        if (nickname != null) user.setNickname(nickname.trim());
        if (gender != null) user.setGender(gender);
        if (birthday != null) user.setBirthday(birthday);
        userMapper.updateById(user);
        return user;
    }

    @Transactional(rollbackFor = Exception.class)
    public User updateAvatar(Long userId, String avatarUrl) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(404, "用户不存在");
        user.setAvatar(avatarUrl);
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getAvatar, avatarUrl));
        return user;
    }

    @Transactional(rollbackFor = Exception.class)
    public User updateBgImage(Long userId, String bgImageUrl) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(404, "用户不存在");
        user.setBgImage(bgImageUrl);
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getBgImage, bgImageUrl));
        return user;
    }

    public static Long getCurrentUserId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails userDetails) {
            return userDetails.getUserId();
        }
        return null;
    }

    /**
     * 网易云 Cookie 绑定状态（只暴露元数据，绝不含 Cookie 本体）。
     *
     * @param has      是否已绑定（密文+IV 均存在）
     * @param valid    有效性：null=未知，true=有效，false=失效
     * @param updatedAt 最近更新时间：null=从未更新
     */
    public record NeteaseCookieStatus(boolean has, Boolean valid, LocalDateTime updatedAt) {
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveNeteaseCookie(Long userId, String rawCookie) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(404, "用户不存在");
        if (rawCookie == null || rawCookie.isBlank()) throw new BusinessException(400, "Cookie 不能为空");
        String trimmed = rawCookie.strip();
        if (trimmed.length() > NETEASE_COOKIE_MAX_CHARS) throw new BusinessException(400, "Cookie 过长");
        if (!trimmed.contains("MUSIC_U=")) throw new BusinessException(400, "Cookie 缺少 MUSIC_U");
        String blob = cookieCryptoService.encrypt(trimmed);
        int sep = blob.indexOf(':');
        String iv = blob.substring(0, sep);
        String enc = blob.substring(sep + 1);
        LocalDateTime now = LocalDateTime.now();
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getNeteaseCookieEnc, enc)
                .set(User::getNeteaseCookieIv, iv)
                .set(User::getNeteaseCookieUpdatedAt, now)
                .set(User::getNeteaseCookieValid, null));
    }

    @Transactional(rollbackFor = Exception.class)
    public void clearNeteaseCookie(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(404, "用户不存在");
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getNeteaseCookieEnc, null)
                .set(User::getNeteaseCookieIv, null)
                .set(User::getNeteaseCookieUpdatedAt, null)
                .set(User::getNeteaseCookieValid, null));
    }

    public NeteaseCookieStatus getNeteaseCookieStatus(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(404, "用户不存在");
        boolean has = user.getNeteaseCookieEnc() != null && !user.getNeteaseCookieEnc().isBlank()
                && user.getNeteaseCookieIv() != null && !user.getNeteaseCookieIv().isBlank();
        return new NeteaseCookieStatus(has, user.getNeteaseCookieValid(), user.getNeteaseCookieUpdatedAt());
    }

    /**
     * 标记网易云 Cookie 失效（BYOC Task 7：过期/失效探测 → 重绑流程）。
     *
     * <p>调用方（per-user 上游取链）在 {@link NeteaseApiService#isNeedLoginPayload}
     * 判定为 need-login 后调用本方法，将 {@code netease_cookie_valid} 置 0；
     * {@code GET /api/cookies/status} 随后对该用户返回 {@code needsRebind:true}。
     *
     * <p>幂等：重复标记同一用户保持 0，不抛错。红线：只记 userId + 上游 code，
     * 绝不记录 Cookie 字节（明文/密文/长度之外的一切）。
     *
     * @param userId       被标记用户
     * @param upstreamCode 上游返回的顶层 code（301/-101 等，仅用于日志）
     */
    @Transactional(rollbackFor = Exception.class)
    public void markNeteaseCookieInvalid(Long userId, Object upstreamCode) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(404, "用户不存在");
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getNeteaseCookieValid, false));
        log.info("网易云 Cookie 已标记失效: userId={}, upstreamCode={}", userId, upstreamCode);
    }

    public Optional<String> resolveNeteaseCookie(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(404, "用户不存在");
        String enc = user.getNeteaseCookieEnc();
        String iv = user.getNeteaseCookieIv();
        if (enc == null || enc.isBlank() || iv == null || iv.isBlank()) return Optional.empty();
        try {
            return Optional.of(cookieCryptoService.decrypt(iv + ":" + enc));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
