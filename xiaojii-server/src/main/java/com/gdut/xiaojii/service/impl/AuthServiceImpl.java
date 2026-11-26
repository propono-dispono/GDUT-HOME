package com.gdut.xiaojii.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.JwtUtil;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.common.UserContext;
import com.gdut.xiaojii.common.constant.RedisKey;
import com.gdut.xiaojii.config.WxProperties;
import com.gdut.xiaojii.dto.req.DevLoginReq;
import com.gdut.xiaojii.dto.req.LoginReq;
import com.gdut.xiaojii.dto.vo.LoginVO;
import com.gdut.xiaojii.entity.User;
import com.gdut.xiaojii.mapper.UserMapper;
import com.gdut.xiaojii.service.AuthService;
import com.gdut.xiaojii.support.wx.WxApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 登录服务实现。
 *
 * 完整链路（微信登录）：
 *   小程序 wx.login() → code
 *     → 后端 POST /app/auth/login
 *     → 微信 jscode2session → openid
 *     → 查 user 表；没有则建档（credit_score=80, verify_status=0）
 *     → 签发 JWT（subject=userId, typ=app）
 *     → Redis 写 login:token:{userId}=token（TTL 7 天，单端登录，覆盖即踢掉旧设备）
 *     → 返回 token
 *
 * 为什么不存 session_key：本项目不解密手机号、不拿用户敏感数据，
 * 存了反而多一处泄露面，用完即弃。
 *
 * @author 校集后端
 */
