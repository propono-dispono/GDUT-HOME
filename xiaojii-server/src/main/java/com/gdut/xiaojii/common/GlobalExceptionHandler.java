package com.gdut.xiaojii.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * 作用：把各种异常统一转换成 Result，让前端永远只处理一种结构。
 * 这样 Controller / Service 里就不用写 try-catch，业务代码保持干净。
 *
 * 三层分工：
 *   ① 可预期的失败 → Service 抛 BizException，这里原样透出 code + msg
 *   ② 参数校验失败 → 这里统一收集字段错误，返回 400
 *   ③ 未预料异常   → 这里兜底记日志，对外只回"服务器开小差了"（不暴露堆栈）
 *
 * 【它是怎么"接住"异常的】
 *   @RestControllerAdvice 让这个类成为"全局切面"：任何 Controller 抛出的异常，
 *   在返回给前端之前都会先经过这里。方法上的 @ExceptionHandler(XxxException.class)
 *   声明"我能处理 XxxException"。
 *
 * 【同时匹配多个 handler 时选谁】
 *   Spring 按"最具体的类型"匹配。例如抛的是 BizException，
 *   它既匹配 BizException 也匹配 Exception，但会选 BizException ——
 *   所以兜底的 Exception 方法不会抢走具体的处理逻辑。
 *   这也解释了为什么兜底方法一定写在最后、用最宽的 Exception。
 *
 * 【为什么 HTTP 状态码恒为 200】
 *   本项目所有响应都是 200 + body 里带 code。业务失败用 code 表达，
 *   前端只需一个拦截器判 code===0。代价是监控系统看不出错误率，
 *   需要额外按 body 里的 code 统计 —— 这是已知取舍。
 *
 * @author 校集后端
 */
