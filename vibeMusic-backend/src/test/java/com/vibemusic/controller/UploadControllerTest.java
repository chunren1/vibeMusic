package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.entity.User;
import com.vibemusic.security.CustomUserDetails;
import com.vibemusic.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * UploadController 纯单元测试（Mockito 直调，不启动 Spring）。
 *
 * <p>这是全仓唯一接受外部文件的入口，重点锁三件事：
 * 每一条拒绝分支（未登录 / 空文件 / 类型 / 超 2MB / 魔数）都必须给 4xx 而不是 500；
 * 成功响应里必须带可访问的 {@code /uploads/avatars/...} URL；
 * 只有 IOException 会被吞成 400，其它异常按既有约定上抛。</p>
 */
@DisplayName("UploadController 头像/背景图上传分支单元测试")
class UploadControllerTest {

    private static final Long USER_ID = 1L;
    private static final byte[] PNG_HEADER = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_HEADER = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
    private static final byte[] GIF_HEADER = {'G', 'I', 'F', '8', '9', 'a', 0, 0};

    private UserService userService;
    private UploadController controller;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        controller = new UploadController(userService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------- 未登录 / 空文件 / 类型 ----------

    @Test
    @DisplayName("头像：未登录返回 401，且不碰 UserService")
    void avatarShouldRejectAnonymous() {
        Result<Map<String, Object>> result = controller.uploadAvatar(file("image/png", 8, "a.png", PNG_HEADER));

        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getMessage()).isEqualTo("未登录");
        assertThat(result.getData()).isNull();
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("背景图：未登录返回 401")
    void bgImageShouldRejectAnonymous() {
        Result<Map<String, Object>> result = controller.uploadBgImage(file("image/png", 8, "a.png", PNG_HEADER));

        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getMessage()).isEqualTo("未登录");
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("头像：空文件返回 400「请选择文件」")
    void avatarShouldRejectEmptyFile() {
        loginAs(USER_ID);
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(true);

        Result<Map<String, Object>> result = controller.uploadAvatar(file);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("请选择文件");
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("背景图：空文件返回 400「请选择文件」")
    void bgImageShouldRejectEmptyFile() {
        loginAs(USER_ID);
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(true);

        Result<Map<String, Object>> result = controller.uploadBgImage(file);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("请选择文件");
    }

    @Test
    @DisplayName("头像：contentType 为 null 或不在白名单（bmp/svg）时一律 400")
    void avatarShouldRejectUnsupportedContentType() {
        loginAs(USER_ID);
        MultipartFile nullType = file(null, 8, "a.png", PNG_HEADER);
        MultipartFile bmp = file("image/bmp", 8, "a.bmp", PNG_HEADER);
        MultipartFile svg = file("image/svg+xml", 8, "a.svg", PNG_HEADER);

        for (MultipartFile f : List.of(nullType, bmp, svg)) {
            Result<Map<String, Object>> result = controller.uploadAvatar(f);
            assertThat(result.getCode()).isEqualTo(400);
            assertThat(result.getMessage()).contains("不支持的文件类型");
        }
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("背景图：类型不在白名单时 400，文案与头像一致")
    void bgImageShouldRejectUnsupportedContentType() {
        loginAs(USER_ID);

        Result<Map<String, Object>> result = controller.uploadBgImage(file("application/pdf", 8, "a.pdf", PNG_HEADER));

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).contains("不支持的文件类型");
    }

    @Test
    @DisplayName("头像：超过 2MB 返回 400「头像文件不能超过 2MB」")
    void avatarShouldRejectOversizedFile() {
        loginAs(USER_ID);
        MultipartFile file = file("image/png", 2L * 1024 * 1024 + 1, "big.png", PNG_HEADER);

        Result<Map<String, Object>> result = controller.uploadAvatar(file);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("头像文件不能超过 2MB");
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("背景图：超过 2MB 返回 400「背景图文件不能超过 2MB」")
    void bgImageShouldRejectOversizedFile() {
        loginAs(USER_ID);

        Result<Map<String, Object>> result =
                controller.uploadBgImage(file("image/png", 2L * 1024 * 1024 + 1, "big.png", PNG_HEADER));

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("背景图文件不能超过 2MB");
    }

    // ---------- 魔数校验 ----------

    @Test
    @DisplayName("头像：Content-Type 伪造（text 内容配 image/png）被魔数校验拦下")
    void avatarShouldRejectForgedContentType() {
        loginAs(USER_ID);

        Result<Map<String, Object>> result =
                controller.uploadAvatar(file("image/png", 5, "fake.png", "hello".getBytes()));

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("文件内容不是有效图片");
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("头像：文件头不足 4 字节时拒绝，不越界")
    void avatarShouldRejectTooShortHeader() {
        loginAs(USER_ID);

        Result<Map<String, Object>> result =
                controller.uploadAvatar(file("image/png", 2, "tiny.png", new byte[]{'P', 'N'}));

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("文件内容不是有效图片");
    }

    @Test
    @DisplayName("头像：读流抛 IOException 时按「不是有效图片」拒绝，不冒 500")
    void avatarShouldRejectWhenInputStreamFails() throws Exception {
        loginAs(USER_ID);
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("image/png");
        when(file.getSize()).thenReturn(8L);
        when(file.getInputStream()).thenThrow(new IOException("stream broken"));

        Result<Map<String, Object>> result = controller.uploadAvatar(file);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("文件内容不是有效图片");
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("背景图：伪造内容同样被魔数校验拦下")
    void bgImageShouldRejectForgedContentType() {
        loginAs(USER_ID);

        Result<Map<String, Object>> result =
                controller.uploadBgImage(file("image/jpeg", 5, "fake.jpg", "hello".getBytes()));

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("文件内容不是有效图片");
    }

    @Test
    @DisplayName("已知缺陷：合法 WebP 被拒（header 只有 8 字节，却判断 header[8..11]）")
    void webpIsRejectedBecauseHeaderBufferIsTooShort() {
        loginAs(USER_ID);
        // RIFF....WEBP
        byte[] webp = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};

        Result<Map<String, Object>> result = controller.uploadAvatar(file("image/webp", webp.length, "a.webp", webp));

        // 白名单允许 image/webp，但魔数校验永远失败 → 合法 WebP 无法上传（生产缺陷，测试锁定现状）
        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("文件内容不是有效图片");
        verifyNoInteractions(userService);
    }

    // ---------- 成功路径 ----------

    @Test
    @DisplayName("头像成功：返回 /uploads/avatars/avatar_{userId}_{ts}.png 并回写 UserService")
    void avatarShouldReturnUrlAndUpdatedUserData() {
        loginAs(USER_ID);
        MultipartFile file = file("image/png", 8, "me.png", PNG_HEADER);
        when(userService.updateAvatar(eq(USER_ID), anyString())).thenReturn(
                User.builder().id(USER_ID).username("alice").nickname("爱丽丝").avatar("/uploads/avatars/x.png")
                        .gender("female").birthday("2000-01-01").build());

        Result<Map<String, Object>> result = controller.uploadAvatar(file);

        assertThat(result.getCode()).isEqualTo(200);
        Map<String, Object> data = result.getData();
        String url = (String) data.get("avatarUrl");
        assertThat(url).startsWith("/uploads/avatars/avatar_1_").endsWith(".png");
        assertThat(data).containsEntry("userId", USER_ID)
                .containsEntry("username", "alice")
                .containsEntry("nickname", "爱丽丝")
                .containsEntry("avatar", "/uploads/avatars/x.png")
                .containsEntry("gender", "female")
                .containsEntry("birthday", "2000-01-01")
                .containsKey("bgImage");
        verify(userService).updateAvatar(USER_ID, url);
    }

    @Test
    @DisplayName("头像成功：昵称为空时回落 username，前端不会显示空白")
    void avatarShouldFallbackNicknameToUsername() {
        loginAs(USER_ID);
        when(userService.updateAvatar(eq(USER_ID), anyString()))
                .thenReturn(User.builder().id(USER_ID).username("bob").build());

        Result<Map<String, Object>> result = controller.uploadAvatar(file("image/png", 8, "me.png", PNG_HEADER));

        assertThat(result.getData()).containsEntry("nickname", "bob");
    }

    @Test
    @DisplayName("头像成功：文件名无扩展名时扩展名兜底 jpg")
    void avatarShouldFallbackToJpgExtension() {
        loginAs(USER_ID);
        when(userService.updateAvatar(eq(USER_ID), anyString()))
                .thenReturn(User.builder().id(USER_ID).username("bob").build());

        Result<Map<String, Object>> result = controller.uploadAvatar(file("image/png", 8, "noext", PNG_HEADER));

        assertThat((String) result.getData().get("avatarUrl")).endsWith(".jpg");
    }

    @Test
    @DisplayName("头像成功：扩展名统一小写（ME.PNG → .png）")
    void avatarShouldLowercaseExtension() {
        loginAs(USER_ID);
        when(userService.updateAvatar(eq(USER_ID), anyString()))
                .thenReturn(User.builder().id(USER_ID).username("bob").build());

        Result<Map<String, Object>> result = controller.uploadAvatar(file("image/png", 8, "ME.PNG", PNG_HEADER));

        assertThat((String) result.getData().get("avatarUrl")).endsWith(".png");
    }

    @Test
    @DisplayName("头像成功：JPEG 与 GIF 魔数均放行")
    void avatarShouldAcceptJpegAndGifMagic() {
        loginAs(USER_ID);
        when(userService.updateAvatar(eq(USER_ID), anyString()))
                .thenReturn(User.builder().id(USER_ID).username("bob").build());

        assertThat(controller.uploadAvatar(file("image/jpeg", 8, "a.jpg", JPEG_HEADER)).getCode()).isEqualTo(200);
        assertThat(controller.uploadAvatar(file("image/gif", 8, "a.gif", GIF_HEADER)).getCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("头像成功：真实字节落盘到 uploads/avatars，内容与上传一致")
    void avatarShouldPersistBytesToDisk() throws Exception {
        loginAs(USER_ID);
        when(userService.updateAvatar(eq(USER_ID), anyString()))
                .thenReturn(User.builder().id(USER_ID).username("bob").build());
        MockMultipartFile file = new MockMultipartFile("file", "real.png", "image/png", PNG_HEADER);

        Result<Map<String, Object>> result = controller.uploadAvatar(file);

        assertThat(result.getCode()).isEqualTo(200);
        String url = (String) result.getData().get("avatarUrl");
        Path written = Paths.get(System.getProperty("user.dir") + File.separator + "uploads"
                + File.separator + "avatars", url.substring("/uploads/avatars/".length()));
        try {
            assertThat(Files.exists(written)).as("文件应已落盘: %s", written).isTrue();
            assertThat(Files.readAllBytes(written)).isEqualTo(PNG_HEADER);
        } finally {
            Files.deleteIfExists(written);
        }
    }

    @Test
    @DisplayName("头像失败：transferTo 抛 IOException 时返回 400「头像上传失败」")
    void avatarShouldReportIoFailure() throws Exception {
        loginAs(USER_ID);
        MultipartFile file = file("image/png", 8, "me.png", PNG_HEADER);
        doThrow(new IOException("disk full")).when(file).transferTo(any(File.class));

        Result<Map<String, Object>> result = controller.uploadAvatar(file);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).contains("头像上传失败").contains("disk full");
        verify(userService, never()).updateAvatar(any(), anyString());
    }

    @Test
    @DisplayName("头像失败：UserService 抛 BusinessException 时上抛（仅 IOException 被吞）")
    void avatarShouldPropagateBusinessExceptionFromUserService() {
        loginAs(USER_ID);
        when(userService.updateAvatar(eq(USER_ID), anyString()))
                .thenThrow(new BusinessException(404, "用户不存在"));

        assertThatThrownBy(() -> controller.uploadAvatar(file("image/png", 8, "me.png", PNG_HEADER)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户不存在");
    }

    @Test
    @DisplayName("背景图成功：返回 bg_{userId}_{ts} 前缀的 URL 并回写 bgImageUrl")
    void bgImageShouldReturnUrlAndUpdatedUserData() {
        loginAs(USER_ID);
        when(userService.updateBgImage(eq(USER_ID), anyString()))
                .thenReturn(User.builder().id(USER_ID).username("alice").nickname("爱丽丝").build());

        Result<Map<String, Object>> result = controller.uploadBgImage(file("image/png", 8, "bg.png", PNG_HEADER));

        assertThat(result.getCode()).isEqualTo(200);
        String url = (String) result.getData().get("bgImageUrl");
        assertThat(url).startsWith("/uploads/avatars/bg_1_").endsWith(".png");
        assertThat(result.getData()).containsEntry("nickname", "爱丽丝");
        verify(userService).updateBgImage(USER_ID, url);
        verify(userService, never()).updateAvatar(any(), anyString());
    }

    @Test
    @DisplayName("背景图失败：transferTo 抛 IOException 时返回 400「背景图上传失败」")
    void bgImageShouldReportIoFailure() throws Exception {
        loginAs(USER_ID);
        MultipartFile file = file("image/png", 8, "bg.png", PNG_HEADER);
        doThrow(new IOException("no space")).when(file).transferTo(any(File.class));

        Result<Map<String, Object>> result = controller.uploadBgImage(file);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).contains("背景图上传失败").contains("no space");
        verify(userService, never()).updateBgImage(any(), anyString());
    }

    // ---------- helpers ----------

    private static void loginAs(Long userId) {
        User user = new User();
        user.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CustomUserDetails(user), null, List.of()));
    }

    /** 构造一个「已通过前置校验」的假 MultipartFile（transferTo 默认空实现，不落盘）。 */
    private static MultipartFile file(String contentType, long size, String filename, byte[] header) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn(contentType);
        when(file.getSize()).thenReturn(size);
        when(file.getOriginalFilename()).thenReturn(filename);
        try {
            when(file.getInputStream()).thenReturn(new ByteArrayInputStream(header));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return file;
    }
}
