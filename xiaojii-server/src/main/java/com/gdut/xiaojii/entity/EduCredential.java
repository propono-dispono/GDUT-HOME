package com.gdut.xiaojii.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 教务账号加密凭据。
 *
 * <p>只存 AES-GCM 密文，不存账号/密码明文；30 天滑动过期，用户可随时删除。
 *
 * @author 校集后端
 */
@Data
@TableName("edu_credential")
public class EduCredential {

    @TableId(type = IdType.INPUT)
    private Long userId;

    private String payloadCipher;

    private String nonce;

    private String accountMasked;

    private Integer keyVersion;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
