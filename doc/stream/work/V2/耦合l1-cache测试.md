## 1. Vadd耦合cache测试分析
1. ![alt text](image-1.png)
Total cycles: 3382, Total insts: 1571
这个是不对齐版本下的示意图 每次用到的L2-line 前15个数是上一个大iter取出来的，还可以用，后17个数会L2 miss一次，l1miss一次
**现在的L2不会连续取了**，中间间隔的时间组成：
miss前：A数组一个个读前15个数
触发miss
miss后：l2miss填好自己之后，才能一个一个的向stream发（多了发数周期=15 cycles + l1-miss大概7 cycle）
1. 即使使用bypass，也只能省掉miss后的cycle，miss前的无法省略（除非数组对齐）
2. 引发的思考就是：向cache请求会带来性能损失，因此只有部分需要复用的流才需要向cache请求数据，其他无需复用的流不用