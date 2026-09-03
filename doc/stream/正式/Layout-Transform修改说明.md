# Layout Transform 修改说明

## 1. 目标与当前方案

本次修改为 Stream Engine 增加一个最小可运行的 layout transform 通路，当前用
32 x 32 矩阵转置验证。

整体数据流为：

```text
主存 --AXI burst read--> FIFO3 --32-bit L2 write--> L2 Cache
```

FIFO 的职责保持明确分离：

| FIFO | 当前职责 | 读写路径 |
| --- | --- | --- |
| FIFO0 | 计算源流 0 | AXI 或 L2 load |
| FIFO1 | 计算源流 1 | AXI 或 L2 load |
| FIFO2 | 计算结果 store buffer | AXI store |
| FIFO3 | layout transform 的 LDST buffer | AXI load + L2 store |

FIFO3 不是在 load 模式和 store 模式之间切换。AXI 可以继续向空 segment
填数据，L2 同时从另一个已经 ready 的 segment 取数据写出。

FIFO2 的 AXI store 和 FIFO3 的 L2 store 使用不同接口，因此没有增加
FIFO2/FIFO3 store 选择器。若 FIFO3 的 L2 写 miss 最终需要访问主存，仍由原有
AXI Arbiter 处理 L2 和其他 AXI 请求之间的竞争。

## 2. 软件配置协议

没有增加新的指令编码，继续复用：

```c
cfg_dim_size(size, dim, fifo_id);
cfg_dim_stride(stride, dim, fifo_id);
cfg_axi_load(src_addr, 3);
cfg_store(dst_addr, 3);
```

FIFO3 需要同时保存输入和输出两套 N-D 描述符，因此配置顺序有明确要求：

```text
1. 配置 FIFO3 的输入 dim size/stride
2. cfg_axi_load(src, 3)，锁存输入描述符并启动 AXI 读取
3. 重新配置 FIFO3 的输出 dim size/stride
4. cfg_store(dst, 3)，锁存输出描述符并启动 L2 写出
```

`cfg_axi_load` 和 `cfg_store` 分别快照描述符，后续修改公共的
`dimSizeCfg(3)`/`dimStrideCfg(3)` 不会破坏正在执行的输入流。

当前输入只支持 dim0 连续的 AXI line：

```text
lineBase = loadBase + sum(loadCnt[d] * loadStride[d]), d = 1..3
```

一个 line 固定为 32 个 word，AXI beat 在 line 内自然按 4 byte 递增。因此输入
地址生成不使用 dim0 stride，软件仍配置 `dim0 stride = 4` 表达其连续性。

输出按完整四维描述符逐 word 计算：

```text
storeAddr = storeBase + sum(storeCnt[d] * storeStride[d]), d = 0..3
```

每当一个写请求被 L2 Stage 1 接收，输出计数器从 dim0 开始进位，并移动到
FIFO3 的下一个 word。后续 `wrsp` 表示该请求已在 L2 完成，不再反压 FIFO3
发送端。

## 3. 主硬件仓库修改

### 3.1 `Config.scala`

在 `FifoRole` 中增加：

```scala
val Layout = 3
```

后续代码使用 `Layout` 表示 FIFO3，使用 `Dst` 表示原 FIFO2，避免继续散布数字
常量。

### 3.2 `Stream.scala`

#### L2 接口扩展

原 `SEL2IO` 只有 stream 到 L2 的读接口，本次增加：

```scala
val wreq  = Output(Bool())
val wrsp  = Input(Bool())
val wdata = Output(UInt(32.W))
```

`paddr` 和 `mtype` 继续由读写共用。当前 layout store 固定执行 32-bit word write，
所以 `mtype = 2`。

#### ready map 扩展

原 `storereadyMap` 只属于 FIFO2：

```scala
Vec(fifoWord, Bool)
```

现在改为每个 FIFO 一份：

```scala
Vec(streamNum, Vec(fifoWord, Bool))
```

实际使用者为：

| ready map | 置位者 | 清零者 |
| --- | --- | --- |
| `storereadyMap(Dst)` | 计算流水线 writeback | FIFO2 AXI store response |
| `storereadyMap(Layout)` | FIFO3 AXI read response | FIFO3 L2 Stage 1 接收写请求 |

