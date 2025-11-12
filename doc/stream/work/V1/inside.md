# V1 - 内嵌模式

将 判断就绪 读buffer 算 写buffer拆开至不同流水级


## 指令格式

TODO：目前已经弃用 “把源和目的fifo号存放在指令码寄存器操作数位” - 已经写死在硬件中

## 基本设计

1. 与流水线耦合
   1. 译码：处理 rj rk rd valid
   2. 重命名
   3. 派发
      1. itercnt需要在这一级递增
   4. 发射
      1. 获取 index
      2. 检查buffer对应项是否就绪
   5. 执行
      1. Readop：从buffer/寄存器中对应项取数
      2. 计算
      3. writeback：写buffer/寄存器

2. Note：不可以把指令需要的buffer号放在通用寄存器里，这样的话Readop阶段会读两次操作数

   
