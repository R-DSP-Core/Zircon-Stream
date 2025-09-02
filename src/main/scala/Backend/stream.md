Methodology
1. 为什么不vector_add:因为不只有这种程序段，所以还是保持普通运算指令的指令样式
2. 


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




