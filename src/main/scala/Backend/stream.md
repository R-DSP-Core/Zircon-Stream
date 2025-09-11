# 基本设计思路

1. 流的种类：
   目前定义了`iter流`和`访存流`，`iter流`对应程序中的循环计数`i`，`访存流`对应程序中对数据的访问

2. 流的访问模式：
   1. 线性访问：A[i] 
   2. TODO:间接索引：A[B[i]]
   3. TODO:pointer-chasing
   
3. 流的依赖关系：
   目前仅实现 `iter流`->`访存流`的依赖关系，即A[i]的线性访问模式

4. 流的配置：
   1. `iter流`配置指令：cfg_i i_id(rs1), FIFO_id(rs2)：用于初始化iCntMap[i_id]=0 streamMap[FIFO_id] = i_id；
   2. `stream流`配置指令
      1. 配置对应的`iter`流：cfg_stream: addr(rs1), FIFO_id(rs2)：addrCfg[FIFO_id] = addr 
      2. 配置访存首地址：cfg_addr: addr(rs1), FIFO_id(rs2) 
      3. ...

5. 流与主核的交互：
   1. 流计算指令：cal {FIFO_id_src0，FIFO_id_src1}(rs1)，FIFO_id_dest(rs2)：
   ```
   iter=iCntMap[streamMap[FIFO_id]]；
   index = iter % FIFO_length
   src=FIFO[FIFO_id][index]；
   dest = src cal src
   FIFO[FIFO_id][index]=dest
   ```
   计算前 需要检查对应的load fifo index是否ready(`有数据可以计算`)，store fifo index是否!ready(`计算指令有空位写入`)
   计算后 将对应的load fifo index置为!ready(`可以向主存取数填入`)，store fifo index置为ready(`可以向主存存数`)
   这里假设每个index只会访问一次（因为是流？），读过之后置为空闲，腾出位置方便后续memory的加载

   2. 配置指令和流计算指令目前走muldiv流水线，在该流水线中实例化了一个Stream Engine(SE)用于处理指令(类似于mul srt模块)。目前所有指令在SE中都是顺序单周期执行

6. 流与存储系统的交互
   读
   1. select stream：选择一个已经配置好的，FIFO充裕的流读主存
      1. load流!ready(`可以向主存取数填入`)；
      2. 直连AXI Arbiter，与L2竞争AXI端口
   2. write Fifo：为FIFO维护一个offset，主存读数据从offset索引开始写FIFO：FIFO[FIFO_id][offset+cnt]，其中cnt是主存读字计数器，假设一次读32个字，则cnt=[0,31]
      1. 写入时置ready(`有数据可以计算`)
      2. 完成填入后
   写
   1. select stream：store流在满足条件下可以发送写请求
      1. store流ready(`可以向主存存数`)
   2. write Mem
      1. 完成写置!ready(`计算指令有空位写入`)
   

说明
1. 为什么需要`iter流`？`访存流`取回来的数据会放置在一个FIFO中，`iter`用作索引(其他实现方式包括“消耗性读”，即读完FIFO中的某项就弹出，自动读下一项，但这样无法处理FIFO中某项不需要使用的情况)
2. 配置指令为什么不一条指令全部配置好：这个后面再说，因为源操作数可能会比较多，按属性进行cfg也方便扩展
3. 后续可以考虑的：
   1. 弃用FIFO，改用特殊地址空间作为预取Buffer，流计算指令复用load uncache的数据通路获得它的操作数(应该是 load uncache，load uncache, calculate)
   2. 细化流指令流水级，两种方案：1.在SE内拆分流水线 2.复用外部流水线







---
指令：
cfg i: i_id
cfg stream: i_id, FIFO_id 
cal FIFO_id, FIFO_id / archReg
step i

部件：
streamMap：FIFI_id -> i_id
iCntMap：i_id -> itercnt


cfg i：
* 预译码 -> iCntMap[i_id] = 0 

cfg stream
* 预译码 -> streamMap[FIFO_id] = i_id
* Exe: Definition，State，ReadyMap

step i：
* 预译码 -> iCntMap[i_id] ++

cal:
* 预译码 -> i_id = streamMap[FIFO_id] 【TODO这里也可以让instCode直接携带i_id号】
* 译码 -> itercnt = iCntMap[i_id]  【TODO此处有数据相关，假设后续这里阻塞，有可能会读出错的iter】
* 发射 -> readyMap[FIFO_id][itercnt] ready?
* 读寄存器 -> op = FIFO[FIFO_id][itercnt]
* Exe


---

Stream Engine：

Defination(regs): FIFO_id -> { pattern, stride, width, length  }
1. pattern (affine, indirect, linked) 3bit
2. stride (4Byte) 2bit?
3. width (4Byte) 2bit?
4. length () bit?

State(regs)：current address 32bit
Operands：TODO

FIFO(regs)：FIFO_id -> .num x 32'b data
ReadyMap(regs)：FIFO_id -> .num x 1'b ready



异步操作：
1. select stream(利用readyMap判断FIFO是否满；利用Operands判断是否就绪TODO)->(Oldest-first)
2. request L1 Cache(TODO:1.端口 2.FIFO_id信息？)
3. FIFO write，State += stride


52.48 2.23
50    0.02
数据总量=1*5120*1B + 10240*5120*1B + 1*10240*2B = 52454400 B = 4.77 × 10⁻⁵ TB
运行时间=44.13us = 4.413 × 10⁻⁵s
带宽利用率= 1.08TB/s

72.13 36.67 = 108
60   16.67
数据总量=2048*5120*1B + 10240*5120*1B + 2048*10240*2B = 104857600 B = 9770 × 10⁻⁵GB
运行时间=343.49us = 34.349× 10⁻⁵s
带宽利用率= 305GB/s



lrc__lts2lrc_sectors_op_read.sum * (32)
lrc__xbar2gpc_sectors_op_read.sum * 32

lrc: The L2 Request Coalescer (LRC) processes incoming requests for L2 and tries to coalesce read requests before forwarding them to the L2 cache.
It also serves programmatic multicast requests from the SM and supports compression for writes.

sectors: Aligned 32 byte-chunk of memory in a cache line or device memory.
An L1 or L2 cache line is four sectors, i.e. 128 bytes.
Sector accesses are classified as hits if the tag is present and the sector-data is present within the cache line.
Tag-misses and tag-hit-data-misses are all classified as misses.

LTS: A Level 2 (L2) Cache Slice is a sub-partition of the Level 2 cache.

lts__t refers to its Tag stage. lts__m refers to its Miss stage. lts__d refers to its Data stage.