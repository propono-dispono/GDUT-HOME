package com.gdut.xiaojii.support.file;

import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.config.UploadProperties;
import com.gdut.xiaojii.dto.vo.UploadVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 本地磁盘存储实现。
 *
 * <p><b>三层校验（缺一不可）：</b>
 * <ol>
 *   <li>扩展名白名单 —— 挡住 .php / .jsp 这类直接上传脚本的攻击</li>
 *   <li>文件头魔数 —— 挡住"把 exe 改成 .jpg"的伪装</li>
 *   <li>文件名完全由服务端生成（UUID）—— 挡住 {@code ../../etc/passwd} 这类路径穿越</li>
 * </ol>
 *
 * <p>目录结构：{@code {baseDir}/{scene}/{yyyyMM}/{uuid}.{ext}}，
 * 按场景与月份分目录，避免单目录几十万文件导致 ls 卡死。
 *
 * @author 校集后端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalFileStorageService implements FileStorageService {

    private final UploadProperties props;

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    /** 允许的业务场景白名单。用枚举式白名单而不是直接拼用户传的 scene，防目录穿越 */
    private static final List<String> ALLOWED_SCENE = List.of("post", "card", "avatar", "report");

    @Override
    public UploadVO store(MultipartFile file, String scene) {
        if (file == null || file.isEmpty()) {
            throw BizException.of(ResultCode.FILE_EMPTY);
        }
        if (file.getSize() > props.getMaxSize()) {
            throw BizException.of(ResultCode.FILE_TOO_LARGE);
        }

        String safeScene = ALLOWED_SCENE.contains(scene) ? scene : "post";
        String ext = resolveExt(file.getOriginalFilename());
        if (!props.allowExtList().contains(ext)) {
            throw BizException.of(ResultCode.FILE_TYPE_NOT_ALLOWED);
        }
        // 校验真实内容类型：扩展名可以改，文件头改不了
        checkMagic(file, ext);

        String month = LocalDate.now().format(MONTH);
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        // key 入库、也用于拼 URL，全程无用户输入
        String key = safeScene + "/" + month + "/" + filename;

        Path baseDir = Paths.get(props.getBaseDir()).toAbsolutePath().normalize();
        Path target = baseDir.resolve(key).normalize();
        // 双保险：即使前面逻辑被改坏，也不允许写到 baseDir 外面
        if (!target.startsWith(baseDir)) {
            throw BizException.of(ResultCode.FILE_UPLOAD_FAIL, "非法的存储路径");
        }

        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("保存上传文件失败 key={}", key, e);
            throw BizException.of(ResultCode.FILE_UPLOAD_FAIL);
        }

        UploadVO vo = new UploadVO();
        vo.setKey(key);
        vo.setUrl(props.getPublicBase() + "/" + key);
        return vo;
    }

    /** 取扩展名，无扩展名视为非法（返回空串，后续白名单校验会拒绝） */
    private String resolveExt(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 校验文件头魔数。
     * <ul>
     *   <li>JPG：FF D8 FF</li>
     *   <li>PNG：89 50 4E 47 0D 0A 1A 0A</li>
     *   <li>WEBP：RIFF .... WEBP</li>
     * </ul>
     */
    private void checkMagic(MultipartFile file, String ext) {
        byte[] head = new byte[12];
        try (InputStream in = file.getInputStream()) {
            int read = in.read(head);
            if (read < 4) {
                throw BizException.of(ResultCode.FILE_TYPE_NOT_ALLOWED);
            }
        } catch (IOException e) {
            throw BizException.of(ResultCode.FILE_UPLOAD_FAIL);
        }

        boolean ok = switch (ext) {
            case "jpg", "jpeg" -> (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            case "png" -> (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
            case "webp" -> head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F';
            default -> false;
        };
        if (!ok) {
            throw BizException.of(ResultCode.FILE_TYPE_NOT_ALLOWED, "文件内容不是合法的图片");
        }
    }
}
