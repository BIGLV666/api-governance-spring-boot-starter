@echo off
setlocal
chcp 65001 >nul
set VERSION=0.1.0
set ARTIFACT_ID=api-governance-spring-boot-starter
set GROUP_PATH=io\github\biglv666

echo ========================================
echo API Governance Spring Boot Starter
echo 构建和安装脚本
echo ========================================
echo.

echo [1/2] 清理、编译、测试并安装项目...
call mvnw.cmd clean install
if %errorlevel% neq 0 (
    echo 构建或安装失败！
    pause
    exit /b 1
)

echo.
echo [2/2] 检查发布构件...
if not exist "target\%ARTIFACT_ID%-%VERSION%.jar" (
    echo 未找到主 JAR：target\%ARTIFACT_ID%-%VERSION%.jar
    pause
    exit /b 1
)
if not exist "target\%ARTIFACT_ID%-%VERSION%-sources.jar" (
    echo 未找到 Sources JAR。
    pause
    exit /b 1
)
if not exist "target\%ARTIFACT_ID%-%VERSION%-javadoc.jar" (
    echo 未找到 Javadoc JAR。
    pause
    exit /b 1
)

echo.
echo ========================================
echo 构建成功！
echo ========================================
echo.
echo 生成的 JAR 包位置:
echo    target\%ARTIFACT_ID%-%VERSION%.jar
echo.
echo 安装位置:
echo    %USERPROFILE%\.m2\repository\%GROUP_PATH%\%ARTIFACT_ID%\%VERSION%\
echo.
echo 发布前签名验证:
echo    mvnw.cmd clean verify -Prelease
echo.
echo 查看文档:
echo    - README.md        - 快速开始与功能说明
echo    - PUBLISH_GUIDE.md - Central Portal 发布指南
echo.
pause
endlocal