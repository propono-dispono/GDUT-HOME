package com.gdut.xiaojii.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * 【这个类是干什么的】
 * 往 Spring 容器里注册一条 SQL 拦截器链（MybatisPlusInterceptor）。
 * MyBatis 每执行一条 SQL 都会先经过这条链，链上的插件可以在 SQL 发给 MySQL 之前
 * 改写它或者直接拦下来 —— 分页、防全表更新都是这么实现的。
 *
 * 【为什么必须要有它】
 * MyBatis-Plus 的高级能力是插件化的。不注册这个 Bean，插件一律不生效，而且不会报错。
 * 最典型的现象是：明明传了分页参数，接口却把全表数据返回回来
 * （Page 的 total 是对的，但 records 是全部数据）。这类问题很难查，
 * 因为看起来像是业务代码写错了。
 *
 * 【它在整体中的位置】
 * 工作在 MyBatis 执行 SQL 的那一层，夹在 Mapper 与 MySQL 之间，对上层业务完全透明。
 * Service 调 mapper.selectPage(page, wrapper) 时，感知不到分页插件的存在。
 *
 * 【生效时机】
 * Spring 启动时扫描到 @Configuration → 执行 @Bean 方法 →
 * 把返回的拦截器注册进 MyBatis 的插件列表 → 之后每条 SQL 都经过它。
 *
 * @author 校集后端
 */
// ┌─ 【面试考点 1】MP 分页插件不注册会怎样？ ───────────────────────────
// │ Q：代码里写了 mapper.selectPage(page, wrapper)，但忘了注册
// │    PaginationInnerInterceptor，会发生什么？会报错吗？
// │ A：不报错，但分页完全失效 —— 返回全表数据。因为 MP 的分页不是"查出来再切一段"，
// │    而是靠拦截器在 SQL 发出前改写成 "LIMIT offset,size"，并额外跑一次 count。
// │    没有拦截器 = 没有 LIMIT = 全表返回。这种"静默失效"最难查：
// │    日志正常、接口 200、数据量大时才察觉。
// └─────────────────────────────────────────────────────
// 【注解作用】@Configuration：标记"这是配置类"。Spring 启动时会实例化它，
//   并把类里所有 @Bean 方法的返回值注册进容器。
//   不加 → 它只是个普通类，@Bean 方法没人调用，插件全部不生效（且不报错）。
@Configuration
public class MybatisPlusConfig {

    /**
     * 构建拦截器链。
     *
     * 方法名 mybatisPlusInterceptor 同时就是 Bean 名称，
     * 在别处可以用 @Qualifier("mybatisPlusInterceptor") 精确指定它。
     *
     * 参数：无。
     * 返回：MybatisPlusInterceptor —— 一个插件容器，
     *   可以往里 addInnerInterceptor 多个内部插件，按添加顺序依次生效。
     */
    // 【注解作用】@Bean：把方法返回值注册成 Spring Bean（Bean 名默认 = 方法名），
    //   其他组件可以注入它。不加 → 返回的只是局部变量，MyBatis 拿不到这条插件链。
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // ============================================================
        // 插件一：分页
        // ============================================================
        // 参数 DbType.MYSQL：告诉插件目标数据库类型。
        //   为什么必须传？因为分页语法各库不同 —— MySQL 用 LIMIT offset,size，
        //   Oracle 用 ROWNUM，SQL Server 用 OFFSET/FETCH。传错会生成非法 SQL。
        // 注意：这个插件必须放在链的最前面（MP 官方要求），
        //   否则多插件组合时可能生成错误的分页 SQL。
        PaginationInnerInterceptor page = new PaginationInnerInterceptor(DbType.MYSQL);

        // maxLimit：单页最大条数。
        //   参数 100L 表示：即使前端传 size=999999，也会被强行压到 100。
        //   这是"防拖库"的最后一道闸 —— size 是前端可控参数，
        //   一旦允许任意大，一个请求就能让 MySQL 扫全表并占用大量内存。
        //   边界：业务确实需要一次返回超过 100 条（如批量导出）时，
        //   应改这里的值或改走"分批拉取"，不要直接放开。
        // ┌─ 【面试考点 2】前端可控的数字参数，为什么必须设上限？ ──────────────
        // │ Q：分页的 size 是前端传的，不做限制有什么风险？
        // │ A：① 拖库：size=999999 会让 MySQL 扫全表、把结果全部读进内存，
        // │       单个请求就能打满 CPU/内存（一种低成本的 DoS）；
        // │    ② 响应体膨胀：几十万条 JSON 序列化吃满带宽和堆内存，还可能 OOM；
        // │    ③ 前端一次性渲染几十万条直接卡死。
        // │    正确做法：服务端强制封顶（本项目 100），大数据量导出走"分批拉取/流式"，
        // │    不能靠放开 size 解决。
        // └──────────────────────────────────────────────────
        page.setMaxLimit(100L);

        // overflow：页码超出总页数时怎么办。
        //   false = 返回空列表（默认推荐）
        //   true  = 自动回到第一页
        //   本项目选 false：小程序列表是无限滚动，靠 hasMore 判断有没有下一页，
        //   返回空能让"到底了"表现一致。若选 true，用户滚到最后一页会突然又看到
        //   第一页的数据，表现成"内容重复"，比空列表更难排查。
        page.setOverflow(false);

        interceptor.addInnerInterceptor(page);

        // ============================================================
        // 插件二：防全表更新 / 删除
        // ============================================================
        // 作用：UPDATE / DELETE 没有 WHERE 条件时，直接抛异常拦下来，
        //   避免"忘写 where 把整张表清空"。纯保护性质，正常业务 SQL 不受影响。
        // 易错点：确实需要更新全表时（如初始化脚本）它也会拦，
        //   那种场景应走 DBA 手工执行或临时注释掉，
        //   不要为了让业务代码通过而长期移除这个插件。
        // 顺序说明：它放在分页之后，两者处理不同语句（select vs update/delete），不会冲突。
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }

    // ┌─ 【面试考点 3】分页为什么交给插件，而不是每个查询手写 LIMIT？ ─────────
    // │ Q：自己写 "SELECT ... LIMIT ?,?" 不也能分页吗？为什么要用插件？
    // │ A：手写要同时解决四件事，每件都容易漏：
    // │    ① 改写 SQL：加 LIMIT offset,size（offset 还得算 (page-1)*size）；
    // │    ② 再查一次总数：前端要 total 才能显示总页数，等于每条分页查询写两条 SQL；
    // │    ③ 方言差异：MySQL/Oracle/SQL Server 分页语法完全不同；
    // │    ④ 参数合法性：page/size 为 0、负数、超大值都要校验。
    // │    插件把这四件事统一收口，业务只写一条 selectPage，换库只改 DbType。
    // │    —— 这是"横切关注点用插件收口"的典型例子，可引申到拦截器/过滤器。
    // └──────────────────────────────────────────────────────
}
