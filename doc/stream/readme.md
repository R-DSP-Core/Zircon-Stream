# stream开发整理

## 开发
1. coding
2. profiling/debug效率
3. 环境更新

## [正式文档](./正式/流式访存总结文档.md)

流式访存正式文档，包含实现说明和case分析

## Version

### [V0](./work/V0/) 

这一版是将Stream Engine接到乘除流水线，
在单周期完成 是否就绪的判断 以及 读buffer+算+写buffer的操作

基本完成测试，可循环测试向量加程序并与原始程序对比（但outer iteration=20时仍有bug）
   
### [V1](./work/V1/)

这一版是将 判断就绪 读buffer 算 写buffer拆开至不同流水级
在VADD上进行了初步验证

### [V2](./work/V2/)

这一版做了较大的改动，使其支持GEMM

### [VFFT](./work/VFFT/)

针对FFT的设计，停留在初步的分析阶段






