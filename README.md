# Cesia输入法 🎉

**开源Typeless克隆版 - 真正的3步输入体验**

## ✨ 核心功能

```
1. 点选输入框 → Cesia输入法自动弹出
2. 点击🎤麦克风 → 说话 → 自动识别+润色 → 文字上屏
3. 点击发送 ✅
```

**中间步骤全自动**：语音识别(WeNet) → 文字润色(API) → 上屏

## 🏗️ 技术架构

| 模块 | 技术 | 说明 |
|------|------|------|
| **语音识别** | WeNet (90MB) | 离线中文识别，专有优化 |
| **文字润色** | typeless-ai-service.vercel.app | 云端API，调用OpenRouter |
| **输入法** | Android InputMethodService | 系统级输入法，任何APP可用 |
| **自动上屏** | commitText() | 识别+润色后自动填入输入框 |

## 📱 使用流程

### 安装后设置
1. 设置 → 语言和输入法 → 虚拟键盘 → 启用"Cesia输入法"
2. 在任何输入框点选，Cesia自动弹出

### 3步输入
1. **点麦克风** 🎤 → 说话
2. **再次点击** → 停止录音
3. **自动识别** → WeNet转文字 → API润色 → **自动上屏** ✅
4. 确认无误 → **点发送**

## 🚀 快速开始

### 用户安装
1. 下载最新APK：https://github.com/harviex/cesia-input-method/releases
2. 安装APK（允许未知来源）
3. 启用输入法：设置 → 语言和输入法 → 勾选"Cesia输入法"
4. 开始使用！

### 开发者编译
```bash
git clone https://github.com/harviex/cesia-input-method.git
cd cesia-input-method
# 下载WeNet模型到 app/src/main/assets/
# 用Android Studio打开项目
# 编译 → Build APK
```

## 📦 项目结构

```
cesia-input-method/
├── app/
│   ├── src/main/
│   │   ├── java/com/cesia/input/
│   │   │   ├── CesiaInputMethod.kt      # 输入法服务（核心）
│   │   │   ├── WenetManager.kt          # WeNet语音识别
│   │   │   ├── ApiClient.kt             # HTTP调用润色API
│   │   │   └── SettingsActivity.kt      # 设置界面
│   │   ├── res/layout/
│   │   │   └── input_view.xml          # 输入法界面
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── wenet/                              # WeNet Android库
├── README.md
└── build.gradle
```

## 🔧 配置说明

### API地址设置
打开Cesia输入法设置（长按空格或设置按钮）：
- 润色API地址：`https://typeless-ai-service.vercel.app/api/polish`
- 可自行更换其他API

### 模型文件
- WeNet模型(~90MB)需放在 `app/src/main/assets/wenet_model.bin`
- 下载地址：https://github.com/wenet-e2e/wenet/releases

## 🎯 与Typeless对比

| 功能 | Typeless | Cesia输入法 |
|------|---------|--------------|
| **语音识别** | 未知(85MB) | WeNet(90MB) ✅ |
| **文字润色** | 云端API | 云端API ✅ |
| **自动上屏** | ✅ | ✅ |
| **开源** | ❌ | ✅ |
| **国内可用** | 未知 | ✅ (离线识别) |
| **大小** | 85MB | ~100MB |

## 📜 开源协议

Apache 2.0 - 可自由使用、修改、分发。

## 🤝 贡献

欢迎提交Issue和PR！项目由 **主人(@harviex)** 和 **Hermes Agent** 共同开发。

## 📞 联系

- GitHub: https://github.com/harviex/cesia-input-method
- Twitter: @harvietse

---

**🎉 Cesia输入法 - 让输入更智能！**
