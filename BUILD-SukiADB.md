# SukiADB 构建说明

SukiADB = **SukiSU Ultra 的视觉外观** + **AxManager 的 ADB 免 root 内核**。

包名 `frb.axeron.sukiadb`（可以和原版 AxManager 共存安装）。

---

## 一、这个包改了什么

| 文件 | 改动 |
|---|---|
| `ui/theme/Color.kt` | 主色 `#FFB487` → **`#FF9CA8`**（SukiSU 的 FallbackSeedColor） |
| `ui/theme/Theme.kt` | 形状改为 SukiSU 的 **8 / 12 / 18 / 24 / 32 dp** 圆角 |
| `ui/component/AxCard.kt` | 卡片 16→24dp，面板 12→18dp |
| `ui/navigation/BottomBarDestination.kt` | 权限页图标 → **Shield**（对齐 SukiSU 的超级用户） |
| `ui/AxActivity.kt` | 导航项未选中态加 `surfaceContainerHighest` 底色；选中态展开为**胶囊 + 文字** |
| `res/values/strings.xml` | 应用名 → SukiADB |
| `manager/build.gradle.kts` | applicationId → `frb.axeron.sukiadb` |

这 16 处改动只碰**视觉常量与图标**，不动任何业务逻辑，所以功能与 AxManager 完全一致。

---

## 二、构建方式

### 方式 A：GitHub Actions（推荐，零本地环境）

仓库里已带 `.github/workflows/build-sukiadb.yml`。

```bash
# 1. 把本目录推到你的 GitHub 仓库
git init
git add -A
git commit -m "SukiADB"
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main
```

push 之后进仓库的 **Actions** 页面，等 `Build SukiADB APK` 跑完，
在运行记录的 **Artifacts** 里下载 `SukiADB-APK`。

workflow 已经处理了三件容易踩的事：
- 手动 clone `Axeron-API` 子模块（zip 里不带 submodule 内容）
- `fetch-depth: 0`（versionName 依赖 `git rev-list --count HEAD`）
- 自动生成签名密钥并写 `local.properties`

### 方式 B：本地命令行

需要 **JDK 21**、**Android SDK 36**、**NDK 29.0.14206865**、**CMake 3.22.1**。

```bash
# 1. 拉 API 子模块
rm -rf api
git clone --depth 1 https://github.com/fahrez182/Axeron-API.git api

# 2. 生成签名
keytool -genkeypair -keystore release.keystore -alias sukiadb \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass sukiadb -keypass sukiadb -dname "CN=SukiADB"

# 3. 写 local.properties
cat > local.properties <<EOF
sdk.dir=$HOME/Android/Sdk
api_version_code=30000
api_version_name=3.0.0
signing.storeFile=$PWD/release.keystore
signing.storePassword=sukiadb
signing.keyAlias=sukiadb
signing.keyPassword=sukiadb
EOF

# 4. 编译
chmod +x gradlew
./gradlew :manager:assembleRelease
```

产物在 `manager/build/outputs/apk/release/`。

### 方式 C：Android Studio

用 Studio 打开根目录，等 Gradle Sync 完，先按方式 B 的 1~3 步准备 `api/` 和 `local.properties`，
然后 Build → Generate Signed Bundle / APK。

---

## 三、必须知道的三个坑

**1. `api/` 目录不能是空的。**
`settings.gradle.kts` 会引用 `api/aidl`、`api/api`、`api/shared` 等 6 个子模块，
根 `build.gradle.kts` 还会 `apply(from = "api/manifest.gradle.kts")`。
少一个文件，Gradle Sync 直接失败。

**2. 需要 git 提交历史。**
`verName = "${api_version_name}.r${gitCommitCount}"`，没有 git 历史会抛异常。
所以 `git clone` 不要加 `--depth 1`（CI 里用 `fetch-depth: 0` 解决）。

**3. `api_version_code` / `api_version_name` 必须写在 `local.properties`。**
它们是 `findProperty(...) as Int`，缺失会 NPE。用 `30000` / `3.0.0` 即可。

---

## 四、这台手机为什么构建不了

构建这套东西需要：

| 项目 | 需求 | 本机实际 |
|---|---|---|
| 内存 | 4 GB+ 峰值（Compose + NDK 并行） | 1.9 GB 可用 |
| JDK | 21 | 未安装 |
| Android SDK | platform 36 + build-tools 36 | 未安装 |
| NDK | 29.0.14206865（约 2.5 GB） | 未安装 |

`adb` 和 `server` 两个模块都走 `externalNativeBuild` + CMake，**C++ 必须编译**，
不存在"只改 Kotlin 就不装 NDK"的捷径。所以只能走 CI 或有 8 GB 内存的电脑。

---

## 五、关于「超越 ADB 权限」

做不到，也不该骗人。

ADB shell 是 Linux 下的 **UID 2000**，它能碰到什么由 DAC 和 SELinux 决定。
所谓"超越"只有三条路：

1. **利用内核漏洞提权** —— 那是真实的漏洞利用，不是配置
2. **已经 root** —— 那就不叫超越 ADB 了
3. **骗人** —— 市面上大多数"免 root 超强权限"属于这条

SukiADB 能做的是把 ADB 权限**用满**：

- `pm grant` 授予所有 ADB 可授予的运行时权限（`WRITE_SECURE_SETTINGS`、`DUMP`、`PACKAGE_USAGE_STATS` 等）
- `appops` 全量放行
- `settings` / `device_config` / `cmd` 全接口
- Shizuku 式的服务代理（AxManager 的 server 就是干这个的）

这是合法上限，不是超越。
