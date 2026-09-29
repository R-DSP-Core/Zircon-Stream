# N-D 流式地址配置

## 修改摘要

本功能把读流地址从旧的 `word stride + tile stride` 扩展为固定四维描述符。主要修改位于：

```text
主仓库:     Backend/Stream.scala、Frontend/Decode/Decoder.scala、Config/Config.scala
RV-Software: base-port/base/include/stream_instr.h
测试程序:   stream-matrix/src/stream-nd-addr.c
            stream-matrix/src/stream-matrix-k128.c
ZirconSim:  StreamDifftest.h / StreamDifftest.cc / main.cc
```

硬件新增各维 `size/stride/counter`、高维进位和完成状态；软件新增
`cfg_dim_size`、`cfg_dim_stride`；参考模型按同一四维公式独立生成地址。

## 目标

读流使用固定四维地址描述符。维度 0 始终是一条 L2/stream-buffer line，当前为 32 个 word；它的 size 不需要配置，stride 仍可配置。维度 1 到 3 分别配置 size 与 stride。

一条 line 的基地址为：

```
base + cnt1 * stride1 + cnt2 * stride2 + cnt3 * stride3
```

line 内第 `wordCnt` 个 word 的地址为：

```
lineBase + wordCnt * stride0
```

line 结束时从维度 1 向维度 3 逐级进位；最高维进位后，流停止继续取数。

## 指令

`cfg_dim_size(size, dim, fifo)` 使用 `funct3=3, funct7=2`。`dim` 编在 rs2[3:2]，`fifo` 编在 rs2[1:0]；dim=0 被硬件忽略。

`cfg_dim_stride(stride, dim, fifo)` 使用 `funct3=3, funct7=3`，编码相同。stride 的单位是 byte。

`cfg_load` / `cfg_axi_load` 是地址配置的最后一步：保存 base，清零维度计数、完成标志和 FIFO 段计数，从而 arm 一个新的 N-D 读流。软件仅在 dim0 连续时选择 `cfg_axi_load`；硬件不跨更高维合并 AXI burst。

旧的 `cfg_i`、`cfg_stride`、`cfg_tilestride` 保留兼容路径，FFT/FIR 等未迁移程序继续走旧寻址状态。`cfg_stride` 同时镜像到新描述符的 dim0 stride，`cfg_tilestride` 镜像到 dim1 stride；只有执行过新的 `cfg_dim_size` 或 `cfg_dim_stride` 后，该 FIFO 才切换到 N-D 读地址路径。

## stream-matrix 映射

矩阵 A（按行）使用 dim1=M、stride0=4、stride1=K*4。矩阵 B（按列）使用 dim1=N、dim2=M、stride0=N*4、stride1=4、stride2=0。它生成的 line 顺序与原先 `cfg_i + cfg_stride + cfg_tilestride` 的 32x32 测试相同。

## 四维回归

`stream-matrix/src/stream-nd-addr.c` 使用 dim1=dim2=dim3=2，覆盖 8 条 line 的完整三次进位。两个源流的高维 stride 都非零；FIFO 0 的 dim0 stride=4，使用 `cfg_axi_load`，FIFO 1 的 dim0 stride=8，使用 `cfg_load`。simulator 的 `nd-addr` difftest 对每个流乘法独立计算四维参考地址并校验结果。

## M32 N32 K128

`stream-matrix/src/stream-matrix-k128.c` 计算 M32 x N32 x K128，但按 K=32 分为四个 tile 后累加。源 FIFO 有两个 32-word segment：A 的一行占一个 segment 并复用 N=32 次，另一个 segment 可预取下一行，因此保留 ping-pong。每个 tile 中，A 使用 dim1=M、stride1=K*4；B 使用 dim1=N、dim2=M、stride1=4、stride2=0。A 的 dim0 连续走 AXI，B 的 dim0 stride=N*4 走 cache。因此该程序实际走的是完整 N-D 地址路径，而不是旧的 `cfg_stride + cfg_tilestride` 路径。

这里需要区分消费索引和地址索引。每个 K tile 开头的 `cfg_i` 用来重置
`specI/archI` 等计算消费状态，并配合 `cfg_i_limit/repeat/reuse` 描述 FIFO 数据的
消费次数；它不是用来重置 N-D 地址计数器。当前 `cfg_i` 为兼容旧软件还会顺带
写 `dimSizeCfg(1/2)`，所以程序必须先执行 `cfg_i`，再用 `cfg_dim_*` 写回本次真正
使用的四维描述符。最后的 `cfg_load/cfg_axi_load` 更新该 K tile 的 base、清零
`dimCnt` 并重新 arm N-D 地址发生器。

四个 K tile 的 size/stride 实际相同，理论上若移除 `cfg_i` 对 `dimSizeCfg` 的兼容
副作用，后续 tile 只需重置消费状态并更新 load base，无须重复发送全部
`cfg_dim_*`。当前测试重复配置维度是现有接口耦合造成的，不是 N-D 寻址本身要求
每个 tile 重配。

## 验证与性能边界

`stream-nd-addr` 和 M32 N32 K128 矩阵乘均已通过程序 golden 与 StreamDifftest。
N-D 地址生成只替换每条 line 的基地址计算，不改变 dim0 的 32-word AXI burst，
因此理论数据事务数由实际 line 数决定，不会因为描述符从两维扩展到四维而增加。

当前没有为 N-D 地址生成单独保存一组 profile 数字；它的性能意义是用硬件计数器
替代软件逐 tile 重配地址。后续比较应使用相同访存布局，分别统计配置指令数、
STREAM burst 数和矩阵主体 cycles，不能把软件 golden 阶段计入 kernel。
