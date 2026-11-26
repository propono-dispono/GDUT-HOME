package com.gdut.xiaojii.support.file;

import com.gdut.xiaojii.dto.vo.UploadVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储抽象。
 *
 * <p><b>为什么抽成接口：</b>当前存本地磁盘，将来换腾讯云 COS / MinIO 只需要新增一个实现类
 * 并把 Bean 换掉，Controller 与业务代码一行不用改。
 *
 * @author 校集后端
 */
public interface FileStorageService {

    /**
     * 保存图片。
     *
     * @param file  上传的文件
     * @param scene 业务场景（post 帖子配图 / card 校园卡 / avatar 头像 / report 举报证据），
     *              只用于归档目录，不参与 URL 拼接的用户输入
     * @return key（入库）+ url（前端预览）
     */
    UploadVO store(MultipartFile file, String scene);
}