因此 FIFO3 的 ready 含义是“该位置已由 load 填好，可以交给 store”，不是计算结果
已写回。

#### 输入、输出描述符

新增两组独立状态：

```text
layoutLoadBase / layoutLoadDimSize / layoutLoadDimStride / layoutLoadDimCnt
layoutStoreBase / layoutStoreDimSize / layoutStoreDimStride / layoutStoreDimCnt
```

同时增加 active、done 和 FIFO read index 等控制寄存器。

执行 `cfg_axi_load(..., 3)` 时：

- 锁存输入描述符；
- 清零输入维度计数和 FIFO3 ready map；
- 清零 FIFO3 读指针及未完成的 store 状态；
- 启动 layout AXI load。

执行 `cfg_store(..., 3)` 时：

- 锁存输出描述符；
- 清零输出维度计数；
- 启动 layout L2 store；
- 不覆盖 FIFO3 正在使用的 load 状态。

#### FIFO3 的 AXI load

FIFO3 参与原有 AXI load 选择。当前策略是：

- FIFO0/FIFO1 仍由原 `LoadSelect` 选择；
- FIFO3 有空 segment 时成为额外候选；
- FIFO3 的已完成 burst 数更少时可以优先；
- burst 数相同时保留原 load 的优先级。

FIFO3 有两个 32-word segment。只有一个 segment 的 32 个 ready bit 全部为 0
时，AXI 才能重新填充该 segment。ready bit 在对应写请求被 L2 Stage 1 接收后
清零，此时地址和数据已经由 L2 流水线持有，FIFO3 槽位可以安全复用。

AXI 返回每个 word 时：

```text
Fifo(3)(index) = rdata
storereadyMap(3)(index) = true
```

一个 AXI line 完成后，输入描述符只推进 dim1..dim3。

#### FIFO3 的 L2 store

当前实现遵守“先等当前完整 line ready，再开始写”的约束：

```text
32 个 ready bit 全为 1 -> layoutStoreLineReady
```

line 启动后，`layoutStoreLineActive` 保持有效，STE 逐个发送普通 L2 写请求。这里
没有新增 scatter 总线，一个 word 就是一组普通信号：

```text
wreq + paddr + wdata + mtype
```

地址可能不连续是 N-D descriptor 的结果，不是 L2 接口提供了特殊的分散写操作。

发送方式复用原有 DCache 到 L2 的写流水交互。满足 `!miss`、DCache 未占用
Stage 1 且没有 `dcHazard` 时，当前 FIFO3 写请求被 L2 接收，并立即：

- 清除对应 ready bit；
- 推进 FIFO read index；
- 推进输出 N-D 计数器；
- 准备在下一可用周期发送下一个 word。

不再使用 `layoutStorePending` 等待每个 `wrsp`。L2 Channel 2 自己的流水寄存器
负责保存已经接收的地址和数据；发生 miss 或 hazard 时，现有反馈信号会冻结 STE
发送端。`wrsp` 仍用于标识 L2 写完成以及驱动仿真 reference memory 同步。

`layoutStoreLineActive` 很重要。若每个 word 都重新要求“整 line ready”，第一个
word 写完并清除 ready bit 后，剩余 31 个 word 会永久无法继续。

在 STE 内部，FIFO3 layout store 优先于已有的 stream L2 load：

```scala
io.l2.rreq := loadValidReg && !layoutStoreReq
```

进入 L2 后，仍保留原来的 DCache 优先级。

### 3.3 `L2Cache.scala` 和 `L2CacheFSM.scala`

L2 Channel 2 原来允许 DCache read/write 和 Stream Engine read。本次允许
Stream Engine 也提交 write。

Channel 2 Stage 1 的选择关系为：

```text
DCache 有 rreq/wreq -> 使用 DCache 请求
否则 STE 有 rreq/wreq -> 使用 STE 请求
```

对 STE write 必须同时选择 STE 的 `paddr`、`mtype` 和 `wdata`。流水线中的
`seRreq` 位目前实际承担“请求来自 Stream Engine”的来源标记，因此同样用于
区分 STE write response：

