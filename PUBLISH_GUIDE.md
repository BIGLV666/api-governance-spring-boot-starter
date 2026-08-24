# Maven 中央仓库发布指南

本文档说明如何将 `api-governance-spring-boot-starter` 发布到 Maven 中央仓库。

---

## 一、前置准备（首次发布需要）

### 1. 注册 Sonatype OSSRH 账号

1. 访问 [https://issues.sonatype.org](https://issues.sonatype.org)
2. 注册账号（记住用户名和密码，后续配置需要）
3. 创建一个 Jira Issue 申请发布权限：
   - **Project**: Community Support - Open Source Project Repository Hosting (OSSRH)
   - **Issue Type**: New Project
   - **Group Id**: `io.github.yourname`（替换为你的 GitHub 用户名）
   - **Project URL**: `https://github.com/yourname/api-governance-spring-boot-starter`
   - **SCM URL**: `https://github.com/yourname/api-governance-spring-boot-starter.git`
   - **Description**: 简单描述项目用途
4. 提交后等待 1-2 个工作日，工作人员会验证你的 GitHub 仓库所有权（要求在仓库里创建一个包含 Issue ID 的文件或 tag）
5. 验证通过后，你就拥有 `io.github.yourname` 下所有 artifactId 的发布权限

### 2. 生成 GPG 密钥对

中央仓库要求所有发布的 jar 必须 GPG 签名。

```bash
# 生成密钥（交互式，按提示输入名字、邮箱、密码）
gpg --gen-key

# 查看密钥 ID（输出类似：pub   rsa3072/ABCD1234 ...，后面的 ABCD1234 就是 KEY_ID）
gpg --list-keys

# 发布公钥到服务器（替换 <KEY_ID> 为上一步的 ID）
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>

# 导出私钥（用于 GitHub Actions，替换 <KEY_ID>）
gpg --armor --export-secret-keys <KEY_ID> | base64 > gpg-private-key.txt
```

### 3. 配置 GitHub Secrets

在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 添加三个密钥：

| Secret Name        | Value                                      |
|--------------------|--------------------------------------------|
| `OSSRH_USERNAME`   | Sonatype OSSRH 用户名                      |
| `OSSRH_TOKEN`      | Sonatype OSSRH 密码                        |
| `GPG_PRIVATE_KEY`  | `gpg-private-key.txt` 文件的全部内容（base64） |
| `GPG_PASSPHRASE`   | 生成 GPG 密钥时设置的密码                  |

---

## 二、修改 pom.xml 的 TODO 项

在 `pom.xml` 中搜索 `TODO`，替换以下占位符：

```xml
<!-- 1. groupId -->
<groupId>io.github.yourname</groupId>  <!-- 改为你的 GitHub 用户名 -->

<!-- 2. url -->
<url>https://github.com/yourname/api-governance-spring-boot-starter</url>

<!-- 3. developer 信息 -->
<developer>
    <id>yourname</id>
    <name>Your Name</name>
    <email>your.email@example.com</email>
    <url>https://github.com/yourname</url>
</developer>

<!-- 4. scm 信息 -->
<scm>
    <connection>scm:git:git://github.com/yourname/api-governance-spring-boot-starter.git</connection>
    <developerConnection>scm:git:ssh://github.com:yourname/api-governance-spring-boot-starter.git</developerConnection>
    <url>https://github.com/yourname/api-governance-spring-boot-starter/tree/main</url>
</scm>
```

---

## 三、本地验证（可选）

### 方式 A：跳过签名构建（开发环境默认）

```bash
# dev profile 默认启用，自动跳过 GPG 签名
mvn clean install

# 或显式指定
mvn clean install -P dev
```

### 方式 B：启用签名构建（验证发布流程）

需要本地有 GPG 密钥：

```bash
# 使用 release profile
mvn clean install -P release

# 如果 GPG agent 未启动，手动输入密码
mvn clean install -P release -Dgpg.passphrase="你的GPG密码"
```

---

## 四、发布到中央仓库

### 自动发布（推荐）

已配置 GitHub Actions 自动发布（`.github/workflows/maven-publish.yml`）：

```bash
# 1. 确保代码已推送到 GitHub
git add .
git commit -m "Ready for release"
git push origin main

# 2. 打一个版本 tag（注意：必须以 v 开头）
git tag v1.0.0
git push origin v1.0.0

# 3. GitHub Actions 自动触发：
#    - 编译、测试
#    - GPG 签名
#    - 上传到 Sonatype OSSRH
#    - 自动 close 并 release 到中央仓库
#    - 创建 GitHub Release
```

### 手动发布（本地）

如果不用 GitHub Actions：

```bash
# 1. 确保 ~/.m2/settings.xml 配置了 OSSRH 账号
# <servers>
#   <server>
#     <id>ossrh</id>
#     <username>你的用户名</username>
#     <password>你的密码</password>
#   </server>
# </servers>

# 2. 执行发布（需要本地 GPG 密钥）
mvn clean deploy -P release -Dgpg.passphrase="你的GPG密码"

# 3. 登录 https://s01.oss.sonatype.org/ 
#    → Staging Repositories → 找到你的构件 → Close → Release
```

---

## 五、发布后验证

1. **等待同步**：首次发布约 2 小时后，后续版本约 15 分钟后，Maven 中央仓库搜索可见（[search.maven.org](https://search.maven.org)）
2. **测试使用**：新建一个项目，在 `pom.xml` 添加依赖：
   ```xml
   <dependency>
       <groupId>io.github.yourname</groupId>
       <artifactId>api-governance-spring-boot-starter</artifactId>
       <version>1.0.0</version>
   </dependency>
   ```
3. **运行 `mvn dependency:resolve`**，确认能从中央仓库下载

---

## 六、常见问题

### Q1: GPG 签名失败？
```
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-gpg-plugin:3.1.0:sign
```
**解决**：
- 确认 GPG 密钥已生成：`gpg --list-keys`
- 本地开发时跳过签名：`mvn install -Dgpg.skip=true` 或使用 `dev` profile
- 发布时确保密码正确：`mvn deploy -P release -Dgpg.passphrase="正确密码"`

### Q2: 401 Unauthorized？
```
[ERROR] Failed to deploy ... Return code is: 401, ReasonPhrase: Unauthorized
```
**解决**：检查 `~/.m2/settings.xml` 或 GitHub Secrets 中的 OSSRH 用户名/密码是否正确

### Q3: 构件已存在？
```
[ERROR] Repository does not allow updating assets: ...
```
**解决**：中央仓库不允许覆盖已发布的版本，必须升级版本号（如 `1.0.0` → `1.0.1`）

### Q4: Javadoc 生成失败？
```
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-javadoc-plugin
```
**解决**：已配置 `<doclint>none</doclint>` 禁用严格检查。如果还报错，检查是否有语法错误的注释

---

## 七、版本管理建议

- **开发版本**：`0.0.1-SNAPSHOT`（SNAPSHOT 版本可以反复覆盖，不发布到中央仓库）
- **正式版本**：`1.0.0`、`1.1.0`（发布后不可修改）
- **语义化版本**：
  - **主版本号**：不兼容的 API 变更
  - **次版本号**：向后兼容的功能新增
  - **修订号**：向后兼容的问题修复

---

## 八、后续维护

发布新版本只需要：

```bash
# 1. 修改 pom.xml 的 <version>
<version>1.1.0</version>

# 2. 推送代码并打 tag
git add .
git commit -m "Release v1.1.0: 新增 XXX 功能"
git push origin main
git tag v1.1.0
git push origin v1.1.0

# 3. GitHub Actions 自动发布
```

首次审核通过后，后续版本无需人工审核，自动同步到中央仓库。
