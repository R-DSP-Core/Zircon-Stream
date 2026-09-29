# Stream-SIMD 指令集与示例索引

更新：2026-09-29。本文是**当前已实现接口**，包含外积、逐元素 SIMD 和内积，不是早期候选编码。
依据：主仓库 `366f5e71`、RV-Software `bf9e58df`、ZirconSim `6a21bcfc`。
唯一工程公共头为 [stream_instr.h](../../../RV-Software/base-port/base/include/stream_instr.h)，本文不另复制一套 `stream.h`。

## 1. 先看计算语义

令 `A[l]`、`B[l]` 分别为源流 a、b 从各自当前读索引开始的第 l 项，`D[l]` 为目的流 d 从当前写索引开始的第 l 项；`l=0..7`，每项 32 bit。
`V[v][l]` 表示第 v 个外积累加器 bank 的第 l 项。

| 公共头接口 | 计算语义 | 读取 / 写入与索引步长 |
| --- | --- | --- |
| `stream_vadd8(a,b,d)` | `D[l] = A[l] + B[l]` | 两个源各取连续 8 项，目的写连续 8 项 |
| `stream_vsub8(a,b,d)` | `D[l] = A[l] - B[l]` | 同上 |
| `stream_vmul8(a,b,d)` | `D[l] = A[l] * B[l]` | 同上；a=b 时逐项平方 |
| `stream_vdot8(a,b)` | 返回 `sum(A[l]*B[l])` | 两个源各取 8 项；一个结果写真实 GPR，不读取旧 rd |
| `stream_vomac8(a,b,v)` | `V[v][l] += A[0]*B[l]` | A 取 1 项广播，B 取 8 项；结果留在 bank v |
| `stream_vclr()` | 所有 `V[v][l] = 0` | 清零全部 8 个 bank，不修改 stream 索引 |
| `stream_vregout8(v,d)` | `D[l] = V[v][l]` | 目的写连续 8 项，不清空 bank，无源 stream 消费 |

上述加减乘和归约均按模 2^32 运算，不是浮点或 64-bit 累加。
长内积使用 `sum += stream_vdot8(0,1)`，加到旧 sum 的动作是普通 CPU 指令，VDOT 本身没有隐藏的第三个 GPR 源。
OMAC 则是真正累加到 VReg，每条更新一个 bank 的 8 个 lane；8 条 OMAC 可更新一个 8x8 子块的一层 K。

## 2. Stream Buffer 与 VReg

| ID / 结构 | 作用 |
| --- | --- |
| FIFO0、FIFO1 | AXI 或 L2 load，提供计算操作数；保留原 FFT PP 路径 |
| FIFO2 | 计算结果输出，凑满 32 words 后写 AXI |
| FIFO3 | AXI load -> L2 store 的 layout transform，不参与这里的算术指令 |
| FIFO4、FIFO5，即 X/Y | 计算生产、计算消费的中间流，不接内存；各有独立读写索引 |
| VReg bank 0..7 | 外积累加器，每 bank 8 x 32 bit，与 stream ID 分开 |

每个 stream buffer 是 64 x 32-bit 寄存器数组、两个 32-word 段，不是多 bank SRAM。
只有外积需要的累加状态保存在 VReg；VADD/VMUL 等通过 stream buffer 传递操作数和结果，VDOT 返回 GPR，不需要先写 VReg 再归约。

当前 SIMD 源流允许 `{0,1,4,5}`，目的流允许 `{2,4,5}`；头文件做编译期检查。
两个源可以相同，同一条指令只消费窗口并集，例如 `stream_vmul8(0,0,4)` 将 8 项平方写 X，只消费源流 8 words。
OMAC 同源时只消费 8 words，不是 1+8=9。
逐元素 SSS 的目的流不能等于任一源流，不支持原地覆盖。

