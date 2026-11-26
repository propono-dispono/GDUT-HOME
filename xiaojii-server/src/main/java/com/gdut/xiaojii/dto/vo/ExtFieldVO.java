package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 扩展字段键值对（详情页的"取件地 / 送达地 / 截止时间"这类行）。
 *
 * 【它是干什么的】
 * 六条业务线的扩展字段完全不同，但详情页的展示形式一样：
 * 都是一行"标签 + 值"。所以后端不传"pickupAddr=菜鸟驿站"这种字段名，
 * 而是传一个列表：
 *   [{k:"取件地", v:"菜鸟驿站"}, {k:"送达地", v:"3 栋 502"}, {k:"最晚送达", v:"11-03 18:00"}]
 * 前端只写一个循环按顺序渲染，不需要知道当前是哪条业务线。
 *
 * 【好处】新增业务线时前端代码一行不用改（不用重新提审小程序）。
 * 【代价】字段值全是字符串，失去类型 —— 前端没法对某个字段做特殊处理（如给价格标红）。
 *
 * @author 校集后端
 */
// 【写法】@Data 生成 getter/setter
// 【为什么】Jackson 序列化靠 getter；反序列化（虽然这个类主要是出参）靠 setter
@Data
// 【写法】@NoArgsConstructor 生成无参构造器
// 【为什么】Jackson 反序列化时需要有参以外的"空壳"对象才能填字段；
//   没有无参构造器，某些反序列化场景会报 cannot construct instance
@NoArgsConstructor
// 【写法】@AllArgsConstructor 生成全参构造器
// 【为什么】后端组装时一行就能建好：new ExtFieldVO("取件地", "菜鸟驿站")
//   否则要 new + 两次 set，Service 里会多出一堆样板代码
@AllArgsConstructor
// 【写法】@Schema 描述模型
// 【为什么】只影响接口文档展示
@Schema(description = "扩展字段")
public class ExtFieldVO {

    /** 字段名 */
    // 【为什么字段名叫 k 而不是 key 或 label】
    //   这是纯传输对象，一条详情页要传 3~4 个，字段名短能省一点 JSON 体积；
    //   而且前端直接 <text>{{item.k}}</text> 用着也短。
    // 【代价】可读性差 —— 光看 JSON 不知道 k 是什么，必须配合注释或文档。
    //   如果是给别人调用的开放 API，不建议这么命名
    private String k;

    /** 字段值（已格式化） */
    // 【"已格式化"是什么意思】后端传的已经是展示态字符串，不是原始值：
    //   时间 → "11-03 18:00"（不是 ISO 时间戳）
    //   布尔 → "需垫付"（不是 true）
    //   金额 → "15"（不是 15.00）
    //   这样前端拿到就能直接显示，不用为每条业务线各写一套格式化逻辑
    private String v;
}
