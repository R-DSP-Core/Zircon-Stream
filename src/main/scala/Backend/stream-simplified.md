允许cal携带id号

cfg i：
* iCntMap[i_id] = 0 

cfg stream
* streamMap[FIFO_id] = i_id
* Definition，State，ReadyMap

step i：
* iCntMap[i_id] ++

cal:
* itercnt = iCntMap[i_id]  【这里时序肯定不好，可以把icnt拆到预译码】
* readyMap[FIFO_id][itercnt] ready?
* op = FIFO[FIFO_id][itercnt]
* Exe

---

1. 这些指令的数据通路，放置的iq fu(其实还好，主要是cal指令)
2. 预取器放置的位置，应该向 L1? L2? or AXI发出请求


新增4条指令：
cfg i(i_id)
cfg stream(fifo_id i_id)
step i(i_id)
cal(fifo_id i_id)

Q：streamIQ
FU：streamFU

icntMap
streamMap
Stream Engine：
    Defination(regs): FIFO_id -> { pattern, stride, width, length  }
      1. pattern (affine, indirect, linked) 3bit
      2. stride (4Byte) 2bit ?
      3. width (4Byte) 2bit ?
      4. length () bit?
    State(regs)：current address 32bit
    FIFO(regs)：FIFO_id -> .num x 32'b data
    ReadyMap(regs)：FIFO_id -> .num x 1'b ready


译码/重命名 不需要参加
DP到streamIQ
无阻塞顺序发射到streamFU
读操作数 不需要参加
执行级对3个东西操作一下

stream Engine的异步发请求：先直接发送到AXI？


