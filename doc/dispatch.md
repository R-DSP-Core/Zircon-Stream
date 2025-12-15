





CPU
        dsp{io.bke} -------> bke{io.dsp}

CPU: dsp.io.bke <> bke.io.dsp
    dsp.io.bkePkg <> io.bke.instPkg
    bkePkg是一个 (niq 3) x (ndcd 3)

Backend：mdIQ.io.enq.zip(io.dsp.instPkg(1)).foreach{case (enq, inst) => enq <> inst}