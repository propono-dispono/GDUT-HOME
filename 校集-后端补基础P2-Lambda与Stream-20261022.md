# 补基础 P2 · Lambda 与 Stream（只讲这个项目里真正出现的写法）

> **本讲只解决一件事**：让你能读懂 `PostServiceImpl.java` 第 350–366 行那 7 行代码。
>
> **为什么先学这个**：全项目 **33 处** `stream()`，其中 `PostServiceImpl` 7 处、`ChatServiceImpl` 6 处、
> `AdminAuditServiceImpl` 6 处。不认这个写法，第 5、7、10 讲会全程卡住。
>
> **本讲要打开的文件**：
> - `src/main/java/com/gdut/xiaojii/service/impl/PostServiceImpl.java` 第 **346–401** 行（必读，核心教材）
> - `src/main/java/com/gdut/xiaojii/common/GlobalExceptionHandler.java` 第 **57–59** 行（另一种用法，扫一眼）

---

## 一、先看"没有 Stream 的时候怎么写"

需求：从一堆帖子里，把发帖人的 userId 都取出来，去重。

**传统写法**：

```java
Set<Long> userIds = new HashSet<>();
for (Post p : posts) {
    userIds.add(p.getUserId());
}
```

**Stream 写法**（就是项目里那行）：

```java
Set<Long> userIds = posts.stream().map(Post::getUserId).collect(Collectors.toSet());
```

**两段代码干的是同一件事。** 第二段不是"更高级"，只是"更紧凑"。**理解时请在心里翻译回第一种。**

---

## 二、Stream 就是一条传送带

```
原料（List<Post>）
   ↓
[工位1 filter]  筛子：不合格的扔掉
   ↓
[工位2 map]     加工机：一个一个变成另一样东西
   ↓
[工位3 collect] 装箱：收成 List / Set / Map
```

三个动作，记住这三个就够：

| 动作 | 白话 | 例子 |
|---|---|---|
| `.filter(x -> 条件)` | **留下**满足条件的 | `.filter(p -> p.getStatus() == 1)` |
| `.map(x -> 转换)` | 把每个**变成**另一样 | `.map(Post::getId)` |
| `.collect(...)` | 收成集合 | `.collect(Collectors.toList())` |
| `.toList()` | 收成 List 的简写（Java 16+） | `.map(Post::getId).toList()` |

---

## 三、两个看着奇怪的写法

### ① `Post::getUserId`

这是 **方法引用**，等价于 `p -> p.getUserId()`。

| 写法 | 意思 |
|---|---|
| `p -> p.getUserId()` | 给我一个 `p`，我返回 `p.getUserId()` |
| `Post::getUserId` | 同上，只是省略了参数名（编译器知道参数是 Post） |

**什么时候能用 `::`**：当 lambda 只是"调用某个方法、不做别的"时。`p -> p.getUserId()` 只干了一件事 → 可以写成 `Post::getUserId`。

### ② `Function.identity()`

等价于 `u -> u`，意思是"**原样返回**，不做任何加工"。

出现在 `Collectors.toMap(User::getId, Function.identity())` 里：
- 第一个参数 `User::getId` = **key 怎么取**
- 第二个参数 `Function.identity()` = **value 就放整个对象本身**

结果：`{ 1: User对象, 2: User对象, ... }`

---

## 四、逐行拆核心代码（PostServiceImpl.java 第 350–366 行）

```java
350  Set<Long> userIds = posts.stream().map(Post::getUserId).collect(Collectors.toSet());
351  Map<Long, User> userMap = userMapper.selectBatchIds(userIds).stream()
352          .collect(Collectors.toMap(User::getId, Function.identity()));
```

**第 350 行**：从这页帖子里取出所有 `userId`，装进 Set。
→ 用 `Set` 而不是 `List`，因为**同一个人发了 3 条帖子，只需要查一次**（Set 自动去重）。

**第 351 行前半**：`userMapper.selectBatchIds(userIds)` —— 一条 SQL 查回所有用户。
→ 生成的 SQL 形如 `SELECT * FROM user WHERE id IN (1,5,9)`，**一次查完**。

**第 351 行后半 + 352 行**：把查回来的 `List<User>` 转成 `Map<id, User>`。
→ 为什么要转 Map？因为**列表里要用 `userMap.get(post.getUserId())` 直接取**，O(1)；
如果留着 List，就得每次循环一遍去找，O(n)。

