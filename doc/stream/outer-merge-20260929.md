# Stream 外积扩展合入说明

## 基线与范围

以服务器 `luoteng:~/Zircon-2024-main` 的六路 stream、四维地址配置、FIFO3 转置、X/Y 计算流为基线。
参考 SIMD 分支的“广播一个标量、乘一个 8-word 窗口、累加到 VReg”结构，没有整套覆盖对方的 Stream/Dispatch/Commit。
本文记录外积三条指令的首阶段实现。后续逐元素 SIMD 和 VDOT 的实现见 [SIMD8 补充说明](simd8-implementation-20260929.md)，首阶段本身不包含这些指令。

修改前已保存三个仓库，均未 push：

| 仓库 | 保存提交 |
| --- | --- |
| 主仓库 | `d084a4bb` |
| RV-Software | `0b3f802` |
| ZirconSim | `53e757d` |

三个仓库均在 `stream-simd-outer-20260929` 分支继续开发。
外积初版阶段提交为主仓库 `c63ab1c7`、RV-Software `d52ccb8`、ZirconSim `3ffc7d1`。
下文已更新为该阶段之后的 B 响应同步修正和一次配置矩阵版本。

## 指令接口

公共头：`RV-Software/base-port/base/include/stream_instr.h`。
以下均使用 `opcode=0x0b, funct3=110`；原 `funct7=0` 的 GPR-to-PP 指令保持不变。

| 指令 | funct7 | rd | rs1 | rs2 | 语义 |
| --- | --- | --- | --- | --- | --- |
| `stream_vomac8(a,b,v)` | `0x40` | bank v | 源流 a | 源流 b | `V[v][l] += A[0] * B[l]`, l=0..7 |
| `stream_vclr()` | `0x41` | 0 | 0 | 0 | 清零全部 8 个 bank |
| `stream_vregout8(v,d)` | `0x42` | 目的流 d | bank v | 0 | `D[l] = V[v][l]`, l=0..7；不清零 bank |

ID/bank 是寄存器位域里的立即编码，不读取对应 GPR。有效值使用位域低 3 位，软件须把高 2 位编码为 0。
源流允许 0、1、4、5；目的流允许 2、4、5；bank 为 0..7。头文件做编译期检查。

`A[l]`、`B[l]` 是各自当前读索引起第 l 个 32-bit word，`D[l]` 是当前写索引起第 l 项。
OMAC 的 A 消费 1 word，B 消费 8 words；相同源 ID 消费两个窗口的并集，即 8 words，而不是 9 words。
每条指令的窗口内部连续，允许跨 32-word line、跨 64-word 环形 buffer；随后按原有 limit/repeat/offset 更新索引。
配置应让向量窗口落在一次逻辑迭代内，不能用一个 8-word 操作代替中途需要跳转的多个标量迭代。
乘法和累加均保留低 32 位；不是浮点、饱和运算或 64-bit 累加。

## 硬件接入

- `Stream.scala` 的六个 64-word operand buffer 保留，不改成多 bank SRAM。增加连续 8-word 读、就绪检查和提交写入。
- `MulDivPipeline.scala` 复用标量乘法器作为 lane 0，增加另外 7 个乘法器；标量 MUL/DIV 与外积共用 MDU 发射口。
- `StreamOuter.scala` 中的 VReg 为 8 bank x 8 lane x 32 bit 寄存器阵列；各有 speculative/architectural 状态。
- OMAC/clear 顺序执行，提交时更新 architectural VReg。flush 恢复已提交前缀；导出结果随指令存入 ROB，避免被后续 clear/MAC 改写。
- 外积在 issue 时扣执行侧的剩余使用次数，commit 时扣提交侧次数；load refill 必须等整条 line 的两套计数都为零，防止 flush 后还要使用的数据被提前覆盖。
- 导出先预留目的窗口，只有 ROB 提交后才写 FIFO/readymap，错误路径不会发出 AXI store。
- 索引更新改为同拍内按指令依次推进，支持 1/8-word 步长；两个槽位跨 repeat 边界时，后者看见前者更新后的状态。
- MDU 使用独立入队序号排序。原 ROB 子队列在 flush 后的物理槽号不一定按程序序递增，不能用于强制顺序发射。

原 CFG、N-D、标量 SSS/SSR、FFT PP 编码不变。内部 `sinfo.op` 从 4 bit 扩为 5 bit，不改变机器码中的 funct3/funct7 宽度。

## 配置与一致性

