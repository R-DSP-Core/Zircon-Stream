# Stream SIMD8：逐元素运算与内积

## 范围

基于服务器 `~/Zircon-2024-main` 的 `stream-simd-outer-20260929` 分支继续实现。
前置提交：主仓库 `ec1d751c`、RV-Software `6df9549`、ZirconSim `3ffc7d1`。
外积、等待 AXI B 响应的 fence、一次配置 N-D 遍历均保留；本文补充逐元素运算与内积，不引入新的内存 buffer 或向量长度配置。

## 软件接口与编码

头文件：`RV-Software/base-port/base/include/stream_instr.h`。
`A[l]`、`B[l]` 为两个源流从各自当前读索引起的第 l 个 word，`D[l]` 为目的流从当前写索引起的第 l 个 word，l=0..7，每项 32 bit。

| C 接口 | funct3 | funct7 | rs1 / rs2 | rd | 计算语义 |
| --- | --- | --- | --- | --- | --- |
| `stream_vadd8(a,b,d)` | `010` | `0x40` | 源流 ID | 目的流 ID | `D[l] = A[l] + B[l]` |
| `stream_vsub8(a,b,d)` | `010` | `0x48` | 源流 ID | 目的流 ID | `D[l] = A[l] - B[l]` |
| `stream_vmul8(a,b,d)` | `010` | `0x50` | 源流 ID | 目的流 ID | `D[l] = A[l] * B[l]` |
| `stream_vdot8(a,b)` | `111` | `0x60` | 源流 ID | 真实 GPR | 返回 `sum(A[l] * B[l])`，不读取旧 rd |

opcode 均为 `0x0b`；ID 是指令位域内的字面量，不是 GPR 中的数值。VDOT 的 rd 则由编译器分配为真实 GPR，不可把它当成目的流 ID。
所有结果按模 2^32 运算。VDOT 返回 `uint32_t`，较长内积使用普通 C 加法：`sum += stream_vdot8(0,1)`。
现有标量编码不变；外积仍使用 `funct3=110, funct7=0x40/0x41/0x42`，不会与 SSS 的 `0x40` 混淆。

约束：

- 源流为 `{0,1,4,5}`，目的流为 `{2,4,5}`；SSS 不允许目的流等于任一源流，头文件静态检查。
- 每个不同源流消费 8 words；两个源 ID 相同只消费一次，例如 `stream_vmul8(0,0,4)` 计算平方。
- X/Y 即 4/5，生产和消费索引独立；0/1 接 load，2 接 AXI store，3 保留给 layout transform。
- 一个窗口内连续取 8 words，允许跨 32-word line 和 64-word 环形边界；窗口之后按 limit/repeat/offset 更新索引。窗口内不执行中途的逻辑迭代跳转。
- 不支持掩码、尾部不足 8 项、浮点、饱和或 64-bit 累加；FIFO2 仍按完整 32-word line 排空。

## 硬件实现

`Decoder.scala` 为两个向量源产生 8-word 需求；SSR 保留 GPR 目的依赖，SSS 不产生 GPR 依赖。配置编码及 PreDecoder 原有 SSR 规则不变。

`Stream.scala` 提供两个 8-word 窗口读端口，仍从原有 operand buffer 取数，不改为多 bank SRAM。沿用外积的发射预留机制：源 credit 按不同 ID 扣减，目的窗口先预留；逐元素结果经 ROB 提交后才更新 FIFO/readymap，flush 撤销未提交预留。内积只提交源消费，结果走普通 GPR 写回。

`MulDivPipeline.scala` 共用原外积的 8 个乘法器。OMAC 广播 A[0]，VMUL/VDOT 使用 A[l]；两个窗口随流水级同步暂存，避免后续指令改变操作数。VDOT 通过现有 MDU 唤醒、旁路和 GPR 写回路径返回结果。

`StreamOuter.scala` 共用 8 个 32-bit 加法器：逐 lane 加减、外积累加，或连接其中 7 个组成三层平衡归约树。VDOT 不读取或改变外积 accumulator bank，VADD/VSUB/VMUL 也不改变 bank。此版本验证功能与周期仿真，未进行综合时序验证；三层加法树的实际频率影响尚未测量。

`Commit.scala` 把四条新指令接入已有向量提交事件。内部部分接口仍名为 `outer*`，现在承载外积和普通 SIMD 的共享路径，并非两套硬件。

## Difftest 与测试程序

ZirconSim 扩展独立的 `StreamOuterDifftest` 参考模型，按软件描述符重新生成源地址、执行 8-lane 算术、维护 X/Y 和各自索引；逐条比较提交的 8 个结果及源/目的索引。VDOT 的 8 个乘积也参与比较，归约结果另由普通 GPR difftest 比较。它不从 RTL FIFO 抄数据充当参考。
名字含 `stream-simd` 的镜像自动选择该模型，手动模式 `simd` 是原 `outer` 模型的别名。