```java
354  List<Long> lostIds = posts.stream()
355          .filter(p -> BizType.LOST.name().equals(p.getBizType()))   // 只留失物帖
356          .map(Post::getId).toList();                                 // 取 id
357  Map<Long, PostLost> lostMap = lostIds.isEmpty() ? Collections.emptyMap()
358          : lostMapper.selectBatchIds(lostIds).stream()
359          .collect(Collectors.toMap(PostLost::getPostId, Function.identity()));
```

**354–356 行**：先 `filter`（只留失物），再 `map`（取 id）——**两个工位连着走**，这就是传送带。
**357 行**：如果一条失物帖都没有，就不查库，直接给个空 Map（**避免 `IN ()` 这种非法 SQL**）。

**368–399 行**是普通 `for` 循环，把数据一条条填进 `PostItemVO`。
注意第 **378** 行：`User u = userMap.get(p.getUserId());` —— 这就是 Map 的用处。

---

## 五、为什么这么写：N+1 问题（本讲最值钱的部分）

假设一页 20 条帖子，20 个不同的作者。

**错误写法**（在循环里查库）：

```java
for (Post p : posts) {
    User u = userMapper.selectById(p.getUserId());   // 每次循环查一次库
}
```

→ 1 次查帖子 + **20 次**查用户 = **21 次数据库往返**。这就是 **N+1**。

**正确写法**（项目里的写法）：

```
1 次查帖子 + 1 次查用户 = 2 次
```

| 写法 | 20 条帖子 | 100 条帖子 |
|---|---|---|
| 循环里查 | 21 次 | 101 次 |
| 批量查 + 转 Map | **2 次** | **2 次** |

**结论**：学 Stream 不是为了代码好看，是为了**把 N+1 变成 2 次查询**。面试必问。

---

## 六、这个模式在项目里出现了 6 次（全部位置）

同样的"取 ID → 批量查 → 转 Map"三段式，出现在：

| 文件 | 行号 |
|---|---|
| `service/impl/PostServiceImpl.java` | 350–352、354–359、361–366 |
| `service/impl/ChatServiceImpl.java` | 114–117 |
| `service/impl/AdminAuditServiceImpl.java` | 87–88、198–199 |
| `service/impl/FavoriteServiceImpl.java` | 96–104 |
| `service/impl/ReportServiceImpl.java` | 115–116 |
| `service/impl/AdminConsoleServiceImpl.java` | 103–106 |

**看到这个形状，就说明作者在解决 N+1**。这是你读这份代码的"指纹"。

---

## 七、三个坑

| 坑 | 现象 | 原因 |
|---|---|---|
| **Stream 只能用一次** | 抛 `IllegalStateException: stream has already been operated upon or closed` | 流水线消费完就废了，要重新 `.stream()` |
| **`toList()` 不可变** | 往里 `add` 抛 `UnsupportedOperationException` | Java 16+ 的 `toList()` 返回不可变列表，要改就 `new ArrayList<>(...)` |
| **`toMap` 键重复会炸** | 抛 `IllegalStateException: Duplicate key` | 所以项目里先 `distinct()` 或保证键唯一 |

---

## 八、另一种用法（扫一眼就够了）

`common/GlobalExceptionHandler.java` 第 **57–59** 行：

```java
String msg = e.getBindingResult().getFieldErrors().stream()
        .map(FieldError::getDefaultMessage)
        .collect(Collectors.joining("；"));       // 把多条错误拼成一句话
```

这里装箱不用 List，用 `joining("；")` 拼字符串。**同一个传送带，换个装箱方式。**

---

## 九、本讲验收（答完回我"P2 学完了"）

**Q1**：`posts.stream().map(Post::getUserId).collect(Collectors.toSet())` 这行得到什么？
为什么用 `Set` 不用 `List`？（看 `PostServiceImpl.java:350`）

**Q2**：如果第 351 行改成在 `for` 循环里对每条帖子调一次 `userMapper.selectById`，
一页 20 条帖子要查几次库？现在的写法是几次？（看 `PostServiceImpl.java:346-401`）

**Q3**：`Collectors.toMap(User::getId, Function.identity())` 里，
`User::getId` 和 `Function.identity()` 分别决定 Map 的什么？（看 `PostServiceImpl.java:352`）

---

## 十、动手动作（10 分钟）

打开 `PostServiceImpl.java` 第 **350** 行，把这一行在心里（或纸上）改写成 `for` 循环版本：

```java
Set<Long> userIds = new HashSet<>();
for (Post p : posts) {
    userIds.add(p.getUserId());
}
```

确认两者等价后，再往下看第 354–356 行，**用一句话说出它筛掉了什么**。

---

*补基础 P2 · 2026-10-22*