```scala
io.se.wrsp := !missC2 && c2s3.wreq && c2s3.seRreq
```

本次没有改变 L2 的 write-back 策略。STE 写入命中的 L2 line 后，数据保留在
L2 并按原逻辑设置 dirty，不会因为新增 `wrsp` 而立即写入 DDR。

#### 连续 STE 写流水

最初接入时，L2 只要在 Channel 2 Stage 3 处理写请求，就会通过 `dcHazard`
阻止新请求进入。即使 FIFO3 已经在 Stage 1 交出当前 word，后续 word 仍只能按
“接收两笔、停两拍”的节奏进入 L2，平均约每两周期接收一笔写。

现在参照 DCache commit-write 的三级流水，将连续 STE write 的 tag 查询和 data
写入拆到同一块双口 RAM 的不同操作上：

```text
当前 STE write：Channel 2 Stage 3，写 data RAM
下一笔 STE write：Channel 2 Stage 1，读 tag RAM
```

由于 hit write 不需要读取旧 data line，二者可以在同一周期进行。连续命中时，
L2 因而可以每周期从 STE 接收一个 32-bit write。

流水化后需要额外保护 write miss。Stage 2 一旦发现 miss，`c2s2MissHazard` 会在
`missC2` 寄存器下一拍置位前立即阻止更年轻的请求进入。若 miss 请求的 victim
data 曾因前一笔 Stage 3 写而没有在原 tag 查询周期读出，FSM 会进入新增的
`mVictim` 状态，用一拍重新读取 victim line，再启动原有 dirty write-back 和
write-allocate 流程。这样连续 hit 可以满流水，miss 时仍不会回写错误的 victim
数据。

另外增加 `L2StreamWriteDBG`：

```text
valid / paddr / wdata / wstrb
```

它只用于仿真器观察一次 STE L2 write 已经完成，不参与 RTL 的功能控制。

### 3.4 `CPU.scala`

仿真模式下，将 L2 的 `streamWrite` 完成事件接到 `CPUDebugIO`，供 Verilator
侧读取。综合模式下不使用该调试事件。

## 4. RV-Software 修改

新增：

```text
stream-matrix/src/stream-transpose.c
```

测试矩阵为 32 x 32，输入元素为互不相同的 `1..1024`。

输入描述符：

```text
dim0: 固定 32 word，stride = 4 byte
dim1: size = 32，stride = 32 * 4 byte
dim2: size = 1，stride = 0
dim3: size = 1，stride = 0
```

输出描述符：

```text
dim0(c): 固定 32 word，stride = 32 * 4 byte
dim1(r): size = 32，stride = 4 byte
dim2:    size = 1，stride = 0
dim3:    size = 1，stride = 0
```

FIFO3 的数据消费顺序仍是输入的 `src[r][c]`，输出地址为：

```text
dstBase + c * 128 + r * 4
```

测试中的 `src` 和 `dst` 都使用 `__attribute__((aligned(128)))` 显式按 L2 line
对齐。这样每个 4KB 矩阵严格覆盖 32 条 128B cache line，不会因为首地址带行内
偏移而额外触及第 33 条 line。

因此最终内存满足：

```c
dst[c * 32 + r] == src[r * 32 + c]
```

程序逐个检查全部 1024 个元素。当前 ISA 没有 layout completion/fence 指令，
所以检查前暂时执行 50000 次 `nop`，给异步搬运留出完成时间。

## 5. ZirconSim 修改

修改文件：

```text
src/Emulator.cc
```

RTL 的 L2 是 write-back Cache，而 C++ 指令参考模型没有 Cache 层。若不处理：

- DUT 的 CPU load 可以从 L2 dirty line 读到转置后的值；
- C++ reference CPU 仍会从 `refMemory` 读到初始值；
- 普通 load 指令会发生假的 RF mismatch。

因此仿真器在 `io_dbg_streamWrite_valid` 有效时执行：

```cpp
memory->refMemoryWrite(paddr, wdata, wstrb);
```

这里更新的是 C++ 的架构参考内存，不是 DUT 的 AXI 主存，也没有把 RTL L2 改成
write-through。

当前 `stream-transpose` 的 StreamDifftest mode 仍为 `none`。正确性由两层保证：