测试的 A/B 均为 CPU runtime pack 后的数据，两个 load stream 都用 `cfg_load` 走 L2。
不使用直接 AXI 读取 CPU 已经写脏的 pack 区域。

`stream_fence()` 发出标准编码的 `fence rw,rw`：前端单独分发，等待之前的 ROB 指令和 CPU store buffer、当前 stream 排空判定满足，再放行后续指令。
测试在 pack 之后、配置阶段边界和读取输出前使用它，不依赖固定 NOP 延时；不是要求每条 CFG 前后都加 fence。它不执行 cache flush/invalidate，也不会让 CPU 脏数据自动出现在 AXI 内存中。
FIFO2 仍按满 32-word line 写 AXI；不足一 line 的输出不能靠 fence 排空。

**B 响应同步：** `SEMemIO.writePending` 由 `AXIArbiter` 的 stream 写事务状态产生，从等待 AW、传输 W 到等待 B 均保持为真，B 握手后才解除。`StreamEngine.storesDrained` 增加 `!writePending` 条件，沿原有 Backend -> Dispatch 路径阻塞 fence。
原 `wrsp` 仍表示逐 word 的 W 握手，FIFO2 的数据推进和释放时机不变；不把每个 word 改成等待 B。当前仲裁器只允许一笔活动写事务，无需新增 outstanding 计数器。本次没有扩展 AXI 错误响应的异常处理。

仿真 `AXIMemory.cc` 仍在 W 握手时更新 memory，这是原模型的行为。验证同步使用延迟 B 的硬件测试直接检查 Dispatch，而不是只依赖最后的 golden 比较。

输出数组按 128B 对齐，写回前不被 CPU 读取或写入。当前启动代码没有 CPU 清零 BSS 的循环，因此测试输出没有预存的脏 cache line。
这只是测试的一致性前提，不是“AXI 输出已经支持任意 cached destination”的承诺。

## 测试程序

### 完整矩阵乘

`RV-Software/stream-matrix/src/stream-outer-matrix.c`：M=N=K=32，分成 16 个 8x8 输出子块。

- A pack：一个 k 对应当前子块的 8 行；每条 OMAC 取其中一项，依次更新 bank 0..7。
- B pack：一个 k 对应当前子块的 8 列；`limit=8, repeat=8, offset=0, reuse=8`。
- B 的 limit/repeat 决定同一 8-word 窗口保持 8 次后推进；reuse 只是每项的消费次数，二者不混用。
- A/B 各配置一次，遍历全部 16 个子块；每路逻辑输入序列共 4096 words。B 的重复消费仍由 limit/repeat/reuse 控制，不额外扩大其地址遍历次数。
- FIFO2 一次配置 1024-word 连续输出区，不在子块之间重设地址或排空。子块间通过 ready/背压与后台写出衔接。
- 每个子块清零一次，执行 32 x 8 条 OMAC，再用 8 条导出产生 64 words；FIFO2 写两条 AXI line。
- 整体为 4096 条 OMAC、128 条导出、16 条 clear；输出按 tile-major 保存，最终逐项比较独立标量 golden。

packed A/B 各有 4 个 256-word panel，每个 panel 为 1024 字节。按软件 `tr` 外层、`tc` 内层的计算顺序配置如下，stride 单位为字节：

| 维度 | size | A stride | B stride |
| --- | --- | --- | --- |
| dim0：line 内 word | 固定 32 | 4 | 4 |
| dim1：panel 内 line | 8 | 128 | 128 |
| dim2：列子块 tc | 4 | 0 | 1024 |
| dim3：行子块 tr | 4 | 1024 | 0 |

即 `addrA = baseA + word*4 + line*128 + tr*1024`，`addrB = baseB + word*4 + line*128 + tc*1024`。
stride=0 表示重复从 L2 读取相同 panel，不表示把完整 panel 留在 64-word buffer 中。
程序仅保留 3 条 fence：CPU pack 完成后、一次配置全部生效后、全部结果导出后；`outer_tile()` 内没有 CFG 或 fence。

### 边界覆盖

`stream-outer-edges.c` 覆盖：B 从索引 28 开始跨 line，索引 60 跨环形 buffer 并触发第三条 line refill；连续同 bank 累加；同源 ID 去重；32-bit 溢出；X/Y 导出后被标量、外积消费；独立读写索引；导出后立即 clear。

### Difftest

