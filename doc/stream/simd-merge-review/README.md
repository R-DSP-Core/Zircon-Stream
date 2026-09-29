# Stream-SIMD 分支审查与合入状态

原始静态审查：2026-09-28。合入状态更新：2026-09-29。
当前代码基线：主仓库 `366f5e71`、RV-Software `bf9e58df`、ZirconSim `6a21bcfc`。

## 1. 如何阅读

本文保留外部 SIMD 源码快照的审查依据，并说明我们后续如何接入。
**第 2 至 5 节描述的是被审查快照，不是宣称当前服务器还存在同样的问题。**
原审查仅为静态检查，没有重新编译或仿真对方快照；后续我们的实现和测试另列于第 6 节。

被审查材料为 `Zircon-Stream-SIMD` 源码快照及配套 `SIMD指令扩展.md`。
快照没有 `.git`，不能验证真实共同祖先；本地存在 2026-03-13 的 `2c5da86a` 提交，不足以证明它就是快照的分叉点。
下文“快照路径:行号”仅是历史定位，不链接到本仓库同名但内容不同的文件。

结论：外积技术路线合理，可以借鉴数据通路，但不能整份覆盖我们已有的六流、N-D、X/Y 与转置实现。编码分歧本身不等于设计错误。

## 2. 历史实现问题与验证缺口

### 2.1 OMAC 同拍跨 B 窗口边界

静态审查级别 P1。快照 `Dispatch.outerStep` 对 OMAC 的 B 步进固定为 0，同拍后续指令据此绑定 B 索引；`Stream.scala` 则汇总整拍 OMAC 数量，在拍末计算 B 游标增量。

最小反例：reuse=8，旧 B 窗口已使用 7 次，本拍同时分发两条 OMAC：

| 指令 | 应读取 | 快照绑定 |
| --- | --- | --- |
| 本组第 8 条 | B[0..7] | B[0..7] |
| 下一组第 1 条 | B[8..15] | B[0..7] |

拍末推进不能修正已写入第二条指令包的旧索引。原双分发测试从“已用 6 次”出发，只覆盖拍末恰好耗尽，没覆盖第一条耗尽、第二条换窗。
这是静态可推导的缺陷，原审查没有在对方 RTL 上复现。建议同拍逐条折叠前缀状态，或者在边界阻止后续槽位分发。

历史依据：快照 `src/main/scala/Dispatch/Dispatch.scala:47`、`Backend/Stream.scala:406`、`src/test/scala/Dispatch_SE_Outer_Test.scala:342`。

### 2.2 矩阵输入不能充分区分地址错误

静态审查级别 P2。快照外积测试使用 `A[i][k]=(i%8)+1`、`B[k][j]=(j%8)+1`，不随 k 变化，各 tile 又重复相同模式。
错取 K 位置或 tile 仍可能得到相同结果。
该测试确实有内存 golden，不是完全不检查；但特定输入下通过不能证明一般矩阵乘的地址与复用正确。

历史依据：快照 `RV-Software/stream-outer-matrix/src/stream-outer-matrix.c:17`、`ZirconSim/src/GoldenCheck.cc:178`。

### 2.3 文档与实现未完全对齐

快照的 VADD、VMUL、VOMAC、VCLR、VREGOUT 有对应实现，并有 speculative/architectural VReg 和提交机制，不是只有空接口。
但 Decoder 还接受文档未列出的 `funct3=0, funct7=5` VECMAC 和 `funct3=7, funct7=1` 状态查询，正式接口与实验遗留边界不清。
历史依据：快照 `src/main/scala/Frontend/Decode/Decoder.scala:56`。

## 3. 外积不等于内积归约

快照中的两个操作分别为：

```text
VOMAC:  VReg[bank][l] += A_scalar * B[l]
VECMAC: VReg[0][l]    += A[l]     * B[l]
```

两者都是逐 lane 累加，不包含把 8 lane 横向求和的步骤。
正式外积程序每个 k 做 8 条 OMAC 更新一个 8x8 tile，遍历 K 后导出；文档 VREDUCE 只是保留。
理论上可导出 8 路部分和再由 CPU 求和，但不能因此说已经实现 VDOT。
快照 `RV-Software/temp/stream-in-matrix` 仅执行一次逐 lane 乘累加，再重复导出 bank0 凑满 line，不是完整内积矩阵乘。

历史依据：快照 `Backend/Pipeline/VectorStreamPipeline.scala:235,347` 与上述 `stream-in-matrix/src/stream-in-matrix.c:3`。

