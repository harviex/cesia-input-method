# Cesia 输入法

Cesia 是一款 Android 输入法，核心思路很简单：**让你说话就能出好文字**。

你说大白话 → AI 帮你润色 → 一键上屏。选中文字 → 一句话让 AI 改 → 瞬间变身。

不用云端处理按键，不上传你的输入内容，AI 润色通过 OpenRouter API 走，相对干净。

---

## 功能

- **AI 语音润色** — 说话，AI 润色好，直接上屏
- **魔法修改** — 选中文字，说一句指令，AI 帮你改（扩写、缩句、翻译、换语气都行）
- **魔法书** — 自己收集常用指令，一键调用
- **全键盘** — 普通 QWERTY，拼音/双拼都支持，功能键长按有快捷操作
- **T9 九宫格** — 适合单手打字
- **简繁体切换** — 工具栏一键切换
- **深色/浅色主题** — 跟系统走

---

## 下载

[GitHub Releases](https://github.com/harviex/cesia-input-method/releases) 下载最新 APK，直接安装覆盖旧版，不用卸载。

安装后：系统设置 → 语言与输入法 → 虚拟键盘 → 启用「Cesia」，然后设为默认输入法。

---

## API 配置（可选）

默认用内置 AI 服务。想用自己的：

设置 → Cesia 设置 → 填入 OpenRouter API Key，选模型。支持免费模型。

AI 风格可选：自然 / 幽默 / 官方 / 简洁 / 正式 / 亲切 / 犀利。

---

## 构建

Android Studio Hedgehog+、JDK 17、SDK 34、NDK。

```bash
git clone https://github.com/harviex/cesia-input-method.git
cd cesia-input-method
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`

---

## 许可

个人免费使用。商业用途联系作者。

---

## 反馈

GitHub Issues 欢迎提 bug 和建议。
