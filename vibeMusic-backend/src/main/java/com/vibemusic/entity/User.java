package com.vibemusic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    private String nickname;

    private String avatar;

    private String bgImage;

    private String gender;

    private String birthday;

    @Builder.Default
    private Boolean enabled = true;

    /** 网易云 Cookie 密文体（AES-GCM ctB64），NULL=未绑定 */
    private String neteaseCookieEnc;

    /** 网易云 Cookie 加密 IV（ivB64），NULL=未绑定 */
    private String neteaseCookieIv;

    /** 网易云 Cookie 最近更新时间，NULL=从未更新 */
    private LocalDateTime neteaseCookieUpdatedAt;

    /** 网易云 Cookie 有效性：NULL=未知，true=有效，false=失效 */
    private Boolean neteaseCookieValid;

    /** B 站 SESSDATA 密文槽位（预留，Phase 1 无任何读写） */
    private String biliSessdataEnc;
}
