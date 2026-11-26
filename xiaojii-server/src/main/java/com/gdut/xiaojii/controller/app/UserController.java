package com.gdut.xiaojii.controller.app;

import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.dto.req.ProfileUpdateReq;
import com.gdut.xiaojii.dto.req.VerifySubmitReq;
import com.gdut.xiaojii.dto.vo.UserVO;
import com.gdut.xiaojii.dto.vo.VerifyVO;
import com.gdut.xiaojii.service.UserService;
import com.gdut.xiaojii.service.VerifyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序端用户接口：资料、认证、他人主页。
 *
 * @author 校集后端
 */
@Tag(name = "02. 用户与认证")
@RestController
@RequestMapping("/app/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final VerifyService verifyService;

    @Operation(summary = "我的资料")
    @GetMapping("/profile")
    public Result<UserVO> profile() {
        return Result.ok(userService.getSelf());
    }

    @Operation(summary = "修改我的资料", description = "只更新传了值的字段")
    @PutMapping("/profile")
    /** 更新资料 */
    public Result<UserVO> updateProfile(@Valid @RequestBody ProfileUpdateReq req) {
        return Result.ok(userService.updateSelf(req));
    }

    @Operation(summary = "查看他人主页")
    @GetMapping("/{id}")
    public Result<UserVO> userDetail(@PathVariable Long id) {
        return Result.ok(userService.getPublic(id));
    }

    @Operation(summary = "提交校园认证",
            description = "学号全局唯一；已认证返回 1022，审核中返回 1021，驳回后可重提")
    @PostMapping("/verify")
    /** 提交认证 */
    public Result<VerifyVO> submitVerify(@Valid @RequestBody VerifySubmitReq req) {
        return Result.ok(verifyService.submit(req));
    }

    @Operation(summary = "查询我的认证状态", description = "学号与姓名均脱敏返回")
    @GetMapping("/verify")
    /** 校验状态 */
    public Result<VerifyVO> verifyStatus() {
        return Result.ok(verifyService.getStatus());
    }
}
