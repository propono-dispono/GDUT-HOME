package com.gdut.xiaojii.controller.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.entity.SensitiveWord;
import com.gdut.xiaojii.mapper.SensitiveWordMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端：敏感词库。
 *
 * <p>sensitive_word 表以 word 作为唯一键（没有自增主键），所以全部按 word 操作。</p>
 *
 * @author 校集后端
 */
@Tag(name = "97. 敏感词库（管理端）")
@RestController
@RequestMapping("/admin/word")
@RequiredArgsConstructor
public class AdminWordController {

    private final SensitiveWordMapper wordMapper;

    @Operation(summary = "敏感词列表", description = "keyword 模糊匹配词条")
    @GetMapping("/list")
    /** 列表 */
    public Result<List<SensitiveWord>> list(
            @Parameter(description = "搜索词") @RequestParam(required = false) String keyword) {
        return Result.ok(wordMapper.selectList(
                Wrappers.<SensitiveWord>lambdaQuery()
                        .like(keyword != null && !keyword.isBlank(), SensitiveWord::getWord, keyword)
                        .orderByDesc(SensitiveWord::getLevel)));
    }

    @Operation(summary = "新增敏感词", description = "level：1 直接拦截 / 2 警告")
    @PostMapping
    /** 添加 */
    public Result<Void> add(@RequestParam String word,
                            @RequestParam(defaultValue = "1") Integer level) {
        if (word == null || word.isBlank()) {
            throw BizException.of(ResultCode.PARAM_ERROR, "词条不能为空");
        }
        SensitiveWord w = new SensitiveWord();
        w.setWord(word.trim());
        w.setLevel(level);
        w.setEnabled(1);
        try {
            wordMapper.insert(w);
        } catch (DuplicateKeyException e) {
            throw BizException.of(ResultCode.PARAM_ERROR, "该词条已存在");
        }
        return Result.ok();
    }

    @Operation(summary = "启用/停用")
    @PostMapping("/toggle")
    public Result<Void> toggle(@RequestParam String word, @RequestParam Integer enabled) {
        int rows = wordMapper.update(null, Wrappers.<SensitiveWord>lambdaUpdate()
                .eq(SensitiveWord::getWord, word)
                .set(SensitiveWord::getEnabled, enabled));
        if (rows == 0) {
            throw BizException.of(ResultCode.NOT_FOUND, "词条不存在");
        }
        return Result.ok();
    }

    @Operation(summary = "删除敏感词")
    @DeleteMapping
    /** 删除 */
    public Result<Void> delete(@RequestParam String word) {
        int rows = wordMapper.delete(Wrappers.<SensitiveWord>lambdaQuery()
                .eq(SensitiveWord::getWord, word));
        if (rows == 0) {
            throw BizException.of(ResultCode.NOT_FOUND, "词条不存在");
        }
        return Result.ok();
    }
}
