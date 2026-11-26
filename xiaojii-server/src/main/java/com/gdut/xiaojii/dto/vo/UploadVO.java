package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 图片上传返回。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "图片上传结果")
public class UploadVO {

    @Schema(description = "相对 key，入库用这个（如 202610/xxxx.jpg）")
    private String key;

    @Schema(description = "可直接访问的完整 URL，前端预览用")
    private String url;
}
