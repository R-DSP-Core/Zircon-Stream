# N-D 流式地址配置

更新：2026-09-29；依据主仓库 `366f5e71` 和 RV-Software `bf9e58df`。
[公共头](../../../RV-Software/base-port/base/include/stream_instr.h) / [RTL](../../../src/main/scala/Backend/Stream.scala) / [完整指令集](Stream-SIMD指令集.md)。

## 1. 描述符与地址序列

当前固定四维，dim0 大小固定为一个 stream-buffer line，即 32 words，不配置它的 size。
dim1..3 配置 size；四维都配置以字节为单位的 stride：

```text
addr(c0,c1,c2,c3) = base + c0*T0 + c1*T1 + c2*T2 + c3*T3
0 <= c0 < 32; 0 <= cd < Sd (d=1..3)
```

所有地址运算截取低 32 bit。stride 是相对于 base 的该维坐标系数，不是“低维走完之后的补偿步长”。
遍历顺序为 dim0 最快，其次 dim1、dim2、dim3；总数据量为 `32*S1*S2*S3` words。
size 写 0 在当前硬件中被规范为 1，不表示空流。未使用的维度显式写 size=1、stride=0。

普通 load 的 `lineBaseAddr` 只叠加 dim1..3；dim0 由 line 内部 word 位置处理。
`advanceLine` 从 dim1 向高维进位，某维到 size 就清零并向上进位，全部耗尽后置 done。
这里的进位与计算索引的 repeat/reuse 无关。

## 2. 指令编码

两条指令都是 `opcode=0x0b, funct3=011, rd=x0`，参数通过真实 GPR 传入。

| 接口 | funct7 | RF[rs1] | RF[rs2] |
| --- | --- | --- | --- |
| `cfg_dim_size(size, dim, fifo)` | `0x02` | size | `(fifo & 7) | ((dim & 3) << 3)` |
| `cfg_dim_stride(stride, dim, fifo)` | `0x03` | byte stride | 同上 |

**选择器是 GPR 中的值：bits[2:0] 为 FIFO ID，bits[4:3] 为维度。** 不是旧四路版本的 `dim << 2`，也不是把这些值直接塞进机器码 rs2 字段。
例如 dim2/FIFO1 的选择器值为 `17`，rs2 字段仍表示保存 17 的那个 GPR。

`cfg_dim_size` 仅对 dim1..3 生效，dim0 调用被忽略。
有效 dim-size 或任一 dim-stride 配置会启用该流的 N-D 模式。
兼容接口 `cfg_stride` 同时写 dim0 stride，`cfg_tilestride` 同时写 dim1 stride，但它们本身不启用 N-D 模式。
当前没有显式关闭 N-D 模式的公共接口，不能假定重新执行 `cfg_i` 就自动恢复旧模式。

## 3. 选 AXI 还是 L2

- `cfg_axi_load` 使用固定 32-word 连续 burst，因此 dim0 stride 必须为 4；不要求 dim1..3 连续。
- `cfg_load` 使用 L2 word 请求，按 dim0 stride 访问，适合矩阵列读取。
- 地址需满足 word 对齐；AXI 还需由软件保证每个 128B burst 不跨 4KB 边界，当前没有通用拆分器。
- CPU 刚写过的输入可能在 L2 脏行中，应该走 L2；fence 不提供 cache flush。

AXI 在 line 最后一个 R beat 返回后推进地址计数；L2 在最后一个 word 请求被接受后推进请求侧 line 计数。
buffer refill 的就绪/覆盖条件独立检查，地址生成不是无限提前运行的。

## 4. 配置顺序与重置范围

推荐一次任务的配置顺序：计算索引与 reuse -> 四维描述符 -> load/store base。不要在任务仍使用旧配置时覆盖普通 load 的描述符。

| 指令 | 主要修改 | 不应误以为它会做的事 |
| --- | --- | --- |
| `cfg_i(outer,length,fifo)` | 计算 iCnt 清零，设置旧 length/outer；dim1 默认 `max(length/32,1)`、dim2 默认 `max(outer,1)`；X/Y 写 iCnt 也清零 | 不是清空全部 ready、repeat、地址状态或关闭 N-D |
| `cfg_i_limit(L,fifo)` | 设置静态 L 和动态 iLimit；X/Y 读写侧都更新 | 不推进访存描述符 |
| `cfg_i_repeat(R,fifo)` | 设置静态 R，动态 iRepeat 清零；X/Y 读写侧都更新 | 不等同于每项 reuse |
| `cfg_i_offset(O,fifo)` | 配置计算窗口偏移，单位 word | 不是内存 byte stride |
| `cfg_dim_*` | 修改 size/stride 并启用 N-D | 不启动访存或清空已有数据 |
| 普通 `cfg_load/cfg_axi_load` | 设置 base/接口，重置 dimCnt/done、burst 和 outer 地址计数 | 不替代计算索引配置，不普遍清空 ready |

所以应先执行 `cfg_i` 再写显式 size，避免它覆盖 dim1/dim2。
CFG 在 MDU 按程序序执行；这保证指令顺序，不保证后台访存已经结束。`stream_fence()` 可用于已满足前提的配置阶段边界，不能代替任意活动流的停止/重启协议。