窗口可跨 32-word line 和 64-word 环形边界，没有“起点必须 8 对齐”的限制。
但窗口内连续取数，不能中途跨逻辑 limit/repeat 边界再跳到另一个窗口；索引配置应保证单窗口属于同一次逻辑迭代。
`cfg_i_repeat` 控制窗口何时重复/推进，`cfg_reuse` 控制数据还能使用几次，不是一个概念。

## 3. 32-bit 指令格式与 funct3

自定义计算/配置使用 R-format，opcode 固定 `0x0b`：

```text
31       25 24    20 19    15 14   12 11     7 6       0
  funct7      rs2      rs1     funct3     rd      opcode
    7          5        5        3         5         7
```

`funct3` 先区分编码类别，再由 `funct7` 选具体操作。不能仅凭字段叫 rs1/rs2/rd 就断定一定读写 GPR。

| funct3 | 类别 | 当前包含 |
| --- | --- | --- |
| `000` | 计算索引配置 | CFGI、LIMIT、REPEAT、OFFSET |
| `001` | store 地址配置 | CFGSTORE |
| `010` | stream 结果计算 | 标量 SSS、原 FFT SSP、SIMD 逐元素 SSS |
| `011` | 访存步长/维度配置 | STRIDE、TILESTRIDE、DIMSIZE、DIMSTRIDE |
| `100` | 剩余次数配置 | REUSE、原 FIR REUSEEMPTY |
| `101` | load 地址与接口配置 | L2 load、AXI load |
| `110` | 原 RRS/PP 与外积操作 | RRS、VOMAC8、VCLR、VREGOUT8，由 funct7 区分 |
| `111` | 结果写 GPR | 标量 SSR、VDOT8 |

SSS 表示 stream-stream-stream；SSR 表示两个 stream 源、一个 GPR 结果。原 RRS 接收两个真实 GPR，其目的由 PP 路径处理。
八种 funct3 已有用途，但每类还有独立的 funct7 编码空间，不代表整套 ISA 只剩 8 个操作。

## 4. 计算编码与字段角色

表内 funct7 为十六进制。stream ID/bank 占对应 5-bit 字段的低 3 位，高 2 位必须为 0。
真实 GPR 则使用完整 5-bit 编号。

| 接口 / 操作 | funct3 | funct7 | rd | rs1 | rs2 |
| --- | --- | --- | --- | --- | --- |
| `cal_stream_add/sub/mul(a,b,d)` | `010` | `00 / 08 / 10` | 目的流 d | 源流 a | 源流 b |
| `cal_stream_rd_add/sub/mul(a,b)` | `111` | `00 / 08 / 10` | 真实 GPR | 源流 a | 源流 b |
| `cal_stream_pp(a,b,d)` | `010` | `01` | 非 GPR，目的由旧 PP 路径映射 | 源流 a | 源流 b |
| `cal_rjrk_stream_pp(lo,hi)` | `110` | `00` | x0 | 真实 GPR | 真实 GPR |
| `stream_vadd8(a,b,d)` | `010` | `40` | 目的流 d | 源流 a | 源流 b |
| `stream_vsub8(a,b,d)` | `010` | `48` | 目的流 d | 源流 a | 源流 b |
| `stream_vmul8(a,b,d)` | `010` | `50` | 目的流 d | 源流 a | 源流 b |
| `stream_vdot8(a,b)` | `111` | `60` | 真实 GPR | 源流 a | 源流 b |
| `stream_vomac8(a,b,v)` | `110` | `40` | bank v | 源流 a | 源流 b |
| `stream_vclr()` | `110` | `41` | x0 | x0 | x0 |
| `stream_vregout8(v,d)` | `110` | `42` | 目的流 d | bank v | x0 |

原 SSP 为加法写 Even PP，原 RRS 为两个 GPR 按位 OR 写 Odd PP，服务旧 FFT；不是这次新增的 SIMD PP。
`cal_stream` 是标量加法别名，不代表动态选择 op。

对于普通 `funct3=010/111` 算术，已使用的 funct7 可以这样理解：