| 程序 | 覆盖 |
| --- | --- |
| `stream-test/stream-simd-vector` | 128 元素，加/减/乘、同源平方、X/Y 串接、FIFO2 输出与完整 golden 比较 |
| `stream-test/stream-simd-edges` | 两源从索引 28 开始跨 line/ring、同源 DOT、rd=t4、rd=x0、X/Y 的 DOT、内外积混用和导出快照 |
| `stream-matrix/stream-simd-matrix` | M32 N32 K128 内积，一次配置完整 N-D 地址，全部 1024 输出与软件矩阵乘比较 |

矩阵程序按 `K32 block -> M*N 输出 -> 4 条 VDOT` 遍历，共 16384 条 VDOT。A 每个 32-word 行窗口重复 N=32 次；B 依次访问 32 个列窗口、重复 M 行、推进 K block。循环内不重配置，也不重新 pack。

| 维度 | A size / byte-stride | B size / byte-stride |
| --- | --- | --- |
| 0 | 固定32 / 4 | 固定32 / 128 |
| 1 | 32 / 512 | 32 / 4 |
| 2 | 4 / 128 | 32 / 0 |
| 3 | 1 / 0 | 4 / 4096 |

三个程序的输入由 CPU 初始化，因此两个 load 流均选 L2。FIFO2 的测试输出按 128B 对齐，写入前不让 CPU 访问；输出检查前执行 `stream_fence()`。矩阵程序则通过真实 GPR 和普通 CPU store 累加 C。

## 运行

服务器构建和批量回归：

```sh
cd ~/Zircon-2024-main
sbt --batch 'testOnly StreamSimdTest StreamOuterTest StreamFenceTest'
bash tests/stream-outer/run.sh
```

`run.sh` 包含上述 3 个新程序及原来的 10 个回归。已有最新模拟器时可设置 `SKIP_BUILD=1`，也可在脚本参数中指定单个 `目录/程序名`。

在各程序目录单独运行：

```sh
cd ~/Zircon-2024-main/RV-Software/stream-test
make NAMES=stream-simd-vector run
make NAMES=stream-simd-edges run
cd ../stream-matrix
make NAMES=stream-simd-matrix run
```

## 验证结果

2026-09-29 服务器实测：

- 三个 Chisel 测试套件共 8 项全部通过：SIMD 译码、同源去重、100 组随机 32-bit 运算，以及原有索引、外积恢复、发射顺序、延迟 B 响应 fence 测试。
- 三个新程序全部通过整核 difftest 和软件 golden；矩阵检查全部 1024 个输出。
- 原有 10 个程序全部通过：外积边界、外积矩阵、stream-add、stream-complex-mag、stream-matrix-k128、stream-transpose、FIR32、FFT32、mul-longlong、div。
- `check-simd-trace.py` 确认三个新程序实际执行了预期指令；矩阵为 16384 条 VDOT、每源一次 CFGI/load，计算开始后没有配置指令。边界程序包含真实 `rd=x29` 和 `rd=x0` 的 VDOT。
- 原 `check-matrix-trace.py` 也通过：外积矩阵仍为 4096 条 VOMAC、128 条导出、16 条清零、3 条 fence，没有逐 tile 重配置。

日志在服务器：

| 内容 | 路径 |
| --- | --- |
| 硬件单测 | `/tmp/zircon-simd8-unit.log` |
| 新向量/边界程序及 10 个旧程序 | `/tmp/zircon-outer-regression.2F2rsO` |
| 最终内积矩阵重跑 | `/tmp/zircon-outer-regression.ZEyvRl` |

注意：第一轮矩阵的普通 C golden 使用列方向跨步读取 B，验证耗时过长，运行被主动停止，因此第一轮批量日志保留了一条 `Terminated/FAIL`。随后只把软件 golden 改为按行连续读取 B，保留全量矩阵乘比较，重跑成功；流式计算和 N-D 配置未改变。不要把那条主动终止记录解释成 difftest mismatch，也不要把第一轮批量退出码说成成功。

复核 trace：

```sh
python3 tests/stream-outer/check-simd-trace.py vector /tmp/zircon-outer-regression.2F2rsO/profiling/stream-simd-vector-riscv32/base.log
python3 tests/stream-outer/check-simd-trace.py edges /tmp/zircon-outer-regression.2F2rsO/profiling/stream-simd-edges-riscv32/base.log
python3 tests/stream-outer/check-simd-trace.py matrix /tmp/zircon-outer-regression.ZEyvRl/profiling/stream-simd-matrix-riscv32/base.log
```

这里不把整程序 cycle 当作 kernel 加速比，暂不进行性能结论或 profile 对比。相关修改由三个仓库分别记录，主仓库通过 submodule 指针引用对应版本。
