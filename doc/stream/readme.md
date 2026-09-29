# Stream 文档索引

当前文档更新至 2026-09-29，包含六流、四维地址、转置、X/Y、SIMD 逐元素、内积与外积。

## 当前接口与实现

1. [流式访存与计算总结](正式/流式访存总结文档.md)：硬件形态、索引/ready/提交、访存与同步，以及 kernel 索引。
2. [Stream-SIMD 指令集与示例](正式/Stream-SIMD指令集.md)：先看计算语义，再看完整位域、CFG、公共头和实际示例链接。
3. [N-D 流式地址配置](正式/N-D流式地址配置.md)：四维描述符、启动顺序、内外积一次配置和转置配置。
4. [SIMD merge-review](simd-merge-review/README.md)：外部快照的历史审查与当前解决情况，二者分开记录。

公共头：[stream_instr.h](../../RV-Software/base-port/base/include/stream_instr.h)。
示例以软件子模块实际源码为准；没有另外维护一套候选 `stream.h`。

## 实现与测试记录

- [外积合入、AXI B 响应 fence 与一次 N-D 配置](outer-merge-20260929.md)
- [SIMD8 逐元素、VDOT 与最新功能测试](simd8-implementation-20260929.md)
- [Layout Transform 修改与既有 profile](正式/Layout-Transform修改说明.md)
- [复数模平方实现与既有 profile](正式/复数模平方流计算实现.md)
- [标量计算字段说明](正式/流计算指令编码约定.md)：背景补充，完整当前 SIMD 编码以上述总文档为准。

阶段记录中的 cycle、旧等待方式和功能范围只适用于其对应版本，不自动构成当前 SIMD 性能或同步保证。
主库通过子模块提交指针记录软件和模拟器版本；阅读/运行示例需准备匹配的 RV-Software 与 ZirconSim。

## 历史设计

以下保留为历史材料，不再标记为当前实现：

- [V0](work/V0/)：最初接入乘除流水线。
- [V1](work/V1/)：拆分就绪判断、读、算、写。
- [V2](work/V2/)：早期 GEMM 支持。
- [VFFT](work/VFFT/)：早期 FFT 设计分析。

早期候选 SIMD 文档、导出 PDF 或打包示例不能覆盖当前公共头/译码器的接口定义。