```text
funct7[6]   : 0 标量，1 SIMD8
funct7[5:3] : 000 ADD，001 SUB，010 MUL，100 DOT
funct7[2:0] : 普通算术当前为 000
```

**这只是普通算术的编码规律，不是全局可自由组合的编码模板。** 实际合法组合以上表为准：DOT 目前只有 SSR，向量 ADD/SUB/MUL 目前只有 SSS；PP、CFG、外积三条另有精确编码。
特别是 `110/40` 是 OMAC，不能用同样的位拆分把它解释成 VADD。
内部 `sinfo.op` 当前为 5 bit，它是 RTL 内部枚举，不等于机器码的 funct7；扩充内部枚举不改变指令字段宽度。
未列出组合不要使用，也不承诺当前译码器对所有未定义码都产生非法指令异常。

### 4.1 汇编中的 x0/x1 不一定是寄存器读取

```asm
.insn r 0x0b, 2, 0x50, x4, x0, x0   # FIFO0 的 8 项平方写 FIFO4，不读取 GPR x0
.insn r 0x0b, 7, 0x60, t4, x0, x1   # FIFO0/1 点积，结果写真实 t4
.insn r 0x0b, 6, 0x40, x3, x0, x1   # FIFO0 标量 * FIFO1 向量，累加到 bank3
.insn r 0x0b, 6, 0x42, x2, x3, x0   # bank3 导出到 FIFO2
```

VDOT 的 rd=x0 会丢弃 GPR 结果，但源流仍正常消费。
公共头通过字符串生成 stream ID 字段，所以要求数字字面量或展开后为数字的宏；不能传运行时变量，也不要传枚举名/算术表达式来冒充立即编码。
这条限制不适用于 CFG 参数，后者通过真实 GPR 的值传入。

## 5. CFG 完整索引

以下 rd 均为 x0。除 `cfg_reuse_empty()` 外，rs1/rs2 都是真实 GPR。

| 接口 | funct3 | funct7 | RF[rs1] | RF[rs2] |
| --- | --- | --- | --- | --- |
| `cfg_i(outer,length,fifo)` | `000` | `00` | 高 16 位 length、低 16 位 outer | fifo |
| `cfg_i_limit(L,fifo)` | `000` | `01` | 计算窗口长度 L，word | fifo |
| `cfg_i_repeat(R,fifo)` | `000` | `02` | 窗口重复次数 R | fifo |
| `cfg_i_offset(O,fifo)` | `000` | `03` | 重复时的窗口偏移 O，word | fifo |
| `cfg_store(base,fifo)` | `001` | `00` | base 地址 | fifo |
| `cfg_stride(T,fifo)` | `011` | `00` | word byte-stride，兼容接口 | fifo |
| `cfg_tilestride(T,fifo)` | `011` | `01` | line byte-stride，兼容接口 | fifo |
| `cfg_dim_size(S,dim,fifo)` | `011` | `02` | size，仅 dim1..3 | `(dim<<3) | fifo` |
| `cfg_dim_stride(T,dim,fifo)` | `011` | `03` | byte-stride，dim0..3 | `(dim<<3) | fifo` |
| `cfg_reuse(R,fifo)` | `100` | `00` | 每 word 剩余使用次数 | fifo |
| `cfg_reuse_empty()` | `100` | `01` | 无，rs1=x0 | 无，rs2=x0 |
| `cfg_load(base,fifo)` | `101` | `00` | L2 load base | fifo |
| `cfg_axi_load(base,fifo)` | `101` | `01` | AXI load base | fifo |

dim 选择器在 GPR **值**中占 bits[4:3]，fifo 占 bits[2:0]，头文件会做位掩码。
dim0 size 固定为 32；AXI 只能按 dim0 连续 32-word burst 读取，是否用 AXI 由软件选择，不自动判断高维连续性。

