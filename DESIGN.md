*本设计文档作为 Cesia 输入法后续开发的正式大纲。*

---

## 开发进度

### Stage 1: rime-engine 模块（进行中）

**已完成**：
- 模块目录结构创建
- `InputEngine.kt` 统一接口定义
- `RimeEngine.kt` 实现骨架
- `RimeJni.kt` JNI 绑定层定义
- `rime_jni.cc` + `CMakeLists.txt` 基础实现
- `build.gradle.kts` 支持 NDK/CMake

**待完成**：
- 引入 `librime.so` 并完成完整 JNI 实现
- Session 管理与候选词获取的实际调用
- 模块集成测试

---

**注意**：后续开发将按模块化方式持续推进，直到生成可安装 APK。