## 4. 为什么选择性合入

两边都使用 opcode=0x0b，但存在直接机器码冲突：

| funct3 / funct7 | 对方快照 | 我们保留的接口 |
| --- | --- | --- |
| 0 / 3 | VADD | cfg_i_offset |
| 6 / 0 | cfg_tilestride | 两个真实 GPR 写 PP 的 RRS |

不能只改 C 函数名称。机器编码、GPR 依赖识别、内部操作枚举以及提交事件都要一起对齐。
快照向量源固定 FIFO0/1；我们的源 ID 可选，并已有 X/Y、四维地址、FIFO3 layout 路径，直接替换 Stream/Dispatch/Commit 会丢失这些能力。
双方 stream-buffer 都仍是寄存器数组；新增 VReg 不意味着 buffer 变成多 bank SRAM。

## 5. 快照交付边界

快照的 `build.sbt` 引用 `src_simd`，测试导入 `zircon.simd`，但快照没有相应目录和类型源码。
这至少是测试源码交付缺口，也可能只是打包遗漏；原审查没有做 clean build，不能推断所有主设计都无法编译。
历史依据：快照 `build.sbt:12`、`src/test/scala/SimdCompute_Test.scala:4`。

评价应依据代码与测试，而不是作者是否使用 AI。几个特定程序能运行不能替代边界覆盖；静态风险也不应冒充已经完成的 RTL 复现。

## 6. 当前基线如何处理

| 原问题 / 分歧 | 当前处理 | 对应代码或证据 |
| --- | --- | --- |
| 同拍跨窗口边界 | Dispatch/Commit 都按程序序逐条调用索引推进，支持 1/8-word 步长 | [StreamIndex](../../../src/main/scala/Backend/StreamOuter.scala)、[外积单测](../../../src/test/scala/StreamOuterTest.scala) |
| 只用重复矩阵输入 | 输入随行/列/K 变化，完整 M32N32K32 与独立矩阵乘 golden 比较 | [外积程序](../../../RV-Software/stream-matrix/src/stream-outer-matrix.c) |
| 编码冲突与固定源 | 保留旧 CFG/PP；外积用 110:40/41/42；源支持 0/1/4/5 | [统一指令集](../正式/Stream-SIMD指令集.md)、[公共头](../../../RV-Software/base-port/base/include/stream_instr.h) |
| 缺内积归约 | 新实现 111:60 VDOT8，返回真实 GPR，不读取旧 rd | [SIMD 内积矩阵](../../../RV-Software/stream-matrix/src/stream-simd-matrix.c) |
| 向量逐项运算 | 接入 VADD8/VSUB8/VMUL8，共享 8-lane 数据通路 | [SIMD 单测](../../../src/test/scala/StreamSimdTest.scala)、[向量用例](../../../RV-Software/stream-test/src/stream-simd-vector.c) |
| 分发、提交与恢复风险 | 源 issue 预留，refill 检查提交读者；向量结果 commit 后可见；VReg 双态恢复 | [Stream.scala](../../../src/main/scala/Backend/Stream.scala)、[外积实现记录](../outer-merge-20260929.md) |
| 逐 tile 重配置 | 内外积矩阵都一次 N-D 配置；trace 检查计算中没有 CFG | [N-D 文档](../正式/N-D流式地址配置.md)、[外积 trace 检查](../../../tests/stream-outer/check-matrix-trace.py) |
| CPU pack 与直接 AXI 不一致 | 新矩阵两路输入走 L2；FIFO2 输出 fence 等待 AXI B | [fence 单测](../../../src/test/scala/StreamFenceTest.scala) |

没有照搬快照 VECMAC/状态查询，也没有把未实现的保留码列为可用接口。
当前内外积是分别支持的两种计算方式，不是强迫所有矩阵乘改成外积。

2026-09-29，当前代码已完成 8 项 Chisel 单测和 13 个程序的最终功能验证；详细过程、主动中止后重跑的记录、测试范围见 [SIMD8 实现记录](../simd8-implementation-20260929.md)。
这不是对方快照的测试结果，也不代表已穷尽全部冲刷/背压组合。尚未做新版本综合时序或性能加速比评价。
FIFO3 整体完成 fence 仍有边界，见 [当前总结](../正式/流式访存总结文档.md)，不要把 FIFO2 的 B 响应修复扩展解读为所有异步任务同步都已完成。