1. 每次 DUT 的 STE L2 write 完成事件同步到 reference memory，使普通 CPU
   指令 difftest 看到一致的架构内存；
2. C 程序使用转置公式检查完整目标矩阵，错误地址、错误数据和漏写都会触发
   `check()` 失败。

这还不是完全独立的逐事件 golden model，因为 reference memory 接收的是 DUT
实际产生的地址和数据。它不能发现“多发了最终不影响矩阵结果的写请求”等内部
问题。更严格的版本可以增加 `transpose` difftest mode，根据配置独立生成预期的
1024 组 `address + data`，逐个比较 L2 write completion。

## 6. 验证与 profiling

### 6.1 生成功能测试日志

服务器执行：

```bash
cd ~/Zircon-2024-main/RV-Software/stream-matrix
make NAMES=stream-transpose run
```

结果：

```text
SIMULATION ENDED SUCCESSFULLY.
stream-transpose: [ACCEPT]
Total cycles: 111777
Total insts: 160602
IPC: 1.43681
```

硬件 `make verilog` 也已通过。构建中仍有原项目已有的 dynamic Vec index width
警告，本次修改没有新增 elaboration error。

运行 `VCPU` 时，`ZirconSim/src/Emulator.cc` 会在以下目录生成本次仿真的三份
原始日志：

```text
ZirconSim/profiling/stream-transpose-riscv32/
  base.log       # 指令各流水级时间
  cachelog.log   # DCache/L2 miss 记录
  timeline.log   # AXI read start/end 事件
```

这些文件在每次重新运行同名程序时被覆盖。因此要比较不同硬件版本，应在下一次
仿真前另存对应的 `timeline.json`。

### 6.2 生成 profiling 结果

功能测试结束后，在服务器执行：

```bash
cd ~/Zircon-2024-main/ZirconSim
bash profile.sh stream-transpose
```

服务器上的 `profile.sh` 是便捷包装脚本，目前不属于仓库跟踪文件。其他环境没有
该脚本时，可直接执行完全等价的四条命令：

```bash
python3 trace.py stream-transpose
python3 trace-cache.py stream-transpose
python3 trace-to-konata.py stream-transpose
python3 trace_timeline.py stream-transpose
```

这些命令不会重新运行仿真，而是依次处理上一步生成的日志：

| 阶段 | 脚本 | 输入 | 主要输出 |
| --- | --- | --- | --- |
| 1 | `trace.py` | `base.log` | `pipeline_stage_stats.csv`、`instrview.csv`、`block_cycles.csv` |
| 2 | `trace-cache.py` | `cachelog.log` | `cache-trace-L1.json`、`cache-trace-L2.json` |
| 3 | `trace-to-konata.py` | `base.log` | `instructions.log` |
| 4 | `trace_timeline.py` | `timeline.log` | `timeline.json` |

本次运行最终生成：

```text
profiling/stream-transpose-riscv32/timeline.json，共 104 条记录
```

需要归档到本地时，可以在本地终端执行：

```bash
scp -P 2222 \
  luoteng@10.2.151.232:~/Zircon-2024-main/ZirconSim/profiling/stream-transpose-riscv32/timeline.json \
  timeline-fully-pipelined.json
```

### 6.3 timeline 字段与事件含义

`timeline.json` 中每条记录的主要字段为：

```text
tid   = STREAM / INST / DATA
ts    = 事务开始周期
dur   = AXI 事务持续时间计数
name  = 起始地址_该类型事件序号
```

三类事件来自 AXI Arbiter 的读通道：

| `tid` | 含义 |
| --- | --- |
| `STREAM` | Stream Engine 发起的 AXI load burst |
| `INST` | L2 指令侧 miss refill |
| `DATA` | L2 数据侧 miss refill，包括 store miss 引起的 write-allocate |

因此 FIFO3 到 L2 的逐 word 写不会直接显示为 `STREAM` 事件。它命中 L2 时不访问
AXI；第一次写入尚未缓存的目标 line 时，才会以 `DATA` 事件体现 write-allocate。

本次对齐后的 32 个 `STREAM` 地址为：

```text
0x80000200, 0x80000280, ..., 0x80001180
```

