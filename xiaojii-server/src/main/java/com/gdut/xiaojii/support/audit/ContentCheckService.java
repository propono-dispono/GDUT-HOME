package com.gdut.xiaojii.support.audit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.common.enums.BizType;
import com.gdut.xiaojii.entity.SensitiveWord;
import com.gdut.xiaojii.mapper.SensitiveWordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 内容审核（敏感词）。
 *
 * 【它在链路里的位置】
 * PostServiceImpl.create 的第 6 段调用它，位置在"校验必填"之后、"插主表"之前。
 * 也就是说：命中敏感词的内容**根本不会入库**，不会产生帖子记录，也不占审核队列。
 *
 * 【为什么不直接用"包含即拦截"】
 * 校园场景里"代取外卖"是合法跑腿需求，而"售卖外卖"是禁止项。
 * 一刀切会把大量正常跑腿单误杀 —— 用户会觉得平台莫名其妙。
 * 所以词库分两级：
 *   level=1  命中即拦截（代写/代考/刷单/网贷…），不分业务线
 *   level=2  只在特定业务线拦截（餐饮类词仅在**拼单**业务线拦截，跑腿放行）
 *
 * 【组合词机制】
 * 库里存 "代取+外卖"，表示"两个片段**同时出现**"才命中。
 * 用于区分"代取外卖"（合法跑腿）和"外卖代取代买"（可能是倒卖）。
 * 这条机制目前用得少，但扩展性留住了。
 *
 * 【⚠️ 一个已知的性能问题】
 * check() 每次调用都会 **selectList 全表查一遍词库**（第 49-50 行），没有缓存。
 * 词库几百条时无明显影响；如果涨到几万条，每次发帖都会拖慢。
 * 正确做法是把词库加载到内存（启动时加载 + 定时刷新），或者用 Trie 树。
 * 现在没做，属于"够用就先不做"的取舍 —— 但读代码的人要知道这里是个瓶颈点。
 *
 * @author 校集后端
 */
// @Slf4j：命中拦截时打日志（便于观察词库命中率、调整词条）
@Slf4j
// @Service：注册成 Bean，PostServiceImpl 通过构造器注入使用
@Service
// @RequiredArgsConstructor：为 final 字段生成构造器
@RequiredArgsConstructor
public class ContentCheckService {

    // 敏感词表的数据访问：sensitive_word（字段 word / level / enabled）
    private final SensitiveWordMapper sensitiveWordMapper;

    /**
     * 校验文本。
     *
     * @throws BizException 命中拦截级词条时抛 1095
     */
    // 【签名说明】
    //   text    —— 要校验的文本（调用方已把标题、正文、标签拼在一起传进来）
    //   bizType —— 当前业务线，决定 level=2 的词要不要拦
    // 【返回 void】校验通过就正常返回；不通过就抛异常 —— 调用方不需要判返回值
    /** 校验 */
    public void check(String text, BizType bizType) {
        // 第 1 段：空文本直接放行。
        // 【为什么】tags 允许为空，拼起来可能是 "标题 正文 " 带尾空格；
        //   空内容没东西可查，直接返回比走一遍查库更省
        if (text == null || text.isBlank()) {
            return;
        }
        // 第 2 段：统一转小写。
        // 【为什么】词库里存的是小写（如 "weixin"），用户可能写 "WeiXin"；
        //   统一转小写后 contains 匹配就能忽略大小写。
        // 【代价】中文不受影响，但英文词的大小写形态丢失了（一般无所谓）
        String lower = text.toLowerCase();
        // 第 3 段：查出所有启用的词条。
        // 【注意这里就是性能隐患】每次调用都全表查一次，没有缓存。
        //   enabled=1 表示该词条当前生效（管理员可以临时禁用某词，而不是删除）
        List<SensitiveWord> words = sensitiveWordMapper.selectList(
                Wrappers.<SensitiveWord>lambdaQuery().eq(SensitiveWord::getEnabled, 1));
        // 词库为空（还没配置）时直接放行 —— 不能因为没词库就让所有发帖都失败
        if (words.isEmpty()) {
            return;
        }

        // 第 4 段：逐条比对。
        // 【为什么用 for 循环而不是 Stream】循环里要 throw（提前退出），
        //   用 Stream 写起来反而绕；而且命中就退出，循环更直观
        for (SensitiveWord w : words) {
            String word = w.getWord();
            // 跳过脏数据（词条为空）
            if (word == null || word.isBlank()) {
                continue;
            }

            // 第 5 段：判断是否命中，分两种情况。
            // 【组合词】库里写成 "代取+外卖" 时，用 + 切分，要求**每个片段都出现**才算命中。
            //   allMatch 会短路：第一个片段不出现就直接返回 false，不会白跑后面的
            // 【普通词】直接判断整段是否包含
            boolean hit = word.contains("+")
                    // 组合词：要求每个片段都出现
                    ? Arrays.stream(word.split("\\+")).allMatch(lower::contains)
                    : lower.contains(word.toLowerCase());
            if (!hit) {
                continue;
            }

            // 第 6 段：命中了 —— 按 level 决定拦不拦。
            // level=1：不分业务线，直接拒绝（"代写论文""代考"这类没有任何合法场景）
            if (w.getLevel() != null && w.getLevel() == 1) {
                log.info("[内容拦截] biz={} 命中词={}", bizType, word);
                // 【为什么要告诉用户命中了哪个词】不告诉的话用户会反复试、反复失败；
                //   告诉了他能自己改。这也是合规要求（要让用户知道为什么被拒）
                throw BizException.of(ResultCode.CONTENT_ILLEGAL, "内容包含违规词「" + word + "」，请修改后重试");
            }
            // level=2：只对拼单生效（外卖/熟食类），跑腿"代取外卖"不拦
            // 【这是整个类最核心的一行判断】同一个词在不同业务线下结论相反：
            //   "外卖" 出现在拼单里 = 违规（拼单不该拼吃的到宿舍，有食品安全风险）；
            //   "外卖" 出现在跑腿里 = 合法（就是帮人取快递/取餐）
            if (w.getLevel() != null && w.getLevel() == 2 && bizType == BizType.GROUPBUY) {
                log.info("[内容拦截] 拼单命中餐饮类词={}", word);
                throw BizException.of(ResultCode.CONTENT_ILLEGAL,
                        "拼单不支持外卖/熟食类商品，请调整后重试");
            }
        }
        // 走到这里说明所有词条都没命中（或命中的都是 level=2 且业务线不是拼单）→ 放行
    }
}
