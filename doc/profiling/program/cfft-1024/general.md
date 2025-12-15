1. 基本块内不同迭代 按每cycle提交 分 指令组
2. 找到该指令组阻塞原因
   1. CMT
   2. DC2 not 1，cache miss

从最晚WB开始算：
sw：受sw单发制约 +1
它的上一条sw：受单发制约 +1
它的上一条sw：add->sw 数据相关 +3  5
add：or->add 数据相关 +1
or：slli->or 数据相关 +1
slli：add->slli 数据相关 +1
add：sltu->add 数据相关 +1
sltu：add->sltu 数据相关 +1 （？1）  11
add：mul->add 数据相关 +3
乘法单发：2
disp阻塞：1，乘法单发：1
disp阻塞：1，乘法单发：1   20
乘法单发：2
lw->mul:3  25
访存单发：2
cache miss：6   33
差1拍：sw->lw +1  34
迭代间依赖 + 1 35



数据相关：15
   alu->lsu:3
   alu->alu:6
   mdu->alu:3
   lsu->mdu:3
单发制约：11
   sw:2
   lw:2
   sw->lw:1
   mul:6
cache miss：6
disp阻塞：2
未知：1

---

数据相关：
   alu->lsu:7966(2)
   alu->alu:7965,
   mdu->alu:3
   lsu->mdu:3
单发制约：
   sw:2
   lw:2
   sw->lw:1
   mul:6
cache miss：
disp阻塞：
未知：
 



l1 7
l2 55    62
预测失误：13  
aa：1+1+1+1+1    67   7965 
mula:3   70
alu lsu：3
lw mul：5  78

sw单：3
mul单：3  84






未知137994：1（唤醒策略真有问题）
未知137988：2
未知137986：1
未知137974：1

89