步长为 128B，对应顺序读取源矩阵的 32 行。目标区在搬运阶段产生的 32 个
`DATA` 地址为：

```text
0x80001200, 0x80001280, ..., 0x80002180
```

这正是目标矩阵覆盖的 32 条 L2 cache line。trace 中较早出现的
`DATA 0x80012f80` 属于程序自身的其他数据访问；约 100000 cycle 后再次出现的
源/目标地址来自 C 程序逐元素验算，均不能计入 layout store 的 write-allocate
数量。分析时应同时按地址范围和时间阶段筛选，不能只统计全部 `DATA` 数量。

### 6.4 连续 burst 的计算方法

按 `ts` 排序 STREAM 记录，对相邻两项计算：

```text
startDelta[i] = ts[i] - ts[i-1]
jsonGap[i]    = ts[i] - (ts[i-1] + dur[i-1])
```

前两个 burst 用于填满 FIFO3 的两个 segment，随后目标区首次写入触发 32 次
write-allocate。第 3、4 个 burst 仍处于启动阶段，因此吞吐对比使用第 5 到第 32
个 burst。三版 trace 的结果为：

| 实现 | burst 起点平均间隔 | `timeline.json` 平均间隔 |
| --- | ---: | ---: |
| 每个 word 等待 L2 `wrsp` | 97.68 cycles | 51.36 cycles |
| Stage 1 接收后推进 FIFO3 | 64.00 cycles | 16.57 cycles |
| L2 支持连续 STE write | 46.79 cycles | 2.00 cycles |

最终 28 个 burst 的间隔为 42 到 56 cycles，取决于各自 AXI burst 的实际响应
时长。`timeline.json` 公式得到的间隔固定为 2 cycles，但这里需要结合原始日志
理解：`trace_timeline.py` 校验的关系是：

```text
rawEnd = ts + dur + 1
```

而稳态阶段实际满足：

```text
nextStart = rawEnd + 1
```

即上一 AXI read 的 end 事件出现后，下一周期就发出新的 start；JSON 中的 2 是
转换脚本对 `dur` 的记账边界，不代表中间仍有两个完整的 AXI 空闲周期。以 AXI
事务边界来看，后续 STREAM burst 已经连续衔接，原来由 FIFO3/L2 写反压造成的
长空洞已经消失。

目标矩阵按 128B 对齐后，目标区 write-allocate/refill 地址严格为
`0x80001200` 到 `0x80002180`，共 32 条 L2 cache line，不再额外跨入第 33 条
line。

## 7. 当前限制和后续工作

1. 没有 completion/fence。固定 `nop` 只能用于当前仿真测试，正式软件接口应能
   查询或等待 FIFO3 load/store 完成。
2. STE 直接写 L2 时不会主动更新或失效 DCache 中已经存在的同地址 line。当前
   软件必须保证目标区域此前未被 DCache 缓存，或后续增加 cache flush/invalidate。
3. 输入 dim0 固定为一个连续的 32-word line，尚不支持其他 line 长度。
4. FIFO3 到 L2 当前按 32-bit word 提交，复用 Channel 2 的流水与反压，没有做
   cache-line 写合并。
5. 每个 line 必须全部 ready 后才能开始 store，尚未实现按 word ready 提前写出。
6. 没有独立的 transpose write-event golden model，也没有检查额外写请求数量。
7. layout store 没有单独的软件可见 done 状态。数据耗尽后 ready map 会阻止继续
   发请求，新一次 `cfg_axi_load(..., 3)` 会重置该通路。

## 8. 本次修改边界

本次 layout transform 直接涉及：

```text
主仓库:
  src/main/scala/Config/Config.scala
  src/main/scala/Backend/Stream.scala
  src/main/scala/Backend/Memory/L2Cache.scala
  src/main/scala/Backend/Memory/L2CacheFSM.scala
  src/main/scala/CPU.scala

RV-Software:
  stream-matrix/src/stream-transpose.c

ZirconSim:
  src/Emulator.cc
```

工作区中的 `stream-matrix-k128.c`、`stream-nd-addr.c`、N-D 地址文档以及
`ZirconSim/StreamDifftest.*` 属于之前的 N-D/K128 工作，不是本次 layout
transform 新增内容。
