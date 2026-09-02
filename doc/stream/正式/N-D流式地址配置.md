# N-D 流式地址配置

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
