package com.gdut.xiaojii.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.common.UserContext;
import com.gdut.xiaojii.common.util.TextUtil;
import com.gdut.xiaojii.dto.req.ProfileUpdateReq;
import com.gdut.xiaojii.dto.vo.UserVO;
import com.gdut.xiaojii.entity.User;
import com.gdut.xiaojii.entity.UserProfile;
import com.gdut.xiaojii.mapper.UserMapper;
import com.gdut.xiaojii.mapper.UserProfileMapper;
import com.gdut.xiaojii.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户资料服务实现。
 *
 * <p><b>为什么不返回实体：</b>User 里有 openid / unionid / status，
 * 直接返回等于把用户的微信标识和封禁状态交给前端，是信息泄露。
 *
 * <p><b>隐私：</b>扩展字段按 {@code user_profile.privacy} 逐项过滤；
 * 昵称/头像/签名/认证/信用分始终公开；生日只用于算年龄，永不对外返回。
 *
 * @author 校集后端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final UserProfileMapper profileMapper;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STR_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Boolean>> BOOL_MAP = new TypeReference<>() {};

    /** 可逐项控制的扩展字段 */
    private static final List<String> PRIVACY_KEYS = List.of(
            "gender", "age", "major", "grade", "college", "campus",
            "tags", "availableTime", "lookingFor");

    /** 取自己的资料。含隐私字段、生日、发布数/成交数（这些只在看自己时才返回） */
    @Override
    public UserVO getSelf() {
        Long userId = UserContext.requireUserId();
        User user = requireUser(userId);
        UserVO vo = toVO(user, true);
        vo.setPostCount(0L);
        vo.setDealCount(0L);
        return vo;
    }

    /** 更新自己的资料。逐项可选，只改传了值的字段；privacy 里 false 的项对外不可见 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateSelf(ProfileUpdateReq req) {
        Long userId = UserContext.requireUserId();
        User user = requireUser(userId);

        // 只更新传了值的字段：前端可能只改头像，不能把昵称覆盖成 null
        boolean changed = false;
        if (req.getNickname() != null && !req.getNickname().isBlank()) {
            user.setNickname(req.getNickname().trim());
            changed = true;
        }
        if (req.getAvatar() != null) {
            user.setAvatar(req.getAvatar().trim());
            changed = true;
        }
        if (req.getIntro() != null) {
            user.setIntro(req.getIntro().trim());
            changed = true;
        }
        if (changed) {
            userMapper.updateById(user);
        }

        saveProfile(userId, req);
        return getSelf();
    }

    /** 看别人的资料。按对方 privacy 设置逐项过滤，被隐藏的字段返回 null */
    @Override
    public UserVO getPublic(Long userId) {
        User user = requireUser(userId);
        UserVO vo = toVO(user, false);
        vo.setPostCount(0L);
        vo.setDealCount(0L);
        return vo;
    }

    /* ==================== 资料扩展 ==================== */

    /** 保存扩展资料：没有记录就 insert，有就 update（MyBatis-Plus 默认忽略 null，清空字段需 FieldStrategy.IGNORED） */
    private void saveProfile(Long userId, ProfileUpdateReq req) {
        boolean hasProfileField = req.getGender() != null || req.getBirthday() != null
                || req.getMajor() != null || req.getGrade() != null || req.getCollege() != null
                || req.getCampus() != null || req.getTags() != null || req.getAvailableTime() != null
                || req.getLookingFor() != null || req.getPrivacy() != null;
        if (!hasProfileField) {
            return;
        }

        UserProfile p = profileMapper.selectById(userId);
        boolean isNew = p == null;
        if (isNew) {
            p = new UserProfile();
            p.setUserId(userId);
            p.setGender(0);
            p.setMajor("");
            p.setCollege("");
            p.setCampus("");
            p.setAvailableTime("");
            p.setPrivacy(toJson(defaultPrivacy()));
        }

        if (req.getGender() != null) {
            p.setGender(req.getGender());
        }
        if (req.getBirthday() != null) {
            p.setBirthday(parseBirthday(req.getBirthday()));
        }
        if (req.getMajor() != null) {
            p.setMajor(req.getMajor().trim());
        }
        if (req.getGrade() != null) {
            p.setGrade(req.getGrade() > 0 ? req.getGrade() : null);
        }
        if (req.getCollege() != null) {
            p.setCollege(req.getCollege().trim());
        }
        if (req.getCampus() != null) {
            p.setCampus(req.getCampus().trim());
        }
        if (req.getTags() != null) {
            p.setTags(toJson(req.getTags()));
        }
        if (req.getAvailableTime() != null) {
            p.setAvailableTime(req.getAvailableTime().trim());
        }
        if (req.getLookingFor() != null) {
            p.setLookingFor(toJson(req.getLookingFor()));
        }
        if (req.getPrivacy() != null) {
            Map<String, Boolean> merged = new LinkedHashMap<>(defaultPrivacy());
            merged.putAll(parseMap(p.getPrivacy()));
            merged.putAll(req.getPrivacy());
            p.setPrivacy(toJson(merged));
        }

        if (isNew) {
            profileMapper.insert(p);
        } else {
            profileMapper.updateById(p);
        }
    }

    /** User + UserProfile 合成 UserVO。参数 self 决定是否返回生日/隐私设置/统计数字 */
    private UserVO toVO(User user, boolean self) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setInitial(TextUtil.initial(user.getNickname()));
        vo.setIntro(user.getIntro());
        vo.setCreditScore(user.getCreditScore());
        vo.setVerifyStatus(user.getVerifyStatus());
        vo.setVerified(user.getVerifyStatus() != null && user.getVerifyStatus() == User.VERIFY_PASSED);
        if (user.getCreatedAt() != null) {
            vo.setJoinedAt(user.getCreatedAt().format(DAY));
        }

        UserProfile p = profileMapper.selectById(user.getId());
        if (p == null) {
            if (self) {
                vo.setGender(0);
                vo.setTags(new ArrayList<>());
                vo.setLookingFor(new ArrayList<>());
                vo.setPrivacy(defaultPrivacy());
            }
            return vo;
        }

        Map<String, Boolean> privacy = new LinkedHashMap<>(defaultPrivacy());
        privacy.putAll(parseMap(p.getPrivacy()));
        if (self) {
            vo.setPrivacy(privacy);
            vo.setBirthday(p.getBirthday() == null ? null : p.getBirthday().format(DAY));
        }

        boolean canSee = self; // 自己永远能看全部
        if (canSee || Boolean.TRUE.equals(privacy.get("gender"))) {
            vo.setGender(p.getGender() == null ? 0 : p.getGender());
        }
        if (p.getBirthday() != null && (canSee || Boolean.TRUE.equals(privacy.get("age")))) {
            vo.setAge(Period.between(p.getBirthday(), LocalDate.now()).getYears());
        }
        if (canSee || Boolean.TRUE.equals(privacy.get("major"))) {
            vo.setMajor(p.getMajor());
        }
        if (canSee || Boolean.TRUE.equals(privacy.get("grade"))) {
            vo.setGrade(p.getGrade());
        }
        if (canSee || Boolean.TRUE.equals(privacy.get("college"))) {
            vo.setCollege(p.getCollege());
        }
        if (canSee || Boolean.TRUE.equals(privacy.get("campus"))) {
            vo.setCampus(p.getCampus());
        }
        if (canSee || Boolean.TRUE.equals(privacy.get("tags"))) {
            vo.setTags(parseList(p.getTags()));
        }
        if (canSee || Boolean.TRUE.equals(privacy.get("availableTime"))) {
            vo.setAvailableTime(p.getAvailableTime());
        }
        if (canSee || Boolean.TRUE.equals(privacy.get("lookingFor"))) {
            vo.setLookingFor(parseList(p.getLookingFor()));
        }
        return vo;
    }

    /** 按 id 查用户，查不到抛 1004。所有需要用户实体的地方都走这里，避免各处重复判空 */
    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    /** 默认隐私设置：全部可见（新用户注册时写入） */
    private Map<String, Boolean> defaultPrivacy() {
        Map<String, Boolean> map = new LinkedHashMap<>();
        // 默认只公开昵称/头像/签名/认证/信用分；扩展字段默认不公开
        for (String key : PRIVACY_KEYS) {
            map.put(key, false);
        }
        return map;
    }

    /** 生日字符串 yyyy-MM-dd 转 LocalDate，格式不对返回 null */
    private LocalDate parseBirthday(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), DAY);
        } catch (Exception e) {
            throw BizException.of(ResultCode.PARAM_ERROR, "生日格式应为 yyyy-MM-dd");
        }
    }

    /** JSON 数组字符串转 List（如 tags），解析失败返回空列表 */
    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JSON.readValue(json, STR_LIST);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** JSON 转 Map（privacy 逐项可见性），解析失败返回默认值 */
    private Map<String, Boolean> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(json, BOOL_MAP);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** 对象转 JSON 字符串（存 List/Map 字段用），失败返回 null */
    private String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw BizException.of(ResultCode.PARAM_ERROR, "资料格式不正确");
        }
    }
}