FIFO0/1 使用当前描述符，不是每次 load 都完整快照一份。
FIFO3 特殊：先配 load 描述符并 `cfg_axi_load(base,3)` 快照，再配 store 描述符并 `cfg_store(base,3)` 快照，两份地址状态独立运行。只能在前一任务结束后重启。
FIFO2 仍使用原连续 AXI store 路径，不能把这套 load N-D 机制直接理解为 FIFO2 已支持任意 stride store。

## 5. 内积矩阵 M32 N32 K128

[标量程序](../../../RV-Software/stream-matrix/src/stream-matrix-k128.c) 与 [SIMD 程序](../../../RV-Software/stream-matrix/src/stream-simd-matrix.c) 都一次配置全部 K blocks，计算循环里不重配置。
遍历顺序：4 个 K32 block -> 32 行、32 列输出 -> 当前 32 项内积；SIMD 每次用 4 条 VDOT8。

| 维度 | A size | A byte-stride | B size | B byte-stride |
| --- | --- | --- | --- | --- |
| dim0：K block 内元素 | 固定 32 | 4 | 固定 32 | 128 |
| dim1 | 32 行 | 512 | 32 列 | 4 |
| dim2 | 4 个 K block | 128 | 重复 32 行 | 0 |
| dim3 | 1 | 0 | 4 个 K block | 4096 |

A 的计算索引 `limit=32, repeat=32, offset=0, reuse=32`：一行 32 项留在 buffer 内被 N 列重复消费。
B 为 `limit=32768, repeat=1, offset=0, reuse=1`，依次读列窗口；每个输出行重新走一次对应 B 区域。
A 共搬入 4096 words，B 共搬入 131072 words。B 的 stride=0 高维是再次访存，不是缓存整个 B。
两个 32-word 段仍可交替 refill/消费，不需要逐个 K block 清空重配。

SIMD 程序的 A/B 由 CPU 初始化，所以两路均用 L2；VDOT 返回 GPR，普通 CPU 加法和 store 累加 C。
整个矩阵为 `4*32*32*4=16384` 条 VDOT，不依赖软件 pack。

## 6. 外积矩阵 M32 N32 K32

[实际程序](../../../RV-Software/stream-matrix/src/stream-outer-matrix.c) 将输出分成 16 个 8x8 tile。
CPU 将 A/B 各 pack 成 4 个 panel，每个 panel 为 32x8 words，按 k 后 lane 存放；两路均从 L2 读取。

| 维度 | size | A byte-stride | B byte-stride |
| --- | --- | --- | --- |
| dim0 | 固定 32 | 4 | 4 |
| dim1：panel 内 line | 8 | 128 | 128 |
| dim2：列 tile | 4 | 0 | 1024 |
| dim3：行 tile | 4 | 1024 | 0 |

A：`limit=4096, repeat=1, reuse=1`。B：`limit=8, repeat=8, reuse=8`。两者 offset=0。
每个 k 的 8 条 OMAC 依次消费 A 的 8 项，B 的同一个 8-word 窗口重复使用 8 次。
每个 tile 先 VCLR，再 256 条 OMAC，最后 8 条 VREGOUT；全矩阵共 4096/128/16 条 OMAC/导出/清零。
A/B 各访问 4096 words，FIFO2 连续输出 1024 words，以 tile-major 保存。
配置和 fence 不放在 tile 循环中，`stride=0` 的 panel 重读由硬件四维计数器完成。

## 7. 转置与限制

[32x32 转置程序](../../../RV-Software/stream-matrix/src/stream-transpose.c) 的 FIFO3 配置：

| 描述符 | dim0 stride | dim1 size / stride | dim2、dim3 |
| --- | --- | --- | --- |
| load | 4 | 32 / 128 | size=1、stride=0 |
| store | 128 | 32 / 4 | size=1、stride=0 |

load 按行 AXI 读取，store 将同一批 32 项按目标列地址发给 L2；不需要额外 tile SRAM。
src/dst 按 128B 对齐，1024 项对应 32 次 load burst 和 1024 个 L2 word 写请求。

当前无任意 dim0 长度、尾段掩码或通用异步任务完成寄存器。FIFO3 的整体完成也尚未纳入 fence 判定，测试仍保留固定等待；详见 [总结文档第 6、7 节](流式访存总结文档.md)。

## 8. 验证入口

```sh
cd RV-Software/stream-matrix
make NAMES=stream-simd-matrix run
make NAMES=stream-outer-matrix run
make NAMES=stream-matrix-k128 run
```

[内积 trace 检查器](../../../tests/stream-outer/check-simd-trace.py) 与 [外积 trace 检查器](../../../tests/stream-outer/check-matrix-trace.py) 不只看数值，还检查计算阶段没有重新配置以及实际指令数量。
验证记录见 [SIMD8](../simd8-implementation-20260929.md) 和 [外积合入](../outer-merge-20260929.md)；本文更新不代表重新运行了硬件回归。