// 【写法】@Slf4j 生成 log 字段
// 【为什么】这里要记三种日志：业务异常 warn、系统异常 error（带堆栈）、路径不存在 debug
@Slf4j
// 【写法】@RestControllerAdvice 而不是 @ControllerAdvice
// 【为什么】@RestControllerAdvice = @ControllerAdvice + @ResponseBody。
//   加了它，方法返回值才会被序列化成 JSON 写进响应体；
//   若只用 @ControllerAdvice，返回值会被当成"视图名"去找页面模板，前端拿到的是 404。
// 【另一种】在每个 Controller 里写 try-catch —— 19 个类 × 每个方法，重复且容易漏
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：最常见的一类，属于"用户操作不对"，不是系统故障。
     * 只记 warn 日志，不打堆栈（否则日志会被正常的业务拒绝刷屏）。
     */
    // 【写法】@ExceptionHandler(BizException.class)
    // 【为什么】声明"我能接住 BizException"。Service 里 throw 之后不用管，
    //   由这里统一转成 {code, msg}，Controller 完全不用写 try-catch。
    // 【注意】这里必须用 e.getCode() 而不是固定码 —— 异常自带错误码，原样还原
    @ExceptionHandler(BizException.class)
    /** 处理biz */
    public Result<Void> handleBiz(BizException e, HttpServletRequest req) {
        // 【写法】log.warn 且不打堆栈（不传 e 作为最后一个参数）
        // 【为什么】"token 过期""帖子不存在"这类是正常业务流，每天可能上万次；
        //   如果每个都打完整堆栈，日志会被撑爆，真正的系统故障反而被淹没。
        //   要排查时靠 method + URI + message 已经够了。
        log.warn("[业务异常] {} {} -> {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * @RequestBody 上的 @Valid 校验失败。
     * 只返回第一条错误，前端直接 toast 即可（不需要一次性弹一堆）。
     */
    // 【写法】单独处理 MethodArgumentNotValidException
    // 【为什么】它和下面的 BindException、ConstraintViolationException 是**三条不同的链**，
    //   触发条件完全不同（见各自注释）。漏掉任何一条，那类失败就会掉进兜底变成 500。
    // 【代价】三个方法有重复的"收集错误信息"逻辑；换来的是每个场景都能给出准确的提示
    @ExceptionHandler(MethodArgumentNotValidException.class)
    /** 处理valid */
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        // getFieldErrors() 拿到所有校验失败的字段，map 取出各自的 message，用"；"拼起来
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        // 注意：这里用的是 PARAM_ERROR(400)，不是业务码 ——
        // 因为"参数不合法"属于调用方问题，前端应按 400 统一处理
        return Result.fail(ResultCode.PARAM_ERROR, msg);
    }

    /** 表单/查询参数绑定失败（非 @RequestBody 场景） */
    // 【写法】BindException 与上面的 MethodArgumentNotValidException 分开处理
    // 【为什么】两者是父子关系（后者继承前者），但**触发场景不同**：
    //   · @RequestBody + @Valid          → MethodArgumentNotValidException
    //   · GET 的对象参数（如 PostQueryReq）→ BindException
    //   Spring 选最具体的 handler，所以两个都写才能各走各的逻辑。
    @ExceptionHandler(BindException.class)
    /** 处理bind */
    public Result<Void> handleBind(BindException e) {
        // getFieldErrors() 直接拿字段错误（BindException 不像上面那样包一层 BindingResult）
        String msg = e.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return Result.fail(ResultCode.PARAM_ERROR, msg);
    }

    /** 必填查询参数缺失 */
    // 【为什么单独处理】不处理会掉进兜底 500，但其实只是"调用方少传了一个参数"，
    // 应该返回 400 并告诉他缺哪个 —— e.getParameterName() 就是那个名字
    @ExceptionHandler(MissingServletRequestParameterException.class)
    /** 处理missingparam */
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.fail(ResultCode.PARAM_ERROR, "缺少参数：" + e.getParameterName());
    }

    /** 请求体 JSON 格式错误 */
    // 【为什么单独处理】前端传了非法 JSON（比如少个引号、多一个逗号），
    // Jackson 解析失败抛这个异常。回 400 比回 500 更准确，且能提示"格式错误"
    @ExceptionHandler(HttpMessageNotReadableException.class)
    /** 处理unreadable */
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        return Result.fail(ResultCode.PARAM_ERROR, "请求体格式错误");
    }

    /** 请求方法不支持（如把 POST 写成 GET） */
    // 【为什么单独处理】语义是 405 而不是 500；且把"实际用了什么方法"回显出来，方便排查
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    /** 处理method */
    public Result<Void> handleMethod(HttpRequestMethodNotSupportedException e) {
        return Result.fail(ResultCode.METHOD_NOT_ALLOWED, "不支持 " + e.getMethod() + " 请求");
    }

    /**
     * 路径不存在。
     * 不处理的话会被兜底成 500 —— Spring Boot 3.2 对静态资源未命中抛 NoResourceFoundException，
     * 前端会误以为服务崩了。
     */
    // 【写法】显式处理 NoResourceFoundException，返回 404
    // 【为什么】Spring Boot 3.2 改了行为：路径找不到时不再静默返回 404 页面，
    //   而是抛 NoResourceFoundException。如果不接，它会掉进兜底变成 500，
    //   于是"接口写错路径"看起来像"服务器崩了"，排查方向直接跑偏。
    // 【另一种】不处理，靠兜底 —— 前端体验和排错都会变差。
    @ExceptionHandler(NoResourceFoundException.class)
    /** 处理notfound */
    public Result<Void> handleNotFound(NoResourceFoundException e, HttpServletRequest req) {
        // 用 debug 级别：路径不存在多半是前端拼错或爬虫扫描，不需要 warn 级别噪音
        log.debug("[路径不存在] {} {}", req.getMethod(), req.getRequestURI());
        return Result.fail(ResultCode.NOT_FOUND, "接口不存在：" + req.getRequestURI());
    }

    /**
     * 路径变量 / 查询参数类型不匹配，例如 GET /app/user/abc 里的 "abc" 转 Long 失败。
     * 属于调用方参数错误，不能回 500。
     */
    // 【为什么单独处理】@PathVariable Long id 传了 "abc" 时，Spring 在"类型转换"阶段就失败，
    // 根本没进 Controller 方法。不接的话会变成 500，而实际是调用方传错了参数
    // e.getName() 能告诉前端是哪个参数出的问题
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    /** 处理typemismatch */
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.fail(ResultCode.PARAM_ERROR, "参数 " + e.getName() + " 类型不正确");
    }

    /**
     * 方法参数上的校验注解失败（@Validated 打在 Controller 类上、参数直接写 @Min/@NotBlank 时走这里）。
     * 与 @RequestBody 的 MethodArgumentNotValidException 是两条不同的链路，都必须处理。
     */
    // 【写法】三条校验链路里的第三条：ConstraintViolationException
    // 【为什么】NoticeController 那样"类上 @Validated + 参数上 @Min(1)"时，
    //   校验由 AOP 代理完成，失败抛的是这个异常，和 @RequestBody 那条完全无关。
    //   本项目 14 个 Controller 里用到 @Valid，两条链路都会真实发生，所以必须都接。
    @ExceptionHandler(ConstraintViolationException.class)
    /** 处理constraint */
    public Result<Void> handleConstraint(ConstraintViolationException e) {
        // getConstraintViolations() 是 Set，可能包含重复消息，所以 distinct 去重
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .distinct()
                .collect(Collectors.joining("；"));
        return Result.fail(ResultCode.PARAM_ERROR, msg);
    }

    /** 上传文件超过 spring.servlet.multipart 限制 */
    // 【为什么用 FILE_TOO_LARGE(1061) 而不是 PARAM_ERROR】
    // 上传超限是文件场景的专属问题，给专属码前端才能提示"请压缩后再上传"，
    // 而不是笼统的"参数不合法"
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    /** 处理maxupload */
    public Result<Void> handleMaxUpload(MaxUploadSizeExceededException e) {
        return Result.fail(ResultCode.FILE_TOO_LARGE);
    }

    /**
     * 唯一索引冲突（并发下的兜底）。
     * 典型场景：同一学号被两个请求同时提交、同一对用户同时建会话、重复提交评价。
     * 业务层应优先用查询做前置判断，这里只是最后一道防线，避免把 SQL 异常暴露成 500。
     */
    // 【写法】接 DuplicateKeyException，返回"请刷新后重试"
    // 【为什么】这是**并发场景**的兜底：两个请求同时"先查再插"，
    //   查的时候都发现不存在，插入时第二个撞上唯一索引。
    //   前置查询无法根治（查和插之间有间隙），唯一索引才是最终保证。
    // 【代价】用户会看到"操作过于频繁或数据已存在"，而不是精确原因 ——
    //   因为异常里只有索引名，没有业务语义；要精确提示需在 Service 里先查一次。
    @ExceptionHandler(DuplicateKeyException.class)
    /** 处理duplicatekey */
    public Result<Void> handleDuplicateKey(DuplicateKeyException e, HttpServletRequest req) {
        // warn 级别：这类冲突是"并发导致的正常异常"，不是 bug，但需要留痕观察频率
        log.warn("[唯一键冲突] {} {} -> {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return Result.fail(ResultCode.BIZ_ERROR, "操作过于频繁或数据已存在，请刷新后重试");
    }

    /**
     * 兜底：未预料的异常。
     * ⚠️ 对外只给一句通用文案，绝不把堆栈/SQL 暴露给前端 —— 那是安全漏洞。
     */
    // 【写法】@ExceptionHandler(Exception.class) —— 最宽的异常类型，放在最后一个方法
    // 【为什么】它是"漏网之鱼"的最后一层：任何没被上面 11 个方法接住的异常都到这里，
    //   保证接口永远返回统一结构，而不是 Spring 默认的白色错误页/500 堆栈。
    // 【关键】log.error 传了 e（会打完整堆栈）给运维看；返回给前端的只有一句固定文案。
    //   如果这里 return e.getMessage()，SQL、类名、文件路径就全泄露了 —— 典型的信息泄露漏洞。
    @ExceptionHandler(Exception.class)
    /** 处理other */
    public Result<Void> handleOther(Exception e, HttpServletRequest req) {
        log.error("[系统异常] {} {}", req.getMethod(), req.getRequestURI(), e);
        return Result.fail(ResultCode.SERVER_ERROR);
    }
}
