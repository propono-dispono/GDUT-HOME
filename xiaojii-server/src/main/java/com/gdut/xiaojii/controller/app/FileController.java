package com.gdut.xiaojii.controller.app;

import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.dto.vo.UploadVO;
import com.gdut.xiaojii.support.file.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图片上传接口。
 *
 * <p>为什么不让小程序直传云存储：当前用本地磁盘，后端中转最简单；
 * 将来换 COS 时把 {@link FileStorageService} 换成直传 + STS 签名即可，前端接口不变。
 *
 * @author 校集后端
 */
@Tag(name = "03. 文件上传")
@RestController
@RequestMapping("/app")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    @Operation(summary = "上传图片",
            description = "支持 jpg/jpeg/png/webp，单张 ≤5MB；返回 key（入库）与 url（预览）")
    @PostMapping("/upload")
    /** 上传 */
    public Result<UploadVO> upload(
            @Parameter(description = "图片文件")
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "场景：post 帖子配图 / card 校园卡 / avatar 头像 / report 举报证据")
            @RequestParam(value = "scene", required = false, defaultValue = "post") String scene) {
        return Result.ok(fileStorageService.store(file, scene));
    }
}