// Lombok 生成 log 字段（private static final Logger log），省去手写
@Slf4j
// 注册成 Spring Bean，Controller 注入的就是它（通过 AuthService 接口）
@Service
// 为 5 个 final 字段生成构造器，Spring 用构造器注入
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    // 用户表的数据访问：按 openid 查用户、插入新用户、更新最后登录时间、更新账号信息
    private final UserMapper userMapper;
    // JWT 工具：generate(userId) 签令牌、parse(token) 验签、getExpireSeconds() 取有效期
    private final JwtUtil jwtUtil;
    // Redis 客户端：登录白名单 login:token:{id}、账号状态缓存 user:status:{id}
    // 用 StringRedisTemplate 而不是 RedisTemplate<String,Object>：
    // 后者会把 value 序列化成带双引号的 "eyJ..."，与请求头里的裸 token 永远比对不上
    private final StringRedisTemplate redis;
    // 微信接口客户端：本项目只用它的 code2Session(code)，把小程序临时 code 换成 openid
    private final WxApiClient wxApiClient;
    // 微信配置（appid / secret / dev-login 开关）：这里只用 isDevLoginEnabled()
    // 判断开发登录是否放行，生产环境该开关必须为 false
    private final WxProperties wxProperties;

    /** 默认昵称：微信不再返回昵称，新用户先给个中性名字，进"我的"页可改 */
    private static final String DEFAULT_NICKNAME = "校集用户";

    /**
     * 微信登录：code 换 openid，再走公共的登录逻辑。
     *
     * 这里只做两件事：① 调微信换 openid；② 转交 doLogin。
     * 不自己写建档/签令牌，因为开发登录也要用同一套逻辑（见 loginByDev）。
     */
    // 表示重写接口方法；写错方法名时编译期就会报错
    @Override
    // 声明事务：本方法内所有数据库写操作要么全成功、要么全回滚
    @Transactional(rollbackFor = Exception.class)
    /** 登录bywx */
    public LoginVO loginByWx(LoginReq req) {
        WxApiClient.WxSession session = wxApiClient.code2Session(req.getCode());
        return doLogin(session.getOpenid(), session.getUnionid(), req.getNickname());
    }

    /**
     * 开发登录：跳过微信，直接用传入的标识当 openid。
     *
     * 安全边界：必须先判断开关，关掉时直接拒绝；
     * 否则任何人传一个 openid 就能登录任意账号（最严重的越权）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginVO loginByDev(DevLoginReq req) {
        if (!wxProperties.isDevLoginEnabled()) {
            // 生产环境必须关闭：否则任何人传个 openid 就能登录任意账号
            throw BizException.of(ResultCode.FORBIDDEN, "开发登录已在服务端关闭");
        }
        log.warn("[开发登录] openid={}，仅限本地调试使用", req.getOpenid());
        return doLogin(req.getOpenid(), null, req.getNickname());
    }

    /**
     * 登录共用的核心逻辑（两个登录入口最终都汇到这里）。
     *
     * 五步：查用户 → 没有则建档 / 有则更新 → 签 JWT → 写两个 Redis key → 组装 VO。
     *
     * @param openid    微信 openid（或开发登录的标识）
     * @param unionid   微信 unionid，可为空
     * @param nickname  本次登录想设置的昵称，可为空
     */
    private LoginVO doLogin(String openid, String unionid, String nickname) {
        // 第 1 步：按 openid 查用户。openid 有唯一索引，最多返回一条。
        // Wrappers.<User>lambdaQuery() 是 MyBatis-Plus 的条件构造器写法，
        // 这整句等价于 SQL：SELECT * FROM user WHERE openid = ? LIMIT 1
        // 用方法引用（User::getOpenid）而不是字符串 "openid"：
        // 字段改名时编译期就能发现，不会因为拼错列名而在运行时才报错。
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery().eq(User::getOpenid, openid));

        // firstLogin 会进 LoginVO，用来告诉前端"这是刚注册的新用户"
        boolean firstLogin = false;
        if (user == null) {
            // 第 2 步（新用户）：建档
            firstLogin = true;
            user = new User();
            user.setOpenid(openid);
            user.setUnionid(unionid);
            // 昵称为空时用默认值；非空则 trim 掉首尾空格
            user.setNickname(nickname == null || nickname.isBlank() ? DEFAULT_NICKNAME : nickname.trim());
            user.setAvatar("");
            user.setIntro("");
            // 初始 80 分而不是 100：留出上升空间，也让"信用分"对用户有正向激励
            user.setCreditScore(80);
            user.setVerifyStatus(User.VERIFY_NONE);
            user.setStatus(User.STATUS_NORMAL);
            user.setLastLoginAt(LocalDateTime.now());
            // createdAt 不手动 set —— 由 MybatisMetaObjectHandler 按 @TableField(fill=INSERT) 自动填
            userMapper.insert(user);
            log.info("新用户注册 userId={} openid={}", user.getId(), openid);
        } else {
            // 第 3 步（老用户）：先判封禁，再按需更新
            // 封禁用户不给令牌（否则他能拿 token 调其他接口）
            if (User.STATUS_BANNED == user.getStatus()) {
                throw BizException.of(ResultCode.USER_BANNED);
            }
            // 老用户首次带昵称登录时，允许覆盖默认昵称
            // 只在"当前还是默认昵称"时覆盖，避免把用户自己改过的昵称重置掉
            if (nickname != null && !nickname.isBlank() && DEFAULT_NICKNAME.equals(user.getNickname())) {
                user.setNickname(nickname.trim());
            }
            // unionid 补全：早期没拿到时，后续登录拿得到就补上
            if (user.getUnionid() == null && unionid != null) {
                user.setUnionid(unionid);
            }
            // lastLoginAt 没有 @TableField(fill)，必须手动 set，否则不会更新
            user.setLastLoginAt(LocalDateTime.now());
            userMapper.updateById(user);
        }

        // 到这一步的时候就剩下新用户和老用户登录的情况（老用户是正常状态）
        // 第 4 步：签发 JWT（subject = userId，typ = app）
        String token = jwtUtil.generate(user.getId());
        // ttl = 令牌有效期（秒），来自配置 xiaojii.jwt.expire-seconds，默认 604800（7 天）
        long ttl = jwtUtil.getExpireSeconds();

        // 单端登录：直接覆盖，旧设备的下一次请求会因 token 不匹配被踢
        // Duration.ofSeconds(ttl) 把"秒数"转成 Redis 的过期时间对象，
        // 等价于 Redis 命令 SET key value EX 604800
        redis.opsForValue().set(RedisKey.loginToken(user.getId()), token, Duration.ofSeconds(ttl));
        // 缓存账号状态，拦截器据此做封禁校验（避免每个请求查库）
        // 两个 key 的 TTL 都等于 JWT 有效期，令牌过期时缓存自然失效，不留垃圾
        // String.valueOf(...)：status 是 Integer，而 StringRedisTemplate 只能存字符串，
        // 不转换会在运行时抛类型转换异常。
        redis.opsForValue().set(RedisKey.userStatus(user.getId()),
                String.valueOf(user.getStatus()), Duration.ofSeconds(ttl));

        // 第 5 步：组装 VO。逐个 set 而不用 BeanUtils 拷贝 —— 精确控制暴露哪些字段，
        // openid / unionid / status 一个都不给前端。
        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setUserId(user.getId());
        vo.setVerifyStatus(user.getVerifyStatus());
        vo.setNickname(user.getNickname());
        vo.setCreditScore(user.getCreditScore());
        vo.setFirstLogin(firstLogin);
        return vo;
    }

    /**
     * 退出登录：删除 Redis 白名单。
     *
     * 为什么不需要参数：当前用户从 UserContext 取（拦截器写入），
     * 这样前端无法伪造 userId 去注销别人的账号。
     */
    @Override
    public void logout() {
        Long userId = UserContext.requireUserId();
        // 删掉白名单 = 令牌立即失效（JWT 本身在 7 天内仍然"有效"，但过不了白名单校验）
        redis.delete(RedisKey.loginToken(userId));
        redis.delete(RedisKey.userStatus(userId));
        log.info("用户退出登录 userId={}", userId);
    }
}