`cfg_load` 用于 FIFO0/1；`cfg_axi_load` 用于 FIFO0/1/3。FIFO2 的 `cfg_store` 为原 AXI 输出，FIFO3 的 `cfg_store` 快照 L2 layout store 描述符。X/Y 不配置访存地址。
`cfg_reuse_empty` 是 FIFO1 的 FIR 专用延迟清理，不是任意 stream 的 reset。
详见 [N-D 配置、启动顺序与矩阵表格](N-D流式地址配置.md)。

## 6. 可见性、完成与未实现部分

向量计算在 issue 预留源额度/目的窗口，逐元素结果和 VReg 导出在 ROB 提交后才写目的 FIFO，防止错误路径输出直接触发 AXI store。
VReg 有推测态和提交态，导出保留当时的快照。VADD/VSUB/VMUL/VDOT 不修改 VReg。

`stream_fence()` 使用标准 `fence rw,rw`，opcode 是 `0x0f`，不占上述自定义编码。
它等待较早 ROB 指令、CPU store buffer 与 stream 当前排空条件，并等待 FIFO2 的 AXI B 响应；不是只等 W 发完。
新向量/外积宏和 fence 带编译器 `memory` clobber，不应由此推断所有历史标量/CFG 宏都有相同编译器屏障。

两类注意事项：

- CPU 写入或 pack 后的输入选 L2；fence 不 flush/invalidate cache。直接 AXI 输出应避免 CPU 提前缓存目标，且必须凑满 32-word line。
- 当前 fence 不完整跟踪 FIFO3 load 描述符的结束，不能充当整个异步转置任务的完成通知；转置测试的固定等待尚未替换。

本版本不含向量-标量加减、掩码/短尾、可变 VL、浮点、饱和、宽累加、VDOT 写流、SIMD PP、VECMAC 或状态查询指令。旧草案里保留过的名字/编码不等于实现承诺。

## 7. 示例与测试索引

示例直接阅读源码中的配置与计算循环，避免维护第二套失真的示例头文件。

| 示例 | 重点 |
| --- | --- |
| [公共头及接口注释](../../../RV-Software/base-port/base/include/stream_instr.h) | 所有真实宏、CFG 与编码 |
| [向量加、减、乘和平方](../../../RV-Software/stream-test/src/stream-simd-vector.c) | 128 项测试、X/Y 串接、FIFO2 输出、fence 与 golden |
| [SIMD 边界](../../../RV-Software/stream-test/src/stream-simd-edges.c) | 跨 line/ring、同源 DOT、真实 GPR 目的、内外积混用 |
| [内积 M32 N32 K128](../../../RV-Software/stream-matrix/src/stream-simd-matrix.c) | A/B 一次 N-D 配置，16384 VDOT，结果由 CPU 累加 |
| [外积 M32 N32 K32](../../../RV-Software/stream-matrix/src/stream-outer-matrix.c) | CPU pack、L2 输入、B 窗口复用、8x8 bank、导出与完整 golden |
| [外积边界](../../../RV-Software/stream-matrix/src/stream-outer-edges.c) | OMAC/导出/X/Y/清零等交互 |
| [现有 FIR32，标量](../../../RV-Software/FIR32/src/fir32.c) | 滑动窗口、offset/reuse_empty；尚无已验证 SIMD FIR 版本 |
| [现有复数模平方，标量](../../../RV-Software/stream-test/src/stream-complex-mag.c) | 同源平方与 X/Y，R64 D32 A4；不是 SIMD A8 测试 |

在对应目录单独运行：

```sh
cd RV-Software/stream-test
make NAMES=stream-simd-vector run
make NAMES=stream-simd-edges run
cd ../stream-matrix
make NAMES=stream-simd-matrix run
make NAMES=stream-outer-matrix run
```

全量入口为 [tests/stream-outer/run.sh](../../../tests/stream-outer/run.sh)。
最新实现分别有 [外积合入记录](../outer-merge-20260929.md)、[SIMD8 与内积记录](../simd8-implementation-20260929.md)，记录逐指令 difftest、软件 golden、硬件单测及 trace 数量检查。
当前有功能验证，没有新的综合时序、面积或 kernel 加速比结论。
