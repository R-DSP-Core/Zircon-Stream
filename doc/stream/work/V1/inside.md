# V1 - 内嵌模式

将 判断就绪 读buffer 算 写buffer拆开至不同流水级


## 指令格式

操作码

### CFGI
jk
长度 fifoid

### CFGSTREAM
jk
地址 fifoid

### CALSTREAM
无操作数（默认 0 1 2号fifo），并且iter是在流水线中动态获取

TODO：
不可以把指令需要的buffer号放在通用寄存器里，这样的话Readop阶段会读两次操作数
目前已经弃用 “把源和目的fifo号存放在指令码寄存器操作数位” - 已经写死在硬件中

## 基本设计

1. 与流水线耦合
   1. 预译码：处理 rj rk rd valid
   2. 译码：decode出指令操作码+是否为流计算指令
   3. 派发
      1. itercnt需要在这一级递增
      2. streamfire可以用robEnq fire获得
   4. 发射
      1. 时机分为 进入IQ当拍 和 后续的cycle
      2. 检查buffer对应项是否就绪
   5. 执行
      1. Readop：从buffer/寄存器中对应项取数
      2. 计算：来自于decode_stage的op0_3，目前写死为0，即加法
      3. writeback：写buffer/寄存器

2. 流engine
   1. 异步部分：alu的读写

## DEBUG过程

1. 检查alu itercnt和算的数
2. 检查fifo2 的数
3. 检查写回memory的数

## TODO

1. 分支预测失误恢复：拷贝一个icntMap commit时赋值，flush时恢复
2. 测试大循环，记录测试数据
3. 可以考虑使用差分测试，但我觉得有没必要特别完备


## 与cache的耦合

TODO:这个应该是后两周去做