新增 `--stream-diff=outer`，`stream-outer*` 镜像自动选择。
参考模型独立维护 N-D 配置、索引、8x8 VReg 和 X/Y 数据；每条 OMAC/导出在提交时检查全部 8 lane 以及实际使用的索引。
FIFO2 的 reference 写入来自参考模型，而不是复制 DUT AXI 数据；程序末尾 CPU load + golden 检查真实内存写回。
可用 `--no-wave` 关闭 VCD，但保留指令时序/AXI 日志；本轮没有运行 `profile.sh` 生成基本块性能汇总。

运行：

```sh
sbt --batch 'testOnly StreamFenceTest StreamOuterTest'
bash tests/stream-outer/run.sh
# 单独跑两个新用例：
bash tests/stream-outer/run.sh stream-matrix/stream-outer-edges stream-matrix/stream-outer-matrix
# 原有逐程序入口保持不变：
cd RV-Software/stream-matrix
make NAMES=stream-outer-matrix run
```

## 初版验证记录

2026-09-29，在上述服务器分支完成 Scala 编译、Verilog 生成、Verilator/C++ 整机编译。
`StreamOuterTest` 的 4 个测试全部通过：立即编码与 GPR 依赖、同拍索引推进、MDU 入队顺序及序号回绕、VReg 提交与 flush 恢复。

整机回归的 10 个用例均通过：

| 用例 | 覆盖 |
| --- | --- |
| `stream-outer-edges` | 跨 line/环形 buffer、同源去重、累加溢出、X/Y 生产消费 |
| `stream-outer-matrix` | 完整 32x32x32 外积矩阵乘，逐指令 difftest + 1024 项软件 golden |
| `stream-add`、`stream-complex-mag` | 原标量流计算、模平方与 X/Y |
| `stream-matrix-k128`、`stream-transpose` | 原 N-D 矩阵乘、FIFO3 转置 |
| `fir32`、`fft32` | FIR32；复用原 FFT 函数的一次 32 点变换及软件比较 |
| `mul-longlong`、`div` | 与外积共用 MDU 的普通整数乘除 |

矩阵 trace 中核实了 4096 条 VOMAC8、128 条 VREGOUT8、16 条 VCLR。
整段测试为 615907 cycles，包含 pack、初始化、golden 和检查，不能作为外积核心循环性能或加速比。
当前只做功能验证，没有综合面积/时序数据，也没有声称覆盖所有错误路径与背压组合。

整机日志在服务器 `/tmp/zircon-outer-regression.1jsuMr`；FFT32 包装程序修复缺失的 `check` 定义后单独复跑，日志在 `/tmp/zircon-outer-regression.bIkqg2`。
单元测试日志为 `/tmp/zircon-outer-unit3.log`，整机编译日志为 `/tmp/zircon-outer-fixed-build.log`。这些临时日志未纳入 Git。

## B 响应与一次配置验证

`StreamFenceTest` 连接真实的 StreamEngine、AXIArbiter 和 Dispatch，注入已提交的两条 FIFO2 line；AW/W 故意背压，B 分别延迟 32 和 128 个周期。检查 W 连续推进和数据不变、B 等待期间 fence 不可分发、有后续 burst 时不能误判排空、CPU store/ROB 尚未排空时仍等待、fence 提交前不放行后继指令。
该测试和原有 4 个 `StreamOuterTest` 均已通过，日志为 `/tmp/zircon-fence-nd-unit.log`。

本轮重新生成 RTL、编译模拟器并运行上述全部 10 个整机用例，均通过。日志在服务器 `/tmp/zircon-outer-regression.eW7sSV`，该目录的 `build.log` 为本轮编译日志，总入口日志为 `/tmp/zircon-fence-nd-regression.log`。
完整矩阵逐指令 difftest 和 1024 项软件 golden 均通过；整段仿真为 618856 cycles、722432 instructions，包含 pack 和软件检查，不作核心循环性能或加速比结论。

使用 CSV 解析器检查实际提交 trace：

```sh
# 在主仓库根目录运行；新的回归目录以脚本输出的 Reports 为准。
python3 tests/stream-outer/check-matrix-trace.py \
  /tmp/zircon-outer-regression.eW7sSV/profiling/stream-outer-matrix-riscv32/base.log
```

检查通过：`cfg_i` 共 3 次、`cfg_load` 共 2 次、`cfg_store` 共 1 次、fence 共 3 次，且计算开始后不再执行 CFG，所有导出完成前没有中间 fence。OMAC/导出/clear 数量仍为 4096/128/16。
同一检查脚本能拒绝初版逐块配置的 trace，避免仅凭数值正确就声称完成了多维一次配置。
