package com.gdut.xiaojii.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 文件上传配置，对应 {@code xiaojii.upload.*}。
 *
 * <p>当前实现存本地磁盘（{@code LocalFileStorageService}）。
 * 将来换 COS / MinIO 只需要再写一个 {@code FileStorageService} 实现并切换 Bean，
 * 接口与调用方都不用改 —— 这就是把存储抽象成接口的原因。
 *
 * @author 校集后端
 */
@Data
@Component
@ConfigurationProperties(prefix = "xiaojii.upload")
public class UploadProperties {

    /** 物理存储根目录，相对路径基于应用启动目录 */
    private String baseDir = "./uploads";

    /** 对外访问前缀，拼在返回的 url 前面，如 http://localhost:8080/api/files */
    private String publicBase = "http://localhost:8080/api/files";

    /** 单文件上限（字节） */
    private long maxSize = 5 * 1024 * 1024;

    /** 允许的扩展名，逗号分隔 */
    private String allowExt = "jpg,jpeg,png,webp";

    public List<String> allowExtList() {
        return Arrays.stream(allowExt.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
