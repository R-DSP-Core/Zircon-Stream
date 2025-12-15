1. 程序片段执行时间 = trace中最晚wbROb的指令cycle - 最早wbRob的指令cycle

2. 期望输出：各种阻塞类型 造成的 延迟占比
   1. 支持分级输出
      1. 分支预测失误
      2. cache-miss
         1. 按数组 & L1/L2 往下拆分 
      3. 数据相关
         1. 按 alu->lsu lsu->mdu mdu->alu等拆分
      4. 部件相关
         1. alu三发射
         2. lsu单发射
            1. sw->sw
            2. sw->lw
            3. lw->lw
         3. mdu单发射
      5. 其他类型

需要你帮忙写脚本，注意脚本的易读性

前提：
1. trace中所有指令pc都以0x80000开头，后续省略
2. mdu指令就是乘除法，lsu指令就是lw sw这种，其他的都是alu指令 

算法：
检查到的前序指令记录为curInst
1. 对于sw指令 2d8 2e0 2e8
   1. 检查RF cycle是否等于 上一条sw指令X 的 RF cycle+1
      1. 若是，curInst = X，记录部件相关(lsu单发射)(sw->sw)=1
      2. 否则
         1. 若curInst不是2d8就报错，输出指令序号方便我debug
         2. 若是2d8，检查RF cycle 是否等于依赖指令 RF cycle+2（我会给你一个总表格，这里是2d4），记录数据相关(alu->lsu)(add->sw)+2
            1. 不是就报错，输出指令序号方便我debug

2. 对于alu指令
   1. 检查其依赖指令的RFcycle，选择离本条指令RF最近的一条依赖指令X(若一样近，选序号靠后的)
      1. X 的RFcycle +x=本指令RF，记录数据相关(xxu->alu)(xxinst->xxinst)
         1. 若X为alu指令
            1. 记录cycle = 1
            2. 若x>1，记录为奇怪现象 原因 pc组
         2. 若X为mdu指令
            1. 记录cycle = 3
            2. 若x>4，记录为奇怪现象 原因 pc组
         3. 若X为lw指令
            1. 记录cycle = 1
            2. 若x>2
               1. 若lw指令D2段不大于5（说明没miss）且D1段不大于5（说明没被阻塞），则记录为奇怪现象 原因 pc组
      2. 如果检查了30条指令还没找到，记录 “断点” 该指令pc（重复的不用记），同时将curInst置为最近的一个sw
3. 对于mdu指令 28c 290 294 298   26c 270 274 278，可以分2组
   1. 检查RF cycle是否等于 组内“所有-包括后面”某一条乘法指令X 的 RF cycle+1 
      1. 若是，curInst = X，记录部件相关(mdu单发射)(mul->mul)=1
      2. 若没找到，找数据相关依赖指令，检查其依赖指令的RFcycle，选择离本条指令RF最近的一条依赖指令X(若一样近，选序号靠后的)
         1. 若X为alu指令
            1. 记录cycle = 4
            2. 若x>2，记录为奇怪现象 原因 pc组
         2. 若X为mdu指令
            1. 报错
         3. 若X为lw指令
            1. 记录cycle = 3
            2. 若x>2
               1. 若lw指令D2段不大于5（说明没miss）且D1段不大于5（说明没被阻塞），则记录为奇怪现象 原因 pc组
   2. 如果检查了30条指令还没找到，报错退出
4. 对于lw指令 25c(output) 260(twiddle) 264(output) 268(twiddle)  |  2bc(output) 2c4(output) 两组
   1. 优先：查出自己是否cache miss（D2->WB >1就是miss 小于10是L1 大于10是L2，按twiddle和output分类），记录；然后找前序阻塞指令
   2. 其次：组内还是做类似mdu的资源部件检查，记住是组内“所有-包括后面”
   3. 其次：对于前一组，要观察2e8的RF+1是否等于我们的RF，若是则记录部件相关(lsu单发射)(sw->lw)=1
   4. 再然后：10条指令内找数据相关
        1. 若X为alu指令
            1. 记录cycle = 2
            2. 若x>2，报错退出
        2. 若X为mdu指令
            1. 报错
        3. 若X为lw指令
            1. 报错

起始
1. 从trace的最后一条 pc=2e8 sw 开始，按上面的规则去做
