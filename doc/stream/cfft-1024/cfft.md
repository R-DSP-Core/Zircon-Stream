基本块 20：CPI接近1，占比最长
关注 CMT不长的那些指令，那是瓶颈

疑惑点：
dispatch阻塞原因：需要所有IQ都ready，各IQready的条件是有>=ew的空闲数量
cmt阻塞原因

重点：
去分析为什么
l1miss
l2miss

一些阻塞原因：
cache miss
固有的数据相关(alu lw->mul:3)
乘除指令不高效
    【DISP级堵塞，后续的乘除指令逻辑上已经可以发射，却还没进入IQ（可以增大发射队列看看）】
sw单发射

