# Maven Central 发布指南

本项目的正式发布坐标为：

```text
io.github.biglv666:api-governance-spring-boot-starter:<version>
```

发布通过 **Sonatype Central Portal** 完成；不要再使用已退役的 OSSRH 或 Nexus Staging 页面。

---

## 1. 首次发布前准备

### 1.1 验证 namespace

1. 登录 [Central Portal](https://central.sonatype.com/) 并使用 GitHub 账号完成认证。
2. 注册并验证 `io.github.biglv666` namespace。
3. 确认 Portal 显示你拥有该 namespace 的发布权限后，才能执行部署。

> 如 Portal 分配的 namespace 与此不同，以 Portal 已验证的 namespace 为准，并同步修改 `pom.xml`、README 和本文档。

### 1.2 创建 Portal User Token

在 Central Portal 的 Account 页面创建 User Token，并在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 中设置：

| Secret | 内容 |
|---|---|
| `CENTRAL_USERNAME` | Portal User Token 的用户名 |
| `CENTRAL_PASSWORD` | Portal User Token 的密码 |
| `GPG_PRIVATE_KEY` | ASCII-armored 私钥文本经 Base64 编码后的内容 |
| `GPG_PASSPHRASE` | GPG 私钥口令 |

不要使用旧的 `OSSRH_USERNAME` 或 `OSSRH_TOKEN`。

### 1.3 创建并发布 GPG 公钥

中央仓库要求对 POM 和所有发布构件签名。请妥善保管私钥和口令，绝不能提交到仓库。

```bash
# 生成密钥；将名称和邮箱替换为你的真实公开身份
gpg --quick-generate-key "LV <379299583@qq.com>" rsa4096 sign 2y

# 查看 Key ID
gpg --list-secret-keys --keyid-format LONG

# 将公钥上传到可检索的公钥服务器
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>

# 导出供 GitHub Actions 使用的 ASCII-armored 私钥
gpg --armor --export-secret-keys <KEY_ID> > gpg-private-key.asc
```

将 `gpg-private-key.asc` 的全部内容 Base64 编码后填入 `GPG_PRIVATE_KEY`。上传后可通过公钥服务器检索该 Key ID。

---

## 2. 修改版本与发布元数据

发布前确认 `pom.xml` 的以下信息正确：

```xml
<groupId>io.github.biglv666</groupId>
<artifactId>api-governance-spring-boot-starter</artifactId>
<version>0.1.0</version>
```

- 正式版本不能包含 `-SNAPSHOT`。
- 每个已发布版本不可覆盖；修复后必须递增版本号。
- Git tag 与 POM 版本必须一致，例如 POM 为 `0.1.0` 时 tag 使用 `v0.1.0`。
- 若修改 Java 包名，必须同步维护 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 中的自动配置类全名。

---

## 3. 本地验证

普通开发构建会自动跳过 GPG 签名：

```bash
# Windows
mvnw.cmd clean verify

# Linux/macOS
./mvnw clean verify
```

在安装并配置 GPG 私钥后，执行发布前签名验证：

```bash
mvnw.cmd clean verify -Prelease
# 或 ./mvnw clean verify -Prelease
```

成功后，`target/` 中应至少包含主 JAR、`-sources.jar`、`-javadoc.jar`，并在 release profile 下生成相应 `.asc` 签名文件。

---

## 4. 通过 GitHub Actions 发布（推荐）

```bash
git add .
git commit -m "release: v0.1.0"
git push origin main

git tag v0.1.0
git push origin v0.1.0
```

`.github/workflows/maven-publish.yml` 会：

1. 使用 JDK 17 构建和测试；
2. 导入 GPG 私钥并对构件签名；
3. 使用 Central Portal token 执行 `mvn deploy -Prelease`；
4. 等待构件发布完成；
5. 创建 GitHub Release。

若工作流失败，请不要重推同一个 tag 或重发同一个版本；修复后递增 POM 版本和 tag。

---

## 5. 本地手动发布

在 `~/.m2/settings.xml` 配置 Portal User Token：

```xml
<settings>
    <servers>
        <server>
            <id>central</id>
            <username>你的 CENTRAL_USERNAME</username>
            <password>你的 CENTRAL_PASSWORD</password>
        </server>
    </servers>
</settings>
```

然后执行：

```bash
mvnw.cmd clean deploy -Prelease
# 或 ./mvnw clean deploy -Prelease
```

`central-publishing-maven-plugin` 会将已签名构件上传到 Central Portal，并配置为自动发布。

---

## 6. 发布后验证

1. 在 [Maven Central Search](https://search.maven.org/) 搜索 `io.github.biglv666:api-governance-spring-boot-starter`。
2. 在一个全新项目中添加正式版本依赖：

```xml
<dependency>
    <groupId>io.github.biglv666</groupId>
    <artifactId>api-governance-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

3. 运行 `mvn dependency:resolve`，确认可从 Central 下载。