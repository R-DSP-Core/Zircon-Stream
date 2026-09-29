// Reuse the existing FFT implementation, but run one 32-point transform.
#define main fft_original_main
#include "../../RV-Software/cfft-4k-sh-stream/src/cfft_4k-sh-stream.c"
#undef main

int main(void)
{
    complex_t expected[32];
    for (int i = 0; i < 32; ++i) {
        temp[0][i].real = temp[0][i].imag = i < 16 ? i : 0;
        expected[i] = temp[0][i];
    }
    fft_32_stockham(expected);
    stream_fence();
    cfg_i(1, 32, 0); cfg_i(1, 32, 1);
    cfg_i_limit(2, 0); cfg_i_limit(2, 1);
    cfg_i_repeat(2, 0); cfg_i_repeat(2, 1);
    cfg_reuse(2, 0); cfg_reuse(2, 1);
    cfg_stride(4, 0); cfg_stride(4, 1);
    cfg_tilestride(256, 0); cfg_tilestride(256, 1);
    cfg_load((uint32_t)(uintptr_t)temp[0], 0);
    cfg_load((uint32_t)(uintptr_t)&temp[0][16], 1);
    stream_fence();
    fft_32_stockham_stream(temp[0]);
    stream_fence();
    for (int i = 0; i < 32; ++i) {
        if (temp[0][i].real != expected[i].real ||
            temp[0][i].imag != expected[i].imag) return 1;
    }
    return 0;
}
