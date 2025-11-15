## TODO：功能部件可视化

根据指令tick
ALU 
LSU
MDU

根据部件tick
L1
L2
AXI

## TODO：对齐[真实阻塞原因]


`总体思路`：
先识别原因，指令流经各级流水的时候统计原因（大概是一个vec，如果有问题就拉高），最终放入ROB
emulator可以把真实原因输出

`关键`：有些原因可能会重叠，可以先统计出来看看
1. 数据依赖 > icache miss 
2. misspre 与 icache miss：若判断对了，即使icache miss
   1. delay也会减少：misspre
   2. delay

`tick 顺序分析`：
normal(2 retire)
can't retire(1 delay)
frontend
dependency

TODO`信息不足`：
1. 退休延迟
2. 27 fetch的停顿
motivation：指令视角还是略显混乱，可能要以
    访存相关
    阻塞原因相关


### 前端

1. icache miss
2. missprediction delay

### 数据依赖
1. alu -> lsu (alu在rf时唤醒lsu)：delay = 3
2. lsu -> lsu (lsu在D1时唤醒lsu)：delay = 3
3. mdu -> alu (mdu在E3时唤醒alu)：delay = 3

### 后端

1. dcache miss
   1. l1 miss：
   2. l2 miss：dram bound
2. 退休限制：双槽 [clusterIndexFIFO]
3. 资源受限
   1. 单发射lsu
   2. storebuffer数量（但这个本质还是lsu瓶颈造成）

### Note

1. 前端时序（可参考架构图）
PF 发给 icache，可以顺利到F
F那一拍还没得到是否miss，顺利到PD
在PD被阻塞

2. 后端storebuffer流程
   1. stcmt
   2. commit位拉高
   3. 发向dcache channel 2：stage1
   4. stage2
   5. stage3 -> l2 stage1
   6. l2 stage2
   7. l2 stage3 -> storefinish
   8. not full
第一条store到第五条之间至少会有7 cycles delay（stb not full，后续指令才能不被阻塞在D2级）
store在第二次来到l1-d有可能被阻塞，!io.l2.miss && !io.l2.rreq
io.l2.miss有两个原因：
   dcHazard：当l2的s3有写请求时会报l2 miss，这会使得l2_s1(l1_s3)被阻塞(24号sw被阻塞2拍也是这个原因：前面的sw因为他的两个前继停留在s3两拍而被阻塞了两拍)
   真的miss了
store l2 miss需要向主存发请求，对store主导的程序也会有影响的：因为后续的store进不去storebuffer，会卡在d2级
