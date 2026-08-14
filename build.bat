@echo off
chcp 65001 >nul
echo ========================================
echo API Governance Spring Boot Starter
echo 构建和安装脚本
echo ========================================
echo.

echo [1/3] 清理项目...
call mvnw.cmd clean
if %errorlevel% neq 0 (
    echo 清理失败！
    pause
    exit /b 1
)

echo.
echo [2/3] 编译项目...
call mvnw.cmd compile
if %errorlevel% neq 0 (
    echo 编译失败！
    pause
    exit /b 1
)

echo.
echo [3/3] 运行测试...
call mvnw.cmd test
if %errorlevel% neq 0 (
    echo 测试失败！
    pause
    exit /b 1
)

echo.
echo [4/4] 安装到本地Maven仓库...
call mvnw.cmd install -DskipTests
if %errorlevel% neq 0 (
    echo 安装失败！
    pause
    exit /b 1
)

echo.
echo ========================================
echo 构建成功！
echo ========================================
echo.
echo 生成的JAR包位置:
echo    target\api-governance-spring-boot-starter-0.0.1-SNAPSHOT.jar
echo.
echo 安装位置:
echo    %USERPROFILE%\.m2\repository\io\github\yourname\api-governance-spring-boot-starter\0.0.1-SNAPSHOT\
echo.
echo 下一步:
echo    1. 在其他 Spring Boot 项目中添加依赖
echo    2. 在 application.yml 配置 api.governance.*
echo    3. 可选使用 @RateLimit / @Skip / @NoLog 注解
echo.
echo 查看文档:
echo    - README.md        - 快速开始与功能说明
echo    - ARCHITECTURE.md  - 架构设计与维护迭代指南
echo.
pause